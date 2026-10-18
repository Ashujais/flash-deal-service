package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.repository.ClaimRepository
import com.nnomm.flashdeal.repository.DealRepository
import com.nnomm.flashdeal.security.WebhookSignature
import com.nnomm.flashdeal.service.ExpiryWorker
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentExpiryRaceTests {

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
     * Requirement 5: A payment and expiry racing on the same claim leave the stock invariant intact.
     */
    @Test
    fun testPaymentAndExpiryRaceLeavesStockInvariantIntact() = testApplication {
        val config = TestDatabase.testConfig!!.copy(claimTtlSeconds = 1L)
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerId = UUID.randomUUID()
        val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)

        val now = Instant.now()
        val createDealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Race Deal",
                    pricePaise = 1000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(createDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Claim 2 units with TTL 1 second
        val claimResp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "race-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 2}""")
        }
        assertEquals(HttpStatusCode.Created, claimResp.status)
        val claimObj = json.parseToJsonElement(claimResp.body<String>()).jsonObject
        val claimId = claimObj["id"]!!.jsonPrimitive.content

        // Wait right around expiry boundary (approx 1000ms)
        Thread.sleep(980)

        val claimRepo = ClaimRepository()
        val dealRepo = DealRepository()
        val expiryWorker = ExpiryWorker(claimRepo, dealRepo)
        val webhookSig = WebhookSignature(config.webhookSecret)

        val payload = """
            {
                "event_id": "evt_race_${UUID.randomUUID()}",
                "type": "payment.succeeded",
                "claim_id": "$claimId",
                "amount_paise": 2000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()
        val rawBytes = payload.toByteArray()
        val sigHeader = webhookSig.generateHeader(System.currentTimeMillis() / 1000, rawBytes)

        // Run payment webhook and expiry job concurrently on the exact same claim!
        val raceJobs = listOf(
            CoroutineScope(Dispatchers.IO).async {
                client.post("/v1/webhooks/payments") {
                    header("X-Payment-Signature", sigHeader)
                    contentType(ContentType.Application.Json)
                    setBody(rawBytes)
                }
            },
            CoroutineScope(Dispatchers.IO).async {
                expiryWorker.processExpiredClaims()
            }
        )

        runBlocking {
            raceJobs.awaitAll()
        }

        // Verify final status of claim: must be either PAID or REFUND_PENDING!
        val getClaimResp = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        val finalClaim = json.parseToJsonElement(getClaimResp.body<String>()).jsonObject
        val finalStatus = finalClaim["status"]!!.jsonPrimitive.content
        assertTrue(finalStatus == "PAID" || finalStatus == "REFUND_PENDING", "Status must be either PAID or REFUND_PENDING, got $finalStatus")

        // Verify Deal Stock Invariant
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val dealJson = json.parseToJsonElement(getDealResp.body<String>()).jsonObject
        val stockAvailable = dealJson["stock_available"]!!.jsonPrimitive.content.toInt()

        if (finalStatus == "PAID") {
            // Units stayed sold: 10 - 2 = 8 available
            assertEquals(8, stockAvailable, "If PAID, units stay sold (8 available)")
        } else {
            // finalStatus == "REFUND_PENDING": units returned: 10 available
            assertEquals(10, stockAvailable, "If REFUND_PENDING, units returned to stock (10 available)")
        }
    }

    /**
     * Requirement: Expiry worker restores units within 10 seconds
     */
    @Test
    fun testExpiryWorkerRestoresStock() = testApplication {
        val config = TestDatabase.testConfig!!.copy(claimTtlSeconds = 1L)
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
                    title = "Expiry Deal",
                    pricePaise = 1000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(createDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val claimResp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "expire-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 3}""")
        }
        val claimId = json.parseToJsonElement(claimResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Wait 1.2s for TTL to expire
        Thread.sleep(1200)

        // Requirement: From expires_at on, the API never shows it as RESERVED
        val getClaimBeforeWorker = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        val claimStatusBeforeWorker = json.parseToJsonElement(getClaimBeforeWorker.body<String>()).jsonObject["status"]!!.jsonPrimitive.content
        assertEquals("EXPIRED", claimStatusBeforeWorker, "API must never show claim as RESERVED once expires_at has passed")

        // Run worker
        val expiryWorker = ExpiryWorker(ClaimRepository(), DealRepository())
        expiryWorker.processExpiredClaims()

        // Check stock restored
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val stockAvailable = json.parseToJsonElement(getDealResp.body<String>()).jsonObject["stock_available"]!!.jsonPrimitive.content.toInt()
        assertEquals(10, stockAvailable, "Units must be restored to 10")
    }

    /**
     * Requirement: Mismatched payment amount before expiry transitions to NEEDS_REVIEW (units stay held)
     */
    @Test
    fun testMismatchedPaymentTransitionsToNeedsReview() = testApplication {
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
                    title = "Mismatch Deal",
                    pricePaise = 2000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(createDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Claim 2 units -> amount = 4000
        val claimResp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "mismatch-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 2}""")
        }
        val claimId = json.parseToJsonElement(claimResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Send payment with 3500 instead of 4000
        val payload = """
            {
                "event_id": "evt_mismatch_${UUID.randomUUID()}",
                "type": "payment.succeeded",
                "claim_id": "$claimId",
                "amount_paise": 3500,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent()
        val rawBytes = payload.toByteArray()
        val sig = WebhookSignature(config.webhookSecret).generateHeader(System.currentTimeMillis() / 1000, rawBytes)

        val webhookResp = client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", sig)
            contentType(ContentType.Application.Json)
            setBody(rawBytes)
        }
        assertEquals(HttpStatusCode.OK, webhookResp.status)

        // Claim must be in NEEDS_REVIEW
        val getClaimResp = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        val status = json.parseToJsonElement(getClaimResp.body<String>()).jsonObject["status"]!!.jsonPrimitive.content
        assertEquals("NEEDS_REVIEW", status)

        // Units must stay held: 10 - 2 = 8 available
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val stockAvailable = json.parseToJsonElement(getDealResp.body<String>()).jsonObject["stock_available"]!!.jsonPrimitive.content.toInt()
        assertEquals(8, stockAvailable, "Units stay held in NEEDS_REVIEW")
    }
}
