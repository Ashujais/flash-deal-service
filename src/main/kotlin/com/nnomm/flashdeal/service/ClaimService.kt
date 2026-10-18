package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.domain.*
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.error.ErrorDetail
import com.nnomm.flashdeal.error.ErrorResponse
import com.nnomm.flashdeal.repository.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

data class ClaimExecutionResult(
    val statusCode: Int,
    val responseBody: String,
    val isReplayed: Boolean = false
)

class ClaimService(
    private val dealRepository: DealRepository,
    private val claimRepository: ClaimRepository,
    private val idempotencyRepository: IdempotencyRepository,
    private val rateLimiter: RateLimiter,
    private val claimTtlSeconds: Long
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun claimUnits(
        user: AuthUser,
        dealId: UUID,
        idempotencyKey: String?,
        request: ClaimRequest
    ): ClaimExecutionResult {
        // Role check: Only customers claim
        if (user.role != UserRole.CUSTOMER) {
            throw AppException.Forbidden("Only customers can claim deals")
        }

        // Header validation: Missing or malformed -> 400 IDEMPOTENCY_KEY_INVALID
        idempotencyRepository.validateKeyFormat(idempotencyKey)
        val key = idempotencyKey!!

        // Body validation: quantity 1-3
        val quantity = request.quantity
        if (quantity == null || quantity < 1 || quantity > 3) {
            throw AppException.ValidationFailed("Quantity must be between 1 and 3")
        }

        // Deal existence check (404 is rejected before idempotency store)
        val dealInitial = dealRepository.findById(dealId)
            ?: throw AppException.NotFound("Deal not found")

        // Rate limiting check across instances (Stretch Goal 2)
        rateLimiter.checkAndRecord(user.userId, key)

        // Atomic Idempotency check and claim execution
        return DatabaseFactory.withTransaction { conn ->
            val now = Instant.now()
            val outcome = idempotencyRepository.acquireOrCheck(
                conn = conn,
                customerId = user.userId,
                key = key,
                dealId = dealId,
                quantity = quantity,
                now = now
            )

            if (outcome is IdempotencyOutcome.Replay) {
                return@withTransaction ClaimExecutionResult(
                    statusCode = outcome.statusCode,
                    responseBody = outcome.responseBody,
                    isReplayed = true
                )
            }

            // New Execution: Lock deal row exclusively
            val deal = dealRepository.findByIdForUpdate(conn, dealId)
                ?: throw AppException.NotFound("Deal not found")

            // Check if deal is live: starts_at <= now < ends_at
            if (now.isBefore(deal.startsAt) || !now.isBefore(deal.endsAt)) {
                val err = ErrorResponse(ErrorDetail("DEAL_NOT_LIVE", "Deal is not currently live"))
                val errJson = json.encodeToString(err)
                idempotencyRepository.complete(conn, user.userId, key, 409, errJson)
                return@withTransaction ClaimExecutionResult(409, errJson, isReplayed = false)
            }

            // Check if customer already holds an active claim on this deal
            if (claimRepository.hasActiveClaim(conn, dealId, user.userId, now)) {
                val err = ErrorResponse(ErrorDetail("ALREADY_CLAIMED", "Customer already holds an active claim on this deal"))
                val errJson = json.encodeToString(err)
                idempotencyRepository.complete(conn, user.userId, key, 409, errJson)
                return@withTransaction ClaimExecutionResult(409, errJson, isReplayed = false)
            }

            // Check stock
            if (deal.stockAvailable < quantity) {
                val err = ErrorResponse(ErrorDetail("SOLD_OUT", "Not enough units left for this quantity"))
                val errJson = json.encodeToString(err)
                idempotencyRepository.complete(conn, user.userId, key, 409, errJson)
                return@withTransaction ClaimExecutionResult(409, errJson, isReplayed = false)
            }

            // Deduct stock
            val updated = dealRepository.deductStock(conn, dealId, quantity)
            if (updated <= 0) {
                val err = ErrorResponse(ErrorDetail("SOLD_OUT", "Not enough units left for this quantity"))
                val errJson = json.encodeToString(err)
                idempotencyRepository.complete(conn, user.userId, key, 409, errJson)
                return@withTransaction ClaimExecutionResult(409, errJson, isReplayed = false)
            }

            val amountPaise = quantity * deal.pricePaise
            val expiresAt = now.plus(claimTtlSeconds, ChronoUnit.SECONDS)
            val claimId = UUID.randomUUID()

            val claimRecord = claimRepository.insert(
                conn = conn,
                id = claimId,
                dealId = dealId,
                customerId = user.userId,
                quantity = quantity,
                amountPaise = amountPaise,
                status = ClaimStatus.RESERVED.name,
                expiresAt = expiresAt
            )

            val claimResponse = claimRecord.toResponse(now)
            val responseJson = json.encodeToString(claimResponse)

            idempotencyRepository.complete(conn, user.userId, key, 201, responseJson)

            ClaimExecutionResult(
                statusCode = 201,
                responseBody = responseJson,
                isReplayed = false
            )
        }
    }

    fun getClaim(user: AuthUser, claimId: UUID): ClaimResponse {
        val claim = claimRepository.findById(claimId)
            ?: throw AppException.NotFound("Claim not found")

        val deal = dealRepository.findById(claim.dealId)
            ?: throw AppException.NotFound("Deal not found")

        // Visibility: a claim is visible only to its customer and to the admin of the deal's restaurant. Anyone else gets 404, not 403.
        val isCustomer = user.role == UserRole.CUSTOMER && user.userId == claim.customerId
        val isDealAdmin = user.role == UserRole.RESTAURANT_ADMIN && user.restaurantId == deal.restaurantId

        if (!isCustomer && !isDealAdmin) {
            throw AppException.NotFound("Claim not found")
        }

        return claim.toResponse()
    }

    /**
     * Stretch Goal 1: Cursor pagination
     */
    fun listMyClaims(user: AuthUser, cursor: String?, limit: Int = 20): PaginatedClaimsResponse {
        if (user.role != UserRole.CUSTOMER) {
            throw AppException.Forbidden("Only customers can view their claims")
        }

        var cursorCreatedAt: Instant? = null
        var cursorId: UUID? = null

        if (!cursor.isNullOrBlank()) {
            try {
                val decoded = String(Base64.getUrlDecoder().decode(cursor))
                val parts = decoded.split(":")
                cursorCreatedAt = Instant.ofEpochMilli(parts[0].toLong())
                cursorId = UUID.fromString(parts[1])
            } catch (e: Exception) {
                throw AppException.ValidationFailed("Invalid pagination cursor")
            }
        }

        val clampedLimit = limit.coerceIn(1, 100)
        // Fetch 1 extra to see if there is a next page
        val records = claimRepository.listCustomerClaims(user.userId, cursorCreatedAt, cursorId, clampedLimit + 1)

        val hasNext = records.size > clampedLimit
        val pageItems = if (hasNext) records.take(clampedLimit) else records

        val nextCursor = if (hasNext && pageItems.isNotEmpty()) {
            val last = pageItems.last()
            val raw = "${last.createdAt.toEpochMilli()}:${last.id}"
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        } else {
            null
        }

        val now = Instant.now()
        return PaginatedClaimsResponse(
            claims = pageItems.map { it.toResponse(now) },
            nextCursor = nextCursor
        )
    }

    /**
     * Stretch Goal 4: Claims on a deal for restaurant admin
     */
    fun listDealClaims(user: AuthUser, dealId: UUID, statusFilter: String?): List<ClaimResponse> {
        val deal = dealRepository.findById(dealId)
            ?: throw AppException.NotFound("Deal not found")

        if (user.role != UserRole.RESTAURANT_ADMIN || user.restaurantId != deal.restaurantId) {
            throw AppException.NotFound("Deal not found")
        }

        val records = claimRepository.listDealClaims(dealId, statusFilter)
        val now = Instant.now()
        return records.map { it.toResponse(now) }
    }
}
