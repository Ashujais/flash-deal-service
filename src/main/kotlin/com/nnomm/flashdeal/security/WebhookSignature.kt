package com.nnomm.flashdeal.security

import com.nnomm.flashdeal.error.AppException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class WebhookSignature(private val webhookSecret: String) {

    private val headerPattern = Regex("""^t=(\d+),v1=([0-9a-fA-F]+)$""")

    fun verify(signatureHeader: String?, rawBodyBytes: ByteArray, currentUnixSeconds: Long = System.currentTimeMillis() / 1000) {
        if (signatureHeader.isNullOrBlank()) {
            throw AppException.Unauthenticated("Missing X-Payment-Signature header")
        }

        val match = headerPattern.matchEntire(signatureHeader.trim())
            ?: throw AppException.Unauthenticated("Malformed X-Payment-Signature header format")

        val tStr = match.groupValues[1]
        val receivedHex = match.groupValues[2].lowercase()

        val t = try {
            tStr.toLong()
        } catch (e: Exception) {
            throw AppException.Unauthenticated("Invalid timestamp in signature header")
        }

        if (abs(currentUnixSeconds - t) > 300) {
            throw AppException.Unauthenticated("Signature timestamp is outside the 300s window")
        }

        val expectedHex = computeSignature(t, rawBodyBytes)

        val expectedBytes = expectedHex.toByteArray(StandardCharsets.UTF_8)
        val receivedBytes = receivedHex.toByteArray(StandardCharsets.UTF_8)

        if (!MessageDigest.isEqual(expectedBytes, receivedBytes)) {
            throw AppException.Unauthenticated("Webhook signature mismatch")
        }
    }

    fun computeSignature(timestampSeconds: Long, rawBodyBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(webhookSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)

        // Signed message is t, a full stop, then raw body bytes
        val prefix = "$timestampSeconds.".toByteArray(StandardCharsets.UTF_8)
        mac.update(prefix)
        mac.update(rawBodyBytes)

        val rawHmac = mac.doFinal()
        return rawHmac.joinToString("") { "%02x".format(it) }
    }

    fun generateHeader(timestampSeconds: Long, rawBodyBytes: ByteArray): String {
        val sig = computeSignature(timestampSeconds, rawBodyBytes)
        return "t=$timestampSeconds,v1=$sig"
    }
}
