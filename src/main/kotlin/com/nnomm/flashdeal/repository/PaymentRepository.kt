package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.db.DatabaseFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.*

class PaymentRepository {

    /**
     * Attempts to record a payment webhook event.
     * Returns true if newly inserted, false if already processed (duplicate event_id).
     */
    fun recordEventIfNew(
        conn: Connection,
        eventId: String,
        claimId: UUID,
        eventType: String,
        amountPaise: Long,
        occurredAt: Instant
    ): Boolean {
        val sql = """
            INSERT INTO payment_events (event_id, claim_id, event_type, amount_paise, occurred_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventId)
            stmt.setObject(2, claimId)
            stmt.setString(3, eventType)
            stmt.setLong(4, amountPaise)
            stmt.setTimestamp(5, Timestamp.from(occurredAt))
            val updated = stmt.executeUpdate()
            updated > 0
        }
    }

    fun hasEvent(eventId: String): Boolean {
        val sql = "SELECT 1 FROM payment_events WHERE event_id = ?;"
        return DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, eventId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }
}
