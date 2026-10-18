package com.nnomm.flashdeal

import com.nnomm.flashdeal.domain.CreateDealRequest
import com.nnomm.flashdeal.error.ErrorResponse
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
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DealClaimLifecycleTests {

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

    @Test
    fun testDealInputValidations() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val now = Instant.now()

        // 1. Title too long (> 100 characters)
        val respLongTitle = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "A".repeat(101),
                    pricePaise = 5000,
                    stockTotal = 10,
                    startsAt = now.toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, respLongTitle.status)
        val errTitle = json.decodeFromString<ErrorResponse>(respLongTitle.body<String>())
        assertEquals("VALIDATION_FAILED", errTitle.error.code)

        // 2. Price out of range
        val respBadPrice = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Bad Price Deal",
                    pricePaise = 1_000_001,
                    stockTotal = 10,
                    startsAt = now.toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, respBadPrice.status)

        // 3. Stock total out of range
        val respBadStock = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Bad Stock Deal",
                    pricePaise = 1000,
                    stockTotal = 0,
                    startsAt = now.toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, respBadStock.status)

        // 4. starts_at after ends_at
        val respBadDates = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Bad Dates Deal",
                    pricePaise = 1000,
                    stockTotal = 10,
                    startsAt = now.plus(2, ChronoUnit.HOURS).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, respBadDates.status)

        // 5. Body over 16 KB -> 413 PAYLOAD_TOO_LARGE
        val largePadding = "X".repeat(17000)
        val respLarge = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "$largePadding", "price_paise": 1000, "stock_total": 10}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, respLarge.status)
        val errLarge = json.decodeFromString<ErrorResponse>(respLarge.body<String>())
        assertEquals("PAYLOAD_TOO_LARGE", errLarge.error.code)
    }

    @Test
    fun testClaimsOnlyWhileDealIsLive() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerToken = JwtTestHelper.createCustomerToken()
        val now = Instant.now()

        // Deal in future: starts in 10 minutes
        val futureDealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Future Deal",
                    pricePaise = 1000,
                    stockTotal = 10,
                    startsAt = now.plus(10, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val futureDealId = json.parseToJsonElement(futureDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val claimFuture = client.post("/v1/deals/$futureDealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "future-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.Conflict, claimFuture.status)
        val errFuture = json.decodeFromString<ErrorResponse>(claimFuture.body<String>())
        assertEquals("DEAL_NOT_LIVE", errFuture.error.code)

        // Deal in past: ended 5 minutes ago
        val pastDealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Past Deal",
                    pricePaise = 1000,
                    stockTotal = 10,
                    startsAt = now.minus(2, ChronoUnit.HOURS).toString(),
                    endsAt = now.minus(5, ChronoUnit.MINUTES).toString()
                )
            )
        }
        val pastDealId = json.parseToJsonElement(pastDealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        val claimPast = client.post("/v1/deals/$pastDealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "past-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.Conflict, claimPast.status)
        val errPast = json.decodeFromString<ErrorResponse>(claimPast.body<String>())
        assertEquals("DEAL_NOT_LIVE", errPast.error.code)
    }

    @Test
    fun testIdempotencyKeyValidationsAndReplay() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val adminToken = JwtTestHelper.createAdminToken()
        val customerToken = JwtTestHelper.createCustomerToken()
        val now = Instant.now()

        val dealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Idempotent Deal",
                    pricePaise = 3000,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(dealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // 1. Missing Idempotency-Key -> 400 IDEMPOTENCY_KEY_INVALID
        val respMissingKey = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, respMissingKey.status)
        val errMissingKey = json.decodeFromString<ErrorResponse>(respMissingKey.body<String>())
        assertEquals("IDEMPOTENCY_KEY_INVALID", errMissingKey.error.code)

        // 2. Malformed Idempotency-Key (special characters like '@' or spaces) -> 400 IDEMPOTENCY_KEY_INVALID
        val respMalformedKey = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", "invalid key!@#")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, respMalformedKey.status)
        val errMalformedKey = json.decodeFromString<ErrorResponse>(respMalformedKey.body<String>())
        assertEquals("IDEMPOTENCY_KEY_INVALID", errMalformedKey.error.code)

        // 3. First valid claim -> 201 Created
        val validKey = "valid-key-12345"
        val respFirst = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", validKey)
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 2}""")
        }
        assertEquals(HttpStatusCode.Created, respFirst.status)
        val claimId1 = json.parseToJsonElement(respFirst.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // 4. Same key, same deal and quantity -> 201 with Idempotent-Replayed: true
        val respReplayed = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", validKey)
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 2}""")
        }
        assertEquals(HttpStatusCode.Created, respReplayed.status)
        assertEquals("true", respReplayed.headers["Idempotent-Replayed"])
        val claimId2 = json.parseToJsonElement(respReplayed.body<String>()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(claimId1, claimId2)

        // 5. Same key, different quantity -> 422 IDEMPOTENCY_KEY_REUSED
        val respDifferentQty = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            header("Idempotency-Key", validKey)
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, respDifferentQty.status)
        val errReused = json.decodeFromString<ErrorResponse>(respDifferentQty.body<String>())
        assertEquals("IDEMPOTENCY_KEY_REUSED", errReused.error.code)
    }
}
