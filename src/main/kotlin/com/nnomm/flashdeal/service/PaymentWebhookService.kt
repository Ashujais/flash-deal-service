package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.ClaimStatus
import com.nnomm.flashdeal.domain.WebhookPaymentEvent
import com.nnomm.flashdeal.domain.parseIsoInstant
import com.nnomm.flashdeal.domain.parseUuid
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.repository.ClaimRepository
import com.nnomm.flashdeal.repository.DealRepository
import com.nnomm.flashdeal.repository.PaymentRepository
import com.nnomm.flashdeal.security.WebhookSignature
import org.slf4j.LoggerFactory
import java.time.Instant

class PaymentWebhookService(
    private val webhookSignature: WebhookSignature,
    private val paymentRepository: PaymentRepository,
    private val claimRepository: ClaimRepository,
    private val dealRepository: DealRepository
) {
    private val logger = LoggerFactory.getLogger(PaymentWebhookService::class.java)

    fun processWebhook(signatureHeader: String?, rawBodyBytes: ByteArray, event: WebhookPaymentEvent) {
        // 1. Verify HMAC Signature
        webhookSignature.verify(signatureHeader, rawBodyBytes)

        // 2. Validate Event Body
        val eventId = event.eventId?.trim()
        if (eventId.isNullOrEmpty()) {
            throw AppException.ValidationFailed("Missing or empty event_id")
        }

        val eventType = event.type?.trim()
        if (eventType != "payment.succeeded" && eventType != "payment.failed") {
            throw AppException.ValidationFailed("Invalid event type: $eventType")
        }

        if (event.claimId.isNullOrBlank()) {
            throw AppException.ValidationFailed("Missing claim_id")
        }
        val claimId = parseUuid(event.claimId, "claim_id")

        val amountPaise = event.amountPaise
        if (amountPaise == null || amountPaise < 0) {
            throw AppException.ValidationFailed("Missing or negative amount_paise")
        }

        if (event.occurredAt.isNullOrBlank()) {
            throw AppException.ValidationFailed("Missing occurred_at")
        }
        val occurredAt = parseIsoInstant(event.occurredAt)

        // 3. Process Event within Database Transaction
        DatabaseFactory.withTransaction { conn ->
            // Check if this event_id was already processed
            val isNew = paymentRepository.recordEventIfNew(
                conn = conn,
                eventId = eventId,
                claimId = claimId,
                eventType = eventType,
                amountPaise = amountPaise,
                occurredAt = occurredAt
            )

            if (!isNew) {
                // Rule: Any event already processed (same event_id): Any status -> 200, no change
                logger.info("Duplicate payment event received: {}", eventId)
                return@withTransaction
            }

            // Lock the claim row exclusively for state transition
            val claim = claimRepository.findByIdForUpdate(conn, claimId)
            if (claim == null) {
                // Decision for Unknown claim_id:
                // We return 404 NOT_FOUND so the provider knows the target resource does not exist in our system.
                // Documented in DESIGN.md.
                throw AppException.NotFound("Claim not found for webhook: $claimId")
            }

            if (eventType == "payment.failed") {
                // Rule: payment.failed, Any status -> No change; customer may pay again until expiry
                logger.info("Payment failed event recorded for claim {}. No status change.", claimId)
                return@withTransaction
            }

            // eventType is payment.succeeded
            val now = Instant.now()
            val currentStatus = claim.status

            when (currentStatus) {
                ClaimStatus.RESERVED.name -> {
                    if (now.isBefore(claim.expiresAt)) {
                        // Payment arrived before expires_at
                        if (amountPaise == claim.amountPaise) {
                            // Rule: payment.succeeded, amount matches, before expires_at -> PAID
                            claimRepository.updateStatus(conn, claimId, ClaimStatus.PAID.name)
                            logger.info("Claim {} transitioned from RESERVED to PAID", claimId)
                        } else {
                            // Rule: payment.succeeded, amount differs, before expires_at -> NEEDS_REVIEW; units stay held
                            claimRepository.updateStatus(conn, claimId, ClaimStatus.NEEDS_REVIEW.name)
                            logger.info("Claim {} transitioned to NEEDS_REVIEW due to amount mismatch (expected {}, got {})",
                                claimId, claim.amountPaise, amountPaise)
                        }
                    } else {
                        // Payment arrived past expires_at:
                        // Rule: payment.succeeded, Past expires_at -> REFUND_PENDING; units are not taken back
                        // Claim was still RESERVED in DB, so return units to stock
                        dealRepository.restoreStock(conn, claim.dealId, claim.quantity)
                        claimRepository.updateStatus(conn, claimId, ClaimStatus.REFUND_PENDING.name)
                        logger.info("Claim {} transitioned from RESERVED to REFUND_PENDING (late payment). Units returned to stock.", claimId)
                    }
                }

                ClaimStatus.EXPIRED.name -> {
                    // Payment arrived after claim had already been expired by background worker
                    // Rule: payment.succeeded, Past expires_at -> REFUND_PENDING; units are not taken back
                    claimRepository.updateStatus(conn, claimId, ClaimStatus.REFUND_PENDING.name)
                    logger.info("Claim {} transitioned from EXPIRED to REFUND_PENDING. Stock remains in deal.", claimId)
                }

                ClaimStatus.PAID.name,
                ClaimStatus.NEEDS_REVIEW.name,
                ClaimStatus.REFUND_PENDING.name -> {
                    // Decision for payment.succeeded, new event_id on resolved claim:
                    // The claim has already reached a terminal/reviewed state.
                    // A secondary payment.succeeded with a new event_id represents an overpayment or duplicate charge.
                    // We record the event in payment_events (above) and leave the claim status unchanged.
                    // We log an alert for operator/manual refund review.
                    // Return 200 OK so the payment provider does not keep retrying.
                    logger.warn("Secondary payment.succeeded received with new event_id {} for claim {} already in status {}",
                        eventId, claimId, currentStatus)
                }
            }
        }
    }
}
