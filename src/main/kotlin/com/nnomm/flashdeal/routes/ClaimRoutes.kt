package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.parseUuid
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.service.ClaimService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.claimRoutes(
    claimService: ClaimService,
    jwtService: JwtService
) {
    // GET /v1/claims/{claimId}
    get("/v1/claims/{claimId}") {
        val user = call.authenticateUser(jwtService)
        val claimId = parseUuid(call.parameters["claimId"] ?: "", "claimId")
        val claim = claimService.getClaim(user, claimId)
        call.respond(HttpStatusCode.OK, claim)
    }

    // GET /v1/me/claims - Cursor pagination (Stretch Goal 1)
    get("/v1/me/claims") {
        val user = call.authenticateUser(jwtService)
        val cursor = call.request.queryParameters["cursor"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val response = claimService.listMyClaims(user, cursor, limit)
        call.respond(HttpStatusCode.OK, response)
    }
}
