package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.error.AppException
import java.util.*

class RateLimiter(private val enabled: Boolean = true) {

    /**
     * Enforces rate limit of 10 claim requests per customer per minute across instances.
     * Idempotent retries with the same key are not double-counted.
     */
    fun checkAndRecord(customerId: UUID, idempotencyKey: String? = null) {
        if (!enabled) return

        DatabaseFactory.withTransaction { conn ->
            // Prune old entries to prevent unbounded table growth
            val deleteOldSql = """
                DELETE FROM claim_rate_limits
                WHERE requested_at < (CURRENT_TIMESTAMP - INTERVAL '1 minute');
            """.trimIndent()
            conn.prepareStatement(deleteOldSql).use { it.executeUpdate() }

            // Count distinct recent attempts in the last 1 minute
            val countSql = """
                SELECT count(DISTINCT coalesce(idempotency_key, id::text))
                FROM claim_rate_limits
                WHERE customer_id = ? AND requested_at >= (CURRENT_TIMESTAMP - INTERVAL '1 minute');
            """.trimIndent()

            val count = conn.prepareStatement(countSql).use { stmt ->
                stmt.setObject(1, customerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }

            if (count >= 10) {
                // If this is an existing idempotency key in the window, allow it
                if (idempotencyKey != null) {
                    val keyExistsSql = """
                        SELECT 1 FROM claim_rate_limits
                        WHERE customer_id = ? AND idempotency_key = ? AND requested_at >= (CURRENT_TIMESTAMP - INTERVAL '1 minute');
                    """.trimIndent()
                    val exists = conn.prepareStatement(keyExistsSql).use { stmt ->
                        stmt.setObject(1, customerId)
                        stmt.setString(2, idempotencyKey)
                        stmt.executeQuery().use { it.next() }
                    }
                    if (exists) return@withTransaction
                }
                throw AppException.TooManyRequests("Rate limit of 10 claim requests per minute exceeded")
            }

            // Record this attempt
            val insertSql = """
                INSERT INTO claim_rate_limits (customer_id, idempotency_key, requested_at)
                VALUES (?, ?, CURRENT_TIMESTAMP);
            """.trimIndent()

            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, customerId)
                stmt.setString(2, idempotencyKey)
                stmt.executeUpdate()
            }
        }
    }
}
