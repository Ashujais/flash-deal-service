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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndVisibilityTests {

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
     * Requirement 6: Customers can't read other customers' claims, and admins can't read claims on other restaurants' deals.
     * Anyone else gets 404, not 403!
     */
    @Test
    fun testVisibilityRulesEnforce404Not403() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val restaurantAId = UUID.randomUUID()
        val adminAToken = JwtTestHelper.createAdminToken(restaurantId = restaurantAId)

        val restaurantBId = UUID.randomUUID()
        val adminBToken = JwtTestHelper.createAdminToken(restaurantId = restaurantBId)

        val customerAId = UUID.randomUUID()
        val customerAToken = JwtTestHelper.createCustomerToken(customerId = customerAId)

        val customerBId = UUID.randomUUID()
        val customerBToken = JwtTestHelper.createCustomerToken(customerId = customerBId)

        // Admin A creates deal
        val now = Instant.now()
        val dealResp = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $adminAToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Restaurant A Deal",
                    pricePaise = 2500,
                    stockTotal = 10,
                    startsAt = now.minus(5, ChronoUnit.MINUTES).toString(),
                    endsAt = now.plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        val dealId = json.parseToJsonElement(dealResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // Customer A claims deal
        val claimResp = client.post("/v1/deals/$dealId/claims") {
            header(HttpHeaders.Authorization, "Bearer $customerAToken")
            header("Idempotency-Key", "cust-a-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.Created, claimResp.status)
        val claimId = json.parseToJsonElement(claimResp.body<String>()).jsonObject["id"]!!.jsonPrimitive.content

        // 1. Customer A CAN read own claim -> 200
        val getClaimByCustomerA = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerAToken")
        }
        assertEquals(HttpStatusCode.OK, getClaimByCustomerA.status)

        // 2. Admin A (owner of the deal's restaurant) CAN read claim -> 200
        val getClaimByAdminA = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $adminAToken")
        }
        assertEquals(HttpStatusCode.OK, getClaimByAdminA.status)

        // 3. Customer B CANNOT read Customer A's claim -> 404 NOT 403!
        val getClaimByCustomerB = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $customerBToken")
        }
        assertEquals(HttpStatusCode.NotFound, getClaimByCustomerB.status)
        val errB = json.decodeFromString<ErrorResponse>(getClaimByCustomerB.body<String>())
        assertEquals("NOT_FOUND", errB.error.code)

        // 4. Admin B (different restaurant admin) CANNOT read claim -> 404 NOT 403!
        val getClaimByAdminB = client.get("/v1/claims/$claimId") {
            header(HttpHeaders.Authorization, "Bearer $adminBToken")
        }
        assertEquals(HttpStatusCode.NotFound, getClaimByAdminB.status)
        val errAdminB = json.decodeFromString<ErrorResponse>(getClaimByAdminB.body<String>())
        assertEquals("NOT_FOUND", errAdminB.error.code)
    }

    @Test
    fun testAuthenticationAndRolePermissions() = testApplication {
        val config = TestDatabase.testConfig!!
        application { module(config) }

        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }

        // 1. Missing Authorization header -> 401
        val respMissing = client.get("/v1/deals/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Unauthorized, respMissing.status)

        // 2. Expired token -> 401
        val expiredToken = JwtTestHelper.createExpiredToken()
        val respExpired = client.get("/v1/deals/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $expiredToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, respExpired.status)

        // 3. None algorithm token -> 401
        val noneToken = JwtTestHelper.createNoneAlgorithmToken()
        val respNone = client.get("/v1/deals/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $noneToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, respNone.status)

        // 4. Tampered token signature -> 401
        val validToken = JwtTestHelper.createCustomerToken()
        val tamperedToken = validToken.substring(0, validToken.length - 4) + "abcd"
        val respTampered = client.get("/v1/deals/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $tamperedToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, respTampered.status)

        // 5. Customer role trying to create deal -> 403 FORBIDDEN
        val customerToken = JwtTestHelper.createCustomerToken()
        val respCustCreateDeal = client.post("/v1/deals") {
            header(HttpHeaders.Authorization, "Bearer $customerToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreateDealRequest(
                    title = "Forbidden Deal",
                    pricePaise = 2000,
                    stockTotal = 10,
                    startsAt = Instant.now().toString(),
                    endsAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, respCustCreateDeal.status)
        val errCust = json.decodeFromString<ErrorResponse>(respCustCreateDeal.body<String>())
        assertEquals("FORBIDDEN", errCust.error.code)

        // 6. Admin role trying to claim deal -> 403 FORBIDDEN
        val adminToken = JwtTestHelper.createAdminToken()
        val respAdminClaim = client.post("/v1/deals/${UUID.randomUUID()}/claims") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header("Idempotency-Key", "admin-key-1")
            contentType(ContentType.Application.Json)
            setBody("""{"quantity": 1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, respAdminClaim.status)
        val errAdmin = json.decodeFromString<ErrorResponse>(respAdminClaim.body<String>())
        assertEquals("FORBIDDEN", errAdmin.error.code)
    }
}
