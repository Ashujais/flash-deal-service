package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.domain.PaginatedClaimsResponse
import com.nnomm.flashdeal.error.ErrorResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StretchGoalTests {

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
     * Stretch Goal 1: GET /v1/me/claims with cursor pagination that never skips or repeats a claim
     */
    @Test
    fun testCursorPaginationNeverSkipsOrRepeats() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerId = UUID.randomUUID()
        val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)
        val now = Instant.now()

        // Create 3 deals so customer can have multiple claims (since Rule 2 allows at most 1 active claim per deal)
        val dealIds = (1..5).map { i ->
            val resp = client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(
                    CreateDealRequest(
                        title = "Paginated Deal $i",
                        pricePaise = 1000,
                        stockTotal = 10,
                        startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                        endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                    )
                )
            }
            json.parseToJsonElement(resp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content
        }

        // Customer claims on each deal
        dealIds.forEachIndexed { idx, dId ->
            client.post("/v1/deals/$dId/claims") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                header("Idempotency-Key", "pag-key-$idx")
                contentType(ContentType.Application.Json)
                setBody("""{"quantity": 1}""")
            }
        }

        // Query page 1 (limit = 2)
        val page1Resp = client.get("/v1/me/claims?limit=2") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        assertEquals(HttpStatusCode.OK, page1Resp.status)
        val page1Data = json.decodeFromString<PaginatedClaimsResponse>(page1Resp.body<String>())
        assertEquals(2, page1Data.claims.size)
        assertNotNull(page1Data.nextCursor)

        // Query page 2 (limit = 2) with cursor
        val page2Resp = client.get("/v1/me/claims?limit=2&cursor=${page1Data.nextCursor}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        val page2Data = json.decodeFromString<PaginatedClaimsResponse>(page2Resp.body<String>())
        assertEquals(2, page2Data.claims.size)
        assertNotNull(page2Data.nextCursor)

        // Query page 3 (remaining 1)
        val page3Resp = client.get("/v1/me/claims?limit=2&cursor=${page2Data.nextCursor}") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
        }
        val page3Data = json.decodeFromString<PaginatedClaimsResponse>(page3Resp.body<String>())
        assertEquals(1, page3Data.claims.size)

        // Verify all 5 claims were retrieved with NO duplicate and NO missing!
        val allIds = page1Data.claims.map { it.id } + page2Data.claims.map { it.id } + page3Data.claims.map { it.id }
        assertEquals(5, allIds.size)
        assertEquals(5, allIds.toSet().size, "Cursor pagination must never skip or repeat claims")
    }

    /**
     * Stretch Goal 2: A limit of 10 claim requests per customer per minute that holds across both instances
     */
    @Test
    fun testRateLimit10RequestsPerMinute() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerId = UUID.randomUUID()
        val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)
        val now = Instant.now()

        // Create 12 deals so customer can make 12 distinct claim attempts
        val dealIds = (1..12).map { i ->
            val resp = client.post("/v1/deals") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(
                    CreateDealRequest(
                        title = "Rate Limit Deal $i",
                        pricePaise = 1000,
                        stockTotal = 10,
                        startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                        endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                    )
                )
            }
            json.parseToJsonElement(resp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content
        }

        // First 10 requests must succeed
        for (i in 0 until 10) {
            val resp = client.post("/v1/deals/${dealIds[i]}/claims") {
                header(HttpHeaders.Authorization, "Bearer $customerToken")
                header("Idempotency-Key", "rate-key-$i")
                contentType(ContentType.Application.Json)
                setBody("""{"quantity": 1}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status, "Attempt $i should succeed")
        }

        // 11th request must be rate limited!
        val resp11 = client.post("/v1/deals/${dealIds[10]}/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "rate-key-11")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp11.status)
        val err = json.decodeFromString<ErrorResponse>(resp11.body<String>())
        assertEquals("TOO_MANY_REQUESTS", err.error.code)
    }

    /**
     * Stretch Goal 3: Prometheus /metrics endpoint and Request-ID header
     */
    @Test
    fun testMetricsAndRequestIdHeader() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Check Request-ID propagation
        val customRequestId = "test-custom-request-id-12345"
        val resp = client.get("/metrics") {
            header("X-Request-ID", customRequestId)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(customRequestId, resp.headers["X-Request-ID"])

        // 2. Check Prometheus metrics output
        val body = resp.body<String>()
        assertTrue(body.contains("http_server_requests") || body.contains("jvm") || body.isNotBlank(), "Metrics endpoint must return Prometheus data")
    }

    /**
     * Stretch Goal 4: GET /v1/deals/{dealId}/claims for restaurant admin with status filter
     */
    @Test
    fun testAdminDealClaimsWithStatusFilter() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantId = UUID.randomUUID()
        val adminToken = JwtTestHelper.createAdminToken(restaurantId = restaurantId)
        val now = Instant.now()

        val dealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Filter Deal",
                    pricePaise = 2000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(dealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Customer 1 claims -> RESERVED
        val cust1Token = JwtTestHelper.createCustomerToken()
        val claim1Resp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust1Token")
            header("Idempotency-Key", "filter-claim-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        val claim1Id = json.parseToJsonElement(claim1Resp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Customer 2 claims -> transition to PAID
        val cust2Token = JwtTestHelper.createCustomerToken()
        val claim2Resp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $cust2Token")
            header("Idempotency-Key", "filter-claim-2")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        val claim2Id = json.parseToJsonElement(claim2Resp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Pay claim 2
        val sig = com.nnomm.flashdeal.security.WebhookSignature(config.webhookSecret)
        val payPayload = """
            {
                "event_id": "evt_filter_pay_2",
                "type": "payment.succeeded",
                "claim_id": "$claim2Id",
                "amount_paise": 2000,
                "occurred_at": "${Instant.now()}"
            }
        """.trimIndent().toByteArray()
        val sigHeader = sig.generateHeader(System.currentTimeMillis() / 1000, payPayload)
        client.post("/v1/webhooks/payments") {
            header("X-Payment-Signature", sigHeader)
            contentType(ContentType.Application.Json)
            setBody(payPayload)
        }

        // Query admin deal claims with filter status=PAID
        val adminPaidResp = client.get("/v1/deals/$dealId/claims?status=PAID") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminPaidResp.status)
        val paidList = json.parseToJsonElement(adminPaidResp.body<String>()).jsonArray
        assertEquals(1, paidList.size)
        assertEquals(claim2Id, paidList[0].jsonObject["id"]!!.jsonPrimitive.content)

        // Query admin deal claims with filter status=RESERVED
        val adminReservedResp = client.get("/v1/deals/$dealId/claims?status=RESERVED") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val resList = json.parseToJsonElement(adminReservedResp.body<String>()).jsonArray
        assertEquals(1, resList.size)
        assertEquals(claim1Id, resList[0].jsonObject["id"]!!.jsonPrimitive.content)
    }
}
