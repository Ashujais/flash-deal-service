package com.nnomm.flashdeal.repository

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.DealResponse
import com.nnomm.flashdeal.domain.toIsoUtcString
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.*

data class DealRecord(
    val id: UUID,
    val restaurantId: UUID,
    val title: String,
    val pricePaise: Long,
    val stockTotal: Int,
    val stockAvailable: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val createdAt: Instant
) {
    fun toResponse(): DealResponse = DealResponse(
        id = id.toString(),
        restaurantId = restaurantId.toString(),
        title = title,
        pricePaise = pricePaise,
        stockTotal = stockTotal,
        stockAvailable = stockAvailable,
        startsAt = startsAt.toIsoUtcString(),
        endsAt = endsAt.toIsoUtcString()
    )
}

class DealRepository {

    fun create(
        id: UUID,
        restaurantId: UUID,
        title: String,
        pricePaise: Long,
        stockTotal: Int,
        startsAt: Instant,
        endsAt: Instant
    ): DealResponse {
        val sql = """
            INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at, created_at;
        """.trimIndent()

        return DatabaseFactory.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, restaurantId)
                stmt.setString(3, title)
                stmt.setLong(4, pricePaise)
                stmt.setInt(5, stockTotal)
                stmt.setInt(6, stockTotal) // Initially stock_available == stock_total
                stmt.setTimestamp(7, Timestamp.from(startsAt))
                stmt.setTimestamp(8, Timestamp.from(endsAt))

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        mapRow(rs).toResponse()
                    } else {
                        throw IllegalStateException("Failed to insert deal")
                    }
                }
            }
        }
    }

    fun findById(id: UUID): DealRecord? {
        val sql = """
            SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at, created_at
            FROM deals
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

    fun findByIdForUpdate(conn: Connection, id: UUID): DealRecord? {
        val sql = """
            SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at, created_at
            FROM deals
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

    fun deductStock(conn: Connection, dealId: UUID, quantity: Int): Int {
        val sql = """
            UPDATE deals
            SET stock_available = stock_available - ?
            WHERE id = ? AND stock_available >= ?;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.setInt(3, quantity)
            stmt.executeUpdate()
        }
    }

    fun restoreStock(conn: Connection, dealId: UUID, quantity: Int): Int {
        val sql = """
            UPDATE deals
            SET stock_available = stock_available + ?
            WHERE id = ?;
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.executeUpdate()
        }
    }

    private fun mapRow(rs: ResultSet): DealRecord {
        return DealRecord(
            id = rs.getObject("id", UUID::class.java),
            restaurantId = rs.getObject("restaurant_id", UUID::class.java),
            title = rs.getString("title"),
            pricePaise = rs.getLong("price_paise"),
            stockTotal = rs.getInt("stock_total"),
            stockAvailable = rs.getInt("stock_available"),
            startsAt = rs.getTimestamp("starts_at").toInstant(),
            endsAt = rs.getTimestamp("ends_at").toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }
}
