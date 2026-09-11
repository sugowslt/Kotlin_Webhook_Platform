package com.sugowslt.hookrelay.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignatureServiceTest {
    private val signatureService = WebhookSignatureService()

    @Test
    fun `signature verifies with the same delivery data`() {
        val signature = signatureService.sign(
            secret = "test-secret",
            timestampSeconds = 1_789_000_000,
            deliveryId = "delivery-1",
            payload = "{\"event\":\"created\"}",
        )

        assertTrue(
            signatureService.verify(
                secret = "test-secret",
                timestampSeconds = 1_789_000_000,
                deliveryId = "delivery-1",
                payload = "{\"event\":\"created\"}",
                signature = signature,
            ),
        )
    }

    @Test
    fun `payload modification fails verification`() {
        val signature = signatureService.sign(
            secret = "test-secret",
            timestampSeconds = 1_789_000_000,
            deliveryId = "delivery-1",
            payload = "{\"event\":\"created\"}",
        )

        assertFalse(
            signatureService.verify(
                secret = "test-secret",
                timestampSeconds = 1_789_000_000,
                deliveryId = "delivery-1",
                payload = "{\"event\":\"deleted\"}",
                signature = signature,
            ),
        )
    }

    @Test
    fun `unknown signature version is rejected`() {
        assertFalse(
            signatureService.verify(
                secret = "test-secret",
                timestampSeconds = 1_789_000_000,
                deliveryId = "delivery-1",
                payload = "{}",
                signature = "v2=unknown",
            ),
        )
    }
}
