package com.nnomm.flashdeal.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

fun Route.metricsRoutes(meterRegistry: PrometheusMeterRegistry) {
    get("/metrics") {
        call.respondText(
            text = meterRegistry.scrape(),
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            status = HttpStatusCode.OK
        )
    }
}
