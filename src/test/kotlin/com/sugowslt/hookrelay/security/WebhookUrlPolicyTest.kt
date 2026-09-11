package com.sugowslt.hookrelay.security

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebhookUrlPolicyTest {
    @Test
    fun `public https endpoint is accepted`() {
        val policy = policyResolvingTo(8, 8, 8, 8)

        val validated = policy.validate("https://hooks.example.com/events")

        assertEquals("hooks.example.com", validated.uri.host)
    }

    @Test
    fun `http endpoint is rejected by default`() {
        val policy = policyResolvingTo(8, 8, 8, 8)

        val exception = assertFailsWith<InvalidWebhookUrlException> {
            policy.validate("http://hooks.example.com/events")
        }

        assertEquals("WEBHOOK_URL_SCHEME_NOT_ALLOWED", exception.errorCode)
    }

    @Test
    fun `userinfo in endpoint is rejected`() {
        val policy = policyResolvingTo(8, 8, 8, 8)

        val exception = assertFailsWith<InvalidWebhookUrlException> {
            policy.validate("https://user:password@hooks.example.com/events")
        }

        assertEquals("WEBHOOK_URL_USERINFO_NOT_ALLOWED", exception.errorCode)
    }

    @Test
    fun `localhost is rejected before DNS lookup`() {
        val policy = policyResolvingTo(8, 8, 8, 8)

        val exception = assertFailsWith<InvalidWebhookUrlException> {
            policy.validate("https://localhost/events")
        }

        assertEquals("WEBHOOK_URL_HOST_NOT_PUBLIC", exception.errorCode)
    }

    @Test
    fun `private IPv4 destination is rejected`() {
        val policy = policyResolvingTo(10, 0, 0, 8)

        val exception = assertFailsWith<InvalidWebhookUrlException> {
            policy.validate("https://hooks.example.com/events")
        }

        assertEquals("WEBHOOK_URL_HOST_NOT_PUBLIC", exception.errorCode)
    }

    private fun policyResolvingTo(vararg address: Int): WebhookUrlPolicy {
        val bytes = address.map(Int::toByte).toByteArray()
        return WebhookUrlPolicy(
            hostResolver = HostResolver { listOf(InetAddress.getByAddress(bytes)) },
        )
    }
}
