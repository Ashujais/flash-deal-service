package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.ClaimResponse
import com.nnomm.flashdeal.domain.ClaimStatus
import com.nnomm.flashdeal.domain.toIsoUtcString
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.*

data class ClaimRecord(
    val id: UUID,
    val dealId: UUID,
    val customerId: UUID,
    val quantity: Int,
    val amountPaise: Long,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Requirement: From expires_at on, the API never shows it as RESERVED.
     */
    fun effectiveStatus(now: Instant = Instant.now()): String {
        return if (status == ClaimStatus.RESERVED.name && !now.isBefore(expiresAt)) {
            ClaimStatus.EXPIRED.name
        } else {
            status
        }
    }

    fun toResponse(now: Instant = Instant.now()): ClaimResponse = ClaimResponse(
        id = id.toString(),
        dealId = dealId.toString(),
        customerId = customerId.toString(),
        quantity = quantity,
        amountPaise = amountPaise,
        status = effectiveStatus(now),
        expiresAt = expiresAt.toIsoUtcString()
    )
}

class ClaimRepository {

    fun insert(
        conn: Connection,
        id: UUID,
        dealId: UUID,
        customerId: UUID,
        quantity: Int,
        amountPaise: Long,
        status: String,
        expiresAt: Instant
    ): ClaimRecord {
        val sql = """
            INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.setObject(2, dealId)
            stmt.setObject(3, customerId)
            stmt.setInt(4, quantity)
            stmt.setLong(5, amountPaise)
            stmt.setString(6, status)
            stmt.setTimestamp(7, Timestamp.from(expiresAt))

            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else throw IllegalStateException("Failed to insert claim")
            }
        }
    }

    fun findById(id: UUID): ClaimRecord? {
        val sql = """
            SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
            FROM claims
            WHERE id = ?;
        """.trimIndent()

        return DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    fun findByIdForUpdate(conn: Connection, id: UUID): ClaimRecord? {
        val sql = """
            SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
            FROM claims
            WHERE id = ?
            FOR UPDATE;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun hasActiveClaim(conn: Connection, dealId: UUID, customerId: UUID, now: Instant = Instant.now()): Boolean {
        // Active claim is RESERVED (where expires_at > now), PAID, or NEEDS_REVIEW
        val sql = """
            SELECT 1 FROM claims
            WHERE deal_id = ? AND customer_id = ?
              AND (
                (status = 'RESERVED' AND expires_at > ?)
                OR status IN ('PAID', 'NEEDS_REVIEW')
              )
            LIMIT 1;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, dealId)
            stmt.setObject(2, customerId)
            stmt.setTimestamp(3, Timestamp.from(now))
            stmt.executeQuery().use { rs ->
                rs.next()
            }
        }
    }

    fun updateStatus(conn: Connection, claimId: UUID, newStatus: String): Int {
        val sql = """
            UPDATE claims
            SET status = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, newStatus)
            stmt.setObject(2, claimId)
            stmt.executeUpdate()
        }
    }

    /**
     * Find claims whose expires_at is passed and status is still RESERVED.
     * Uses FOR UPDATE SKIP LOCKED so multiple service instances don't block each other!
     */
    fun findExpiredReservedClaimsForUpdate(conn: Connection, limit: Int = 100): List<ClaimRecord> {
        val sql = """
            SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
            FROM claims
            WHERE status = 'RESERVED' AND expires_at <= CURRENT_TIMESTAMP
            ORDER BY expires_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ClaimRecord>()
                while (rs.next()) {
                    list.add(mapRow(rs))
                }
                list
            }
        }
    }

    /**
     * Stretch Goal 1: Cursor-paginated claims for a customer
     */
    fun listCustomerClaims(
        customerId: UUID,
        cursorCreatedAt: Instant?,
        cursorId: UUID?,
        limit: Int
    ): List<ClaimRecord> {
        val sql = if (cursorCreatedAt != null && cursorId != null) {
            """
                SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
                FROM claims
                WHERE customer_id = ?
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                LIMIT ?;
            """.trimIndent()
        } else {
            """
                SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
                FROM claims
                WHERE customer_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?;
            """.trimIndent()
        }

        return DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, customerId)
                if (cursorCreatedAt != null && cursorId != null) {
                    stmt.setTimestamp(2, Timestamp.from(cursorCreatedAt))
                    stmt.setTimestamp(3, Timestamp.from(cursorCreatedAt))
                    stmt.setObject(4, cursorId)
                    stmt.setInt(5, limit)
                } else {
                    stmt.setInt(2, limit)
                }

                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<ClaimRecord>()
                    while (rs.next()) {
                        list.add(mapRow(rs))
                    }
                    list
                }
            }
        }
    }

    /**
     * Stretch Goal 4: Claims on a deal for restaurant admin with optional status filter
     */
    fun listDealClaims(dealId: UUID, statusFilter: String?): List<ClaimRecord> {
        val sql = if (!statusFilter.isNullOrBlank()) {
            """
                SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
                FROM claims
                WHERE deal_id = ? AND status = ?
                ORDER BY created_at DESC;
            """.trimIndent()
        } else {
            """
                SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at
                FROM claims
                WHERE deal_id = ?
                ORDER BY created_at DESC;
            """.trimIndent()
        }

        return DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, dealId)
                if (!statusFilter.isNullOrBlank()) {
                    stmt.setString(2, statusFilter.uppercase())
                }
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<ClaimRecord>()
                    while (rs.next()) {
                        list.add(mapRow(rs))
                    }
                    list
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): ClaimRecord {
        return ClaimRecord(
            id = rs.getObject("id", UUID::class.java),
            dealId = rs.getObject("deal_id", UUID::class.java),
            customerId = rs.getObject("customer_id", UUID::class.java),
            quantity = rs.getInt("quantity"),
            amountPaise = rs.getLong("amount_paise"),
            status = rs.getString("status"),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }
}
