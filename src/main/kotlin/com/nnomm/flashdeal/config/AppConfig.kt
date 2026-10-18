package com.nnomm.flashdeal.config

data class AppConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val databaseUrl: String = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nnomm_db",
    val databaseUser: String = System.getenv("DATABASE_USER") ?: "nnomm_user",
    val databasePassword: String = System.getenv("DATABASE_PASSWORD") ?: "nnomm_pass",
    val jwtSecret: String = System.getenv("JWT_SECRET") ?: "default_jwt_secret_must_be_overridden_in_prod",
    val webhookSecret: String = System.getenv("WEBHOOK_SECRET") ?: "default_webhook_secret_must_be_overridden",
    val claimTtlSeconds: Long = System.getenv("CLAIM_TTL_SECONDS")?.toLongOrNull() ?: 600L,
    val rateLimitEnabled: Boolean = System.getenv("RATE_LIMIT_ENABLED")?.toBooleanStrictOrNull() ?: true
)
