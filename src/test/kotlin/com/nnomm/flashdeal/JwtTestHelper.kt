package com.nnomm.flashdeal

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*

object JwtTestHelper {
    fun createCustomerToken(
        customerId: UUID = UUID.randomUUID(),
        secret: String = "test_jwt_secret_key_1234567890_test_key",
        expiresInSeconds: Long = 3600
    ): String {
        return JWT.create()
            .withSubject(customerId.toString())
            .withClaim("role", "customer")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .sign(Algorithm.HMAC256(secret))
    }

    fun createAdminToken(
        adminId: UUID = UUID.randomUUID(),
        restaurantId: UUID = UUID.randomUUID(),
        secret: String = "test_jwt_secret_key_1234567890_test_key",
        expiresInSeconds: Long = 3600
    ): String {
        return JWT.create()
            .withSubject(adminId.toString())
            .withClaim("role", "restaurant_admin")
            .withClaim("restaurant_id", restaurantId.toString())
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .sign(Algorithm.HMAC256(secret))
    }

    fun createNoneAlgorithmToken(
        userId: UUID = UUID.randomUUID(),
        role: String = "customer"
    ): String {
        return JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", role)
            .sign(Algorithm.none())
    }

    fun createExpiredToken(
        userId: UUID = UUID.randomUUID(),
        role: String = "customer",
        secret: String = "test_jwt_secret_key_1234567890_test_key"
    ): String {
        return JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", role)
            .withIssuedAt(Date(System.currentTimeMillis() - 7200 * 1000))
            .withExpiresAt(Date(System.currentTimeMillis() - 3600 * 1000))
            .sign(Algorithm.HMAC256(secret))
    }
}
