package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.error.ErrorResponse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyTests {

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
     * Requirement 1: 200 or more concurrent claims never oversell a 50-unit deal.
     */
    @Test
    fun test200ConcurrentClaimsNeverOversell50UnitDeal() = testApplication {
        val config = TestDatabase.testConfig!!.copy(rateLimitEnabled = false)
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantId = UUID.randomUUID()
        val adminToken = JwtTestHelper.createAdminToken(restaurantId = restaurantId)

        val now = Instant.now()
        val startsAt = now.minus(5, ChronoUnit.MINUTES).toString()
        val endsAt = now.plus(2, ChronoUnit.HOURS).toString()

        // Create deal with 50 units
        val createResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Flash Biryani 50",
                    pricePaise = 5000,
                    stockTotal = 50,
                    startsAt = startsAt,
                    endsAt = endsAt
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val dealJson = json.parseToJsonElement(createResp.body<String>()).jsonObject
        val dealId = dealJson["id"]!!.jsonPrimitive.content

        // 250 distinct customers attempt to claim 1 unit concurrently
        val concurrentRequests = 250
        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val otherStatusCount = AtomicInteger(0)

        val dispatcher = Dispatchers.IO
        val jobs = (1..concurrentRequests).map { index ->
            CoroutineScope(dispatcher).async {
                val customerId = UUID.randomUUID()
                val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)
                val key = "claim-key-$index-${UUID.randomUUID()}"

                val resp = client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $customerToken")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity": 1}""")
                }

                when (resp.status) {
                    HttpStatusCode.Created -> successCount.incrementAndGet()
                    HttpStatusCode.Conflict -> {
                        val body = resp.body<String>()
                        val err = json.decodeFromString<ErrorResponse>(body)
                        if (err.error.code == "SOLD_OUT") {
                            soldOutCount.incrementAndGet()
                        } else {
                            otherStatusCount.incrementAndGet()
                        }
                    }
                    else -> otherStatusCount.incrementAndGet()
                }
            }
        }

        runBlocking {
            jobs.awaitAll()
        }

        // Verify counts
        assertEquals(50, successCount.get(), "Exactly 50 claims must succeed for a 50-unit deal")
        assertEquals(200, soldOutCount.get(), "Remaining 200 claims must fail with SOLD_OUT")
        assertEquals(0, otherStatusCount.get(), "No unexpected status codes")

        // Verify deal state in API
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, getDealResp.status)
        val updatedDeal = json.parseToJsonElement(getDealResp.body<String>()).jsonObject
        assertEquals("0", updatedDeal["stock_available"]!!.jsonPrimitive.content)
        assertEquals("50", updatedDeal["stock_total"]!!.jsonPrimitive.content)
    }

    /**
     * Requirement 2: 20 or more concurrent requests with one Idempotency-Key create exactly one claim.
     */
    @Test
    fun test20ConcurrentRequestsWithOneIdempotencyKeyCreateExactlyOneClaim() = testApplication {
        val config = TestDatabase.testConfig!!.copy(rateLimitEnabled = false)
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantId = UUID.randomUUID()
        val adminToken = JwtTestHelper.createAdminToken(restaurantId = restaurantId)

        val now = Instant.now()
        val startsAt = now.minus(5, ChronoUnit.MINUTES).toString()
        val endsAt = now.plus(2, ChronoUnit.HOURS).toString()

        val createResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Single Deal 50",
                    pricePaise = 5000,
                    stockTotal = 50,
                    startsAt = startsAt,
                    endsAt = endsAt
                )
            )
        }
        val dealId = json.parseToJsonElement(createResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val customerId = UUID.randomUUID()
        val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)
        val sharedKey = "shared-idempotency-key-${UUID.randomUUID()}"

        val concurrentCount = 25
        val responses = ConcurrentHashMap<Int, Int>() // status code -> count
        val createdClaimIds = ConcurrentHashMap.newKeySet<String>()

        val jobs = (1..concurrentCount).map {
            CoroutineScope(Dispatchers.IO).async {
                val resp = client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $customerToken")
                    header("Idempotency-Key", sharedKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity": 2}""")
                }

                responses.compute(resp.status.value) { _, count -> (count ?: 0) + 1 }

                if (resp.status == HttpStatusCode.Created) {
                    val body = resp.body<String>()
                    val claimObj = json.parseToJsonElement(body).jsonObject
                    createdClaimIds.add(claimObj["id"]!!.jsonPrimitive.content)
                }
            }
        }

        runBlocking {
            jobs.awaitAll()
        }

        // Must create exactly 1 unique claim ID across all 25 requests!
        assertEquals(1, createdClaimIds.size, "Exactly one unique claim ID must be created")
        assertTrue(responses[201] != null && responses[201]!! >= 1, "At least one request returned 201 Created")

        // Check deal stock in API: exactly 2 units deducted (50 - 2 = 48)
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val updatedDeal = json.parseToJsonElement(getDealResp.body<String>()).jsonObject
        assertEquals("48", updatedDeal["stock_available"]!!.jsonPrimitive.content)
    }

    /**
     * Requirement 3: Concurrent claims by one customer with different keys create exactly one claim.
     */
    @Test
    fun testConcurrentClaimsByOneCustomerWithDifferentKeysCreateExactlyOneClaim() = testApplication {
        val config = TestDatabase.testConfig!!.copy(rateLimitEnabled = false)
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantId = UUID.randomUUID()
        val adminToken = JwtTestHelper.createAdminToken(restaurantId = restaurantId)

        val now = Instant.now()
        val startsAt = now.minus(5, ChronoUnit.MINUTES).toString()
        val endsAt = now.plus(2, ChronoUnit.HOURS).toString()

        val createResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Single Customer Deal",
                    pricePaise = 5000,
                    stockTotal = 50,
                    startsAt = startsAt,
                    endsAt = endsAt
                )
            )
        }
        val dealId = json.parseToJsonElement(createResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val customerId = UUID.randomUUID()
        val customerToken = JwtTestHelper.createCustomerToken(customerId = customerId)

        val concurrentCount = 20
        val createdCount = AtomicInteger(0)
        val alreadyClaimedCount = AtomicInteger(0)
        val otherCount = AtomicInteger(0)

        val jobs = (1..concurrentCount).map { i ->
            CoroutineScope(Dispatchers.IO).async {
                val uniqueKey = "customer-key-$i-${UUID.randomUUID()}"
                val resp = client.post("/v1/deals/$dealId/claims") {
                    header(HttpHeaders.Authorization, "Bearer $customerToken")
                    header("Idempotency-Key", uniqueKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity": 1}""")
                }

                when (resp.status) {
                    HttpStatusCode.Created -> createdCount.incrementAndGet()
                    HttpStatusCode.Conflict -> {
                        val body = resp.body<String>()
                        val err = json.decodeFromString<ErrorResponse>(body)
                        if (err.error.code == "ALREADY_CLAIMED") {
                            alreadyClaimedCount.incrementAndGet()
                        } else {
                            otherCount.incrementAndGet()
                        }
                    }
                    else -> otherCount.incrementAndGet()
                }
            }
        }

        runBlocking {
            jobs.awaitAll()
        }

        assertEquals(1, createdCount.get(), "Customer can hold at most one active claim")
        assertEquals(19, alreadyClaimedCount.get(), "All other concurrent requests must fail with ALREADY_CLAIMED")
        assertEquals(0, otherCount.get())

        // Check stock: exactly 1 unit deducted
        val getDealResp = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val updatedDeal = json.parseToJsonElement(getDealResp.body<String>()).jsonObject
        assertEquals("49", updatedDeal["stock_available"]!!.jsonPrimitive.content)
    }
}
