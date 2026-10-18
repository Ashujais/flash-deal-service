package com.nnomm.flashdeal

import com.nnomm.flashdeal.config.AppConfig
import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.error.ErrorDetail
import com.nnomm.flashdeal.error.ErrorResponse
import com.nnomm.flashdeal.repository.*
import com.nnomm.flashdeal.routes.*
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.security.WebhookSignature
import com.nnomm.flashdeal.service.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*

val appMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

fun Application.module(config: AppConfig = AppConfig()) {
    val logger = LoggerFactory.getLogger("Application")

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    install(ContentNegotiation) {
        json(json)
    }

    install(DoubleReceive)

    install(MicrometerMetrics) {
        registry = appMeterRegistry
    }

    // MDC Request-ID interceptor for structured JSON logging
    intercept(ApplicationCallPipeline.Plugins) {
        val reqHeader = call.request.headers["X-Request-ID"]
        val requestId = if (!reqHeader.isNullOrBlank()) reqHeader else UUID.randomUUID().toString()

        MDC.put("requestId", requestId)
        call.response.headers.append("X-Request-ID", requestId)
        try {
            proceed()
        } finally {
            MDC.remove("requestId")
        }
    }

    install(StatusPages) {
        exception<AppException> { call, cause ->
            call.respond(cause.statusCode, ErrorResponse(ErrorDetail(cause.code, cause.message)))
        }

        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("VALIDATION_FAILED", "Malformed JSON or invalid types")))
        }

        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception processing request: {}", cause.message, cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorDetail("INTERNAL", "Internal server error")))
        }
    }

    // Dependencies
    val dealRepo = DealRepository()
    val claimRepo = ClaimRepository()
    val idempotencyRepo = IdempotencyRepository()
    val paymentRepo = PaymentRepository()
    val rateLimiter = RateLimiter(config.rateLimitEnabled)

    val jwtService = JwtService(config.jwtSecret)
    val webhookSignature = WebhookSignature(config.webhookSecret)

    val dealService = DealService(dealRepo)
    val claimService = ClaimService(dealRepo, claimRepo, idempotencyRepo, rateLimiter, config.claimTtlSeconds)
    val paymentWebhookService = PaymentWebhookService(webhookSignature, paymentRepo, claimRepo, dealRepo)

    routing {
        dealRoutes(dealService, claimService, jwtService, json)
        claimRoutes(claimService, jwtService)
        webhookRoutes(paymentWebhookService, json)
        metricsRoutes(appMeterRegistry)
    }
}

fun main() {
    val config = AppConfig()
    val logger = LoggerFactory.getLogger("Main")

    logger.info("Initializing database connection pool and running Flyway migrations...")
    DatabaseFactory.init(config)

    val claimRepo = ClaimRepository()
    val dealRepo = DealRepository()
    val expiryWorker = ExpiryWorker(claimRepo, dealRepo, intervalMillis = 1000L)
    expiryWorker.start()

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down application...")
        expiryWorker.stop()
        server.stop(1000, 2000)
        DatabaseFactory.close()
    })

    server.start(wait = true)
}
