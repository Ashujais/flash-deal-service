package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.error.AppException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

sealed class IdempotencyOutcome {
    object NewExecution : IdempotencyOutcome()
    data class Replay(val statusCode: Int, val responseBody: String) : IdempotencyOutcome()
}

data class IdempotencyRecord(
    val customerId: UUID,
    val key: String,
    val dealId: UUID,
    val quantity: Int,
    val statusCode: Int?,
    val responseBody: String?,
    val createdAt: Instant,
    val lockedUntil: Instant
)

class IdempotencyRepository {

    private val validKeyPattern = Regex("""^[A-Za-z0-9\-_]{1,64}$""")

    fun validateKeyFormat(key: String?) {
        if (key.isNullOrBlank() || !validKeyPattern.matches(key)) {
            throw AppException.IdempotencyKeyInvalid("Idempotency-Key header is missing or malformed")
        }
    }

    /**
     * Checks or locks the idempotency key atomically.
     */
    fun acquireOrCheck(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int,
        now: Instant = Instant.now()
    ): IdempotencyOutcome {
        val selectSql = """
            SELECT customer_id, idempotency_key, deal_id, quantity, status_code, response_body, created_at, locked_until
            FROM idempotency_keys
            WHERE customer_id = ? AND idempotency_key = ?
            FOR UPDATE;
        """.trimIndent()

        val record: IdempotencyRecord? = conn.prepareStatement(selectSql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }

        if (record == null) {
            // Insert in-progress record
            val insertSql = """
                INSERT INTO idempotency_keys (customer_id, idempotency_key, deal_id, quantity, status_code, response_body, created_at, locked_until)
                VALUES (?, ?, ?, ?, NULL, NULL, ?, ?);
            """.trimIndent()

            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setString(2, key)
                stmt.setObject(3, dealId)
                stmt.setInt(4, quantity)
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.setTimestamp(6, Timestamp.from(now.plus(60, ChronoUnit.SECONDS)))
                stmt.executeUpdate()
            }
            return IdempotencyOutcome.NewExecution
        }

        // Record exists, verify deal_id and quantity
        if (record.dealId != dealId || record.quantity != quantity) {
            throw AppException.IdempotencyKeyReused("Idempotency key already used for a different deal or quantity")
        }

        if (record.statusCode != null && record.responseBody != null) {
            // Already finished. Check 24 hour window
            val isWithin24Hours = now.isBefore(record.createdAt.plus(24, ChronoUnit.HOURS))
            if (isWithin24Hours) {
                return IdempotencyOutcome.Replay(record.statusCode, record.responseBody)
            } else {
                // Key expired after 24 hours, reset for new attempt
                updateLock(conn, customerId, key, now)
                return IdempotencyOutcome.NewExecution
            }
        }

        // In progress. Check locked_until (60 seconds crash timeout)
        if (now.isBefore(record.lockedUntil)) {
            throw AppException.RequestInProgress("A request with this idempotency key is currently in progress")
        }

        // Lock expired -> previous attempt crashed! Take over lock.
        updateLock(conn, customerId, key, now)
        return IdempotencyOutcome.NewExecution
    }

    fun complete(
        conn: Connection,
        customerId: UUID,
        key: String,
        statusCode: Int,
        responseBody: String
    ) {
        val updateSql = """
            UPDATE idempotency_keys
            SET status_code = ?, response_body = ?
            WHERE customer_id = ? AND idempotency_key = ?;
        """.trimIndent()

        conn.prepareStatement(updateSql).use { stmt ->
            stmt.setInt(1, statusCode)
            stmt.setString(2, responseBody)
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            stmt.executeUpdate()
        }
    }

    fun releaseInProgress(customerId: UUID, key: String) {
        val deleteSql = """
            DELETE FROM idempotency_keys
            WHERE customer_id = ? AND idempotency_key = ? AND status_code IS NULL;
        """.trimIndent()

        DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(deleteSql).use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setString(2, key)
                stmt.executeUpdate()
            }
        }
    }

    private fun updateLock(conn: Connection, customerId: UUID, key: String, now: Instant) {
        val sql = """
            UPDATE idempotency_keys
            SET locked_until = ?, status_code = NULL, response_body = NULL, created_at = ?
            WHERE customer_id = ? AND idempotency_key = ?;
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(now.plus(60, ChronoUnit.SECONDS)))
            stmt.setTimestamp(2, Timestamp.from(now))
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            stmt.executeUpdate()
        }
    }

    private fun mapRow(rs: ResultSet): IdempotencyRecord {
        val sc = rs.getInt("status_code")
        val statusCode = if (rs.wasNull()) null else sc
        return IdempotencyRecord(
            customerId = rs.getObject("customer_id", UUID::class.java),
            key = rs.getString("idempotency_key"),
            dealId = rs.getObject("deal_id", UUID::class.java),
            quantity = rs.getInt("quantity"),
            statusCode = statusCode,
            responseBody = rs.getString("response_body"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lockedUntil = rs.getTimestamp("locked_until").toInstant()
        )
    }
}
