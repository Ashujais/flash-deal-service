package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.ClaimRequest
import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.parseUuid
import com.nnomm.flashdeal.error.AppException
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.service.ClaimService
import com.nnomm.flashdeal.service.DealService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun ApplicationCall.authenticateUser(jwtService: JwtService) =
    jwtService.verifyToken(
        request.header(HttpHeaders.Authorization)?.let {
            if (it.startsWith("Bearer ")) it.substring(7).trim() else null
        } ?: throw AppException.Unauthenticated("Missing or invalid Authorization header")
    )

suspend fun ApplicationCall.receiveSafeBytes(maxBytes: Long = 16384): ByteArray {
    val length = request.contentLength()
    if (length != null && length > maxBytes) {
        throw AppException.PayloadTooLarge("The body is over 16 KB")
    }
    val bytes = receive<ByteArray>()
    if (bytes.size > maxBytes) {
        throw AppException.PayloadTooLarge("The body is over 16 KB")
    }
    return bytes
}

fun Route.dealRoutes(
    dealService: DealService,
    claimService: ClaimService,
    jwtService: JwtService,
    json: Json
) {
    route("/v1/deals") {
        // POST /v1/deals - Restaurant admin creates a deal
        post {
            val user = call.authenticateUser(jwtService)
            val bytes = call.receiveSafeBytes()
            val req = try {
                json.decodeFromString<CreateDealRequest>(bytes.decodeToString())
            } catch (e: Exception) {
                throw AppException.ValidationFailed("Malformed JSON in request body: ${e.message}")
            }

            val deal = dealService.createDeal(user, req)
            call.respond(HttpStatusCode.Created, deal)
        }

        // GET /v1/deals/{dealId} - Any signed-in user
        get("/{dealId}") {
            call.authenticateUser(jwtService)
            val dealId = parseUuid(call.parameters["dealId"] ?: "", "dealId")
            val deal = dealService.getDeal(dealId)
            call.respond(HttpStatusCode.OK, deal)
        }

        // POST /v1/deals/{dealId}/claims - Customer claims units
        post("/{dealId}/claims") {
            val user = call.authenticateUser(jwtService)
            val dealId = parseUuid(call.parameters["dealId"] ?: "", "dealId")
            val idempotencyKey = call.request.header("Idempotency-Key")

            val bytes = call.receiveSafeBytes()
            val req = try {
                json.decodeFromString<ClaimRequest>(bytes.decodeToString())
            } catch (e: Exception) {
                throw AppException.ValidationFailed("Malformed JSON in request body: ${e.message}")
            }

            val result = claimService.claimUnits(user, dealId, idempotencyKey, req)

            if (result.isReplayed) {
                call.response.header("Idempotent-Replayed", "true")
            }
            call.respondText(
                text = result.responseBody,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.fromValue(result.statusCode)
            )
        }

        // GET /v1/deals/{dealId}/claims - Restaurant admin lists claims on deal (Stretch Goal 4)
        get("/{dealId}/claims") {
            val user = call.authenticateUser(jwtService)
            val dealId = parseUuid(call.parameters["dealId"] ?: "", "dealId")
            val statusFilter = call.request.queryParameters["status"]

            val claims = claimService.listDealClaims(user, dealId, statusFilter)
            call.respond(HttpStatusCode.OK, claims)
        }
    }
}
