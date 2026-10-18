package com.nnomm.flashdeal.db

import com.nnomm.flashdeal.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun init(config: AppConfig): DataSource {
        if (dataSource != null) return dataSource!!

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 25
            minimumIdle = 5
            idleTimeout = 30000
            connectionTimeout = 15000
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        val ds = HikariDataSource(hikariConfig)
        dataSource = ds

        // Run migrations
        runFlyway(ds)

        return ds
    }

    fun getDataSource(): DataSource = dataSource ?: throw IllegalStateException("DatabaseFactory not initialized")

    fun runFlyway(ds: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        val ds = getDataSource()
        return ds.connection.use { conn ->
            block(conn)
        }
    }

    fun <T> withTransaction(block: (Connection) -> T): T {
        val ds = getDataSource()
        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                try {
                    conn.autoCommit = true
                } catch (_: Exception) {}
            }
        }
    }
}
