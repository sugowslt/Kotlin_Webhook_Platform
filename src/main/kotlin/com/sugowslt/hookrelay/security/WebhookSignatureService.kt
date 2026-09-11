package com.sugowslt.hookrelay.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookSignatureService {
    fun sign(
        secret: String,
        timestampSeconds: Long,
        deliveryId: String,
        payload: String,
    ): String {
        require(secret.isNotBlank()) { "secret must not be blank" }
        require(deliveryId.isNotBlank()) { "deliveryId must not be blank" }

        val message = "$timestampSeconds.$deliveryId.$payload"
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM))
        return VERSION_PREFIX + mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun verify(
        secret: String,
        timestampSeconds: Long,
        deliveryId: String,
        payload: String,
        signature: String,
    ): Boolean {
        if (!signature.startsWith(VERSION_PREFIX)) {
            return false
        }

        val expected = sign(secret, timestampSeconds, deliveryId, payload)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            signature.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val SIGNATURE_HEADER = "X-HookRelay-Signature"
        const val TIMESTAMP_HEADER = "X-HookRelay-Timestamp"
        const val DELIVERY_ID_HEADER = "X-HookRelay-Delivery"
        private const val ALGORITHM = "HmacSHA256"
        private const val VERSION_PREFIX = "v1="
    }
}
