package com.nnomm.flashdeal.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

fun Instant.toIsoUtcString(): String = DateTimeFormatter.ISO_INSTANT.format(this)

fun parseIsoInstant(str: String): Instant =
    try {
        Instant.parse(str)
    } catch (e: Exception) {
        throw com.nnomm.flashdeal.error.AppException.ValidationFailed("Invalid ISO 8601 timestamp: $str")
    }

fun parseUuid(str: String, fieldName: String = "ID"): UUID =
    try {
        UUID.fromString(str)
    } catch (e: Exception) {
        throw com.nnomm.flashdeal.error.AppException.ValidationFailed("Invalid UUID for $fieldName: $str")
    }

enum class ClaimStatus {
    RESERVED,
    PAID,
    EXPIRED,
    NEEDS_REVIEW,
    REFUND_PENDING
}

enum class UserRole {
    @SerialName("customer")
    CUSTOMER,
    @SerialName("restaurant_admin")
    RESTAURANT_ADMIN;

    companion object {
        fun fromString(str: String): UserRole = when (str.lowercase()) {
            "customer" -> CUSTOMER
            "restaurant_admin" -> RESTAURANT_ADMIN
            else -> throw com.nnomm.flashdeal.error.AppException.Unauthenticated("Invalid role: $str")
        }
    }
}

data class AuthUser(
    val userId: UUID,
    val role: UserRole,
    val restaurantId: UUID? = null
)

@Serializable
data class CreateDealRequest(
    val title: String? = null,
    @SerialName("price_paise")
    val pricePaise: Long? = null,
    @SerialName("stock_total")
    val stockTotal: Int? = null,
    @SerialName("starts_at")
    val startsAt: String? = null,
    @SerialName("ends_at")
    val endsAt: String? = null
)

@Serializable
data class DealResponse(
    val id: String,
    @SerialName("restaurant_id")
    val restaurantId: String,
    val title: String,
    @SerialName("price_paise")
    val pricePaise: Long,
    @SerialName("stock_total")
    val stockTotal: Int,
    @SerialName("stock_available")
    val stockAvailable: Int,
    @SerialName("starts_at")
    val startsAt: String,
    @SerialName("ends_at")
    val endsAt: String
)

@Serializable
data class ClaimRequest(
    val quantity: Int? = null
)

@Serializable
data class ClaimResponse(
    val id: String,
    @SerialName("deal_id")
    val dealId: String,
    @SerialName("customer_id")
    val customerId: String,
    val quantity: Int,
    @SerialName("amount_paise")
    val amountPaise: Long,
    val status: String,
    @SerialName("expires_at")
    val expiresAt: String
)

@Serializable
data class WebhookPaymentEvent(
    @SerialName("event_id")
    val eventId: String? = null,
    val type: String? = null,
    @SerialName("claim_id")
    val claimId: String? = null,
    @SerialName("amount_paise")
    val amountPaise: Long? = null,
    @SerialName("occurred_at")
    val occurredAt: String? = null
)

@Serializable
data class PaginatedClaimsResponse(
    val claims: List<ClaimResponse>,
    @SerialName("next_cursor")
    val nextCursor: String? = null
)
