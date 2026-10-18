package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.error.ErrorResponse
import com.nnomm.flashdeal.security.WebhookSignature
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookTests {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setup() {
        TestDatabase.start()
    }

    @BeforeEach
    fun clean() {
        TestDatabase.cleanTables()
    }

    @AfterAll
    fun teardown() {
        TestDatabase.close()
    }

    /**
     * Requirement 4: Bad signatures, stale timestamps, duplicate events and out-of-order events are all handled.
     */
    @Test
    fun testWebhookSignatureAndTimestampValidation() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val rawBody = """{"event_id":"evt_1","type":"payment.succeeded","claim_id":"${UUID.randomUUID()}","amount_paise":1000,"occurred_at":"${Instant.now()}"}""".toByteArray()

        // 1. Missing signature header
        val respMissing = client.post("/v1/webhooks/payments") {
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, respMissing.status)
        val errMissing = json.decodeFromString<ErrorResponse>(respMissing.body<String>())
        assertEquals("UNAUTHENTICATED", errMissing.error.code)

        // 2. Bad/tampered signature
        val respBadSig = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", "t=${System.currentTimeMillis() / 1000},v1=deadbeef1234")
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, respBadSig.status)

        // 3. Stale timestamp (> 300 seconds in the past)
        val staleTime = (System.currentTimeMillis() / 1000) - 301
        val staleSig = WebhookSignature(config.webhookSecret).generateHeader(staleTime, rawBody)
        val respStale = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", staleSig)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, respStale.status)

        // 4. Future timestamp (> 300 seconds in the future)
        val futureTime = (System.currentTimeMillis() / 1000) + 305
        val futureSig = WebhookSignature(config.webhookSecret).generateHeader(futureTime, rawBody)
        val respFuture = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", futureSig)
            contentType(ContentType.Application.Json)
            setBody(rawBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, respFuture.status)
    }

    @Test
    fun testDuplicateAndOutOfOrderEvents() = testApplication {
        val config = TestDatabase.testConfig!!.copy(claimTtlSeconds = 600L)
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerToken = JwtTestHelper.createCustomerToken()

        val now = Instant.now()
        val createDealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Webhook Deal",
                    pricePaise = 5000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(createDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Claim 1 unit -> amount = 5000
        val claimResp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "webhook-claim-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        val claimId = json.parseToJsonElement(claimResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val webhookSig = WebhookSignature(config.webhookSecret)

        // Out of order: First a payment.failed arrives
        val failedBody = """
            {
                "event_id": "evt_fail_1",
                "type": "payment.failed",
                "claim_id": "$claimId",
                "amount_paise": 5000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent().toByteArray()
        val failedSig = webhookSig.generateHeader(System.currentTimeMillis() / 1000, failedBody)

        val failResp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", failedSig)
            contentType(ContentType.Application.Json)
            setBody(failedBody)
        }
        assertEquals(HttpStatusCode.OK, failResp.status)

        // Verify claim is still RESERVED (customer may retry payment)
        val claimCheck1 = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        assertEquals("RESERVED", json.parseToJsonElement(claimCheck1.body<String>()).jsonObject["status"]!!.jsonPrimitive.content)

        // Now payment.succeeded arrives
        val successBody = """
            {
                "event_id": "evt_succ_1",
                "type": "payment.succeeded",
                "claim_id": "$claimId",
                "amount_paise": 5000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent().toByteArray()
        val successSig = webhookSig.generateHeader(System.currentTimeMillis() / 1000, successBody)

        val succResp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", successSig)
            contentType(ContentType.Application.Json)
            setBody(successBody)
        }
        assertEquals(HttpStatusCode.OK, succResp.status)

        // Claim must now be PAID
        val claimCheck2 = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        assertEquals("PAID", json.parseToJsonElement(claimCheck2.body<String>()).jsonObject["status"]!!.jsonPrimitive.content)

        // Duplicate payment event: Send the exact same success event again
        val dupResp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", successSig)
            contentType(ContentType.Application.Json)
            setBody(successBody)
        }
        // Must return 200, no change
        assertEquals(HttpStatusCode.OK, dupResp.status)
        val claimCheck3 = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        assertEquals("PAID", json.parseToJsonElement(claimCheck3.body<String>()).jsonObject["status"]!!.jsonPrimitive.content)

        // New event_id for already PAID claim
        val newEventForPaid = """
            {
                "event_id": "evt_succ_duplicate_new_id",
                "type": "payment.succeeded",
                "claim_id": "$claimId",
                "amount_paise": 5000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent().toByteArray()
        val newEventSig = webhookSig.generateHeader(System.currentTimeMillis() / 1000, newEventForPaid)

        val newEventResp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", newEventSig)
            contentType(ContentType.Application.Json)
            setBody(newEventForPaid)
        }
        assertEquals(HttpStatusCode.OK, newEventResp.status)
    }

    @Test
    fun testUnknownClaimIdReturns404() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val unknownClaimId = UUID.randomUUID()
        val payload = """
            {
                "event_id": "evt_unknown_1",
                "type": "payment.succeeded",
                "claim_id": "$unknownClaimId",
                "amount_paise": 5000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent().toByteArray()
        val sig = WebhookSignature(config.webhookSecret).generateHeader(System.currentTimeMillis() / 1000, payload)

        val resp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", sig)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        val err = json.decodeFromString<ErrorResponse>(resp.body<String>())
        assertEquals("NOT_FOUND", err.error.code)
    }
}
