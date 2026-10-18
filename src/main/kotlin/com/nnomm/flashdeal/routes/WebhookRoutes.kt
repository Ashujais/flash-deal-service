package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.WebhookPaymentEvent
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.service.PaymentWebhookService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun Route.webhookRoutes(
    paymentWebhookService: PaymentWebhookService,
    json: Json
) {
    post("/v1/webhooks/payments") {
        val signatureHeader = call.request.headers["X-Payment-Signature"]
        val rawBytes = call.receiveSafeBytes()

        val event = try {
            json.decodeFromString<WebhookPaymentEvent>(rawBytes.decodeToString())
        } catch (e: Exception) {
            throw AppException.ValidationFailed("Malformed JSON in webhook payload: ${e.message}")
        }

        paymentWebhookService.processWebhook(signatureHeader, rawBytes, event)

        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}
