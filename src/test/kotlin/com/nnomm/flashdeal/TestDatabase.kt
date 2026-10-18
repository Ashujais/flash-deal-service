package com.nnomm.flashdeal

import com.nnomm.flashdeal.config.AppConfig
import com.nnomm.flashdeal.db.DatabaseFactory
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import javax.sql.DataSource

object TestDatabase {
    private val logger = LoggerFactory.getLogger(TestDatabase::class.java)
    private var embeddedPostgres: EmbeddedPostgres? = null
    private var container: PostgreSQLContainer<*>? = null
    var testConfig: AppConfig? = null
        private set

    @Synchronized
    fun start(): AppConfig {
        if (testConfig != null) return testConfig!!

        val envUrl = System.getenv("DATABASE_URL")
        if (!envUrl.isNullOrBlank() && (envUrl.startsWith("jdbc:postgresql:") || envUrl.startsWith("postgres:") || envUrl.startsWith("postgresql:"))) {
            val jdbcUrl = if (envUrl.startsWith("postgres://")) {
                "jdbc:postgresql://${envUrl.removePrefix("postgres://")}"
            } else if (envUrl.startsWith("postgresql://")) {
                "jdbc:postgresql://${envUrl.removePrefix("postgresql://")}"
            } else {
                envUrl
            }
            logger.info("Using PostgreSQL DATABASE_URL from environment for tests: {}", jdbcUrl)
            val cfg = AppConfig(
                databaseUrl = jdbcUrl,
                databaseUser = System.getenv("DATABASE_USER") ?: "nnomm_user",
                databasePassword = System.getenv("DATABASE_PASSWORD") ?: "nnomm_pass",
                jwtSecret = "test_jwt_secret_key_1234567890_test_key",
                webhookSecret = "test_webhook_secret_key_1234567890",
                claimTtlSeconds = 3L
            )
            DatabaseFactory.init(cfg)
            testConfig = cfg
            return cfg
        }

        // Try Testcontainers if Docker is available
        var useZonky = true
        try {
            val pg = PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("nnomm_test")
                .withUsername("test_user")
                .withPassword("test_pass")
            pg.start()
            container = pg
            useZonky = false
            logger.info("Started Testcontainers PostgreSQL on {}", pg.jdbcUrl)

            val cfg = AppConfig(
                databaseUrl = pg.jdbcUrl,
                databaseUser = pg.username,
                databasePassword = pg.password,
                jwtSecret = "test_jwt_secret_key_1234567890_test_key",
                webhookSecret = "test_webhook_secret_key_1234567890",
                claimTtlSeconds = 3L
            )
            DatabaseFactory.init(cfg)
            testConfig = cfg
            return cfg
        } catch (e: Throwable) {
            logger.info("Docker/Testcontainers not available ({}), falling back to Embedded Postgres...", e.message)
            useZonky = true
        }

        if (useZonky) {
            val ep = EmbeddedPostgres.builder().start()
            embeddedPostgres = ep
            val jdbcUrl = ep.getJdbcUrl("postgres", "postgres")
            logger.info("Started Embedded Postgres on {}", jdbcUrl)

            val cfg = AppConfig(
                databaseUrl = jdbcUrl,
                databaseUser = "postgres",
                databasePassword = "postgres",
                jwtSecret = "test_jwt_secret_key_1234567890_test_key",
                webhookSecret = "test_webhook_secret_key_1234567890",
                claimTtlSeconds = 3L
            )
            DatabaseFactory.init(cfg)
            testConfig = cfg
            return cfg
        }

        throw IllegalStateException("Failed to initialize test database")
    }

    fun cleanTables() {
        DatabaseFactory.withTransaction { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE claim_rate_limits, payment_events, idempotency_keys, claims, deals CASCADE;")
            }
        }
    }

    fun close() {
        DatabaseFactory.close()
        embeddedPostgres?.close()
        embeddedPostgres = null
        container?.stop()
        container = null
        testConfig = null
    }
}
