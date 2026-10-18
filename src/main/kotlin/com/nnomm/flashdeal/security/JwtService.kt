package com.nnomm.flashdeal.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.AlgorithmMismatchException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.nnomm.flashdeal.domain.AuthUser
import com.nnomm.flashdeal.domain.UserRole
import com.nnomm.flashdeal.error.AppException
import java.util.*

class JwtService(private val secret: String) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun verifyToken(token: String): AuthUser {
        try {
            // First decode to strictly inspect header algorithm
            val decoded = JWT.decode(token)
            if (decoded.algorithm != "HS256") {
                throw AppException.Unauthenticated("Invalid algorithm: ${decoded.algorithm}")
            }

            val jwt = verifier.verify(token)
            val subStr = jwt.subject ?: throw AppException.Unauthenticated("Missing subject claim")
            val sub = try {
                UUID.fromString(subStr)
            } catch (e: Exception) {
                throw AppException.Unauthenticated("Malformed sub UUID in token")
            }

            val roleClaim = jwt.getClaim("role")?.asString()
                ?: throw AppException.Unauthenticated("Missing role claim")
            val role = try {
                UserRole.fromString(roleClaim)
            } catch (e: Exception) {
                throw AppException.Unauthenticated("Invalid role claim: $roleClaim")
            }

            val restaurantId = if (role == UserRole.RESTAURANT_ADMIN) {
                val rIdStr = jwt.getClaim("restaurant_id")?.asString()
                    ?: throw AppException.Unauthenticated("Missing restaurant_id claim for restaurant_admin")
                try {
                    UUID.fromString(rIdStr)
                } catch (e: Exception) {
                    throw AppException.Unauthenticated("Malformed restaurant_id UUID in token")
                }
            } else {
                null
            }

            return AuthUser(userId = sub, role = role, restaurantId = restaurantId)
        } catch (e: AppException) {
            throw e
        } catch (e: TokenExpiredException) {
            throw AppException.Unauthenticated("Token expired")
        } catch (e: AlgorithmMismatchException) {
            throw AppException.Unauthenticated("Algorithm mismatch")
        } catch (e: JWTVerificationException) {
            throw AppException.Unauthenticated("Token verification failed")
        } catch (e: Exception) {
            throw AppException.Unauthenticated("Invalid token: ${e.message}")
        }
    }

    /**
     * Helper to mint tokens (useful for testing and verification)
     */
    fun createToken(
        userId: UUID,
        role: UserRole,
        restaurantId: UUID? = null,
        expiresInSeconds: Long = 3600
    ): String {
        val now = System.currentTimeMillis()
        var builder = JWT.create()
            .withSubject(userId.toString())
            .withClaim("role", if (role == UserRole.CUSTOMER) "customer" else "restaurant_admin")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + expiresInSeconds * 1000))

        if (role == UserRole.RESTAURANT_ADMIN && restaurantId != null) {
            builder = builder.withClaim("restaurant_id", restaurantId.toString())
        }

        return builder.sign(algorithm)
    }
}
