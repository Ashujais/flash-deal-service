package com.nnomm.flashdeal.error

import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

sealed class AppException(
    val statusCode: HttpStatusCode,
    val code: String,
    override val message: String
) : RuntimeException(message) {

    class ValidationFailed(message: String = "Validation failed") :
        AppException(HttpStatusCode.BadRequest, "VALIDATION_FAILED", message)

    class IdempotencyKeyInvalid(message: String = "Idempotency-Key header is missing or malformed") :
        AppException(HttpStatusCode.BadRequest, "IDEMPOTENCY_KEY_INVALID", message)

    class Unauthenticated(message: String = "Authentication failed") :
        AppException(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", message)

    class Forbidden(message: String = "Action forbidden for this role") :
        AppException(HttpStatusCode.Forbidden, "FORBIDDEN", message)

    class NotFound(message: String = "Resource not found") :
        AppException(HttpStatusCode.NotFound, "NOT_FOUND", message)

    class SoldOut(message: String = "Deal is sold out or insufficient stock") :
        AppException(HttpStatusCode.Conflict, "SOLD_OUT", message)

    class AlreadyClaimed(message: String = "Customer already holds an active claim for this deal") :
        AppException(HttpStatusCode.Conflict, "ALREADY_CLAIMED", message)

    class DealNotLive(message: String = "Deal is not currently live") :
        AppException(HttpStatusCode.Conflict, "DEAL_NOT_LIVE", message)

    class RequestInProgress(message: String = "A request with this idempotency key is already in progress") :
        AppException(HttpStatusCode.Conflict, "REQUEST_IN_PROGRESS", message)

    class PayloadTooLarge(message: String = "Payload exceeds maximum allowed size of 16 KB") :
        AppException(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", message)

    class IdempotencyKeyReused(message: String = "Idempotency key reused with different request parameters") :
        AppException(HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED", message)

    class TooManyRequests(message: String = "Rate limit of 10 claim requests per minute exceeded") :
        AppException(HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", message)

    class Internal(message: String = "An internal server error occurred") :
        AppException(HttpStatusCode.InternalServerError, "INTERNAL", message)
}
