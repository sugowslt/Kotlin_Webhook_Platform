package com.sugowslt.hookrelay.delivery

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {
    private val policy = RetryPolicy(random = { 0.5 })

    @Test
    fun `400 response is a permanent failure`() {
        val decision = policy.forHttpStatus(statusCode = 400, completedAttempts = 1)

        assertFalse(decision.shouldRetry)
        assertEquals(null, decision.delay)
    }

    @Test
    fun `408 429 and 5xx responses are retryable`() {
        assertTrue(policy.forHttpStatus(408, completedAttempts = 1).shouldRetry)
        assertTrue(policy.forHttpStatus(429, completedAttempts = 1).shouldRetry)
        assertTrue(policy.forHttpStatus(503, completedAttempts = 1).shouldRetry)
    }

    @Test
    fun `retry after header overrides exponential delay`() {
        val decision = policy.forHttpStatus(
            statusCode = 429,
            completedAttempts = 2,
            retryAfter = Duration.ofSeconds(7),
        )

        assertEquals(Duration.ofSeconds(7), decision.delay)
    }

    @Test
    fun `backoff doubles after each completed attempt`() {
        assertEquals(Duration.ofSeconds(1), policy.forNetworkFailure(1).delay)
        assertEquals(Duration.ofSeconds(2), policy.forNetworkFailure(2).delay)
        assertEquals(Duration.ofSeconds(4), policy.forNetworkFailure(3).delay)
    }

    @Test
    fun `retry stops after maximum attempts`() {
        val decision = policy.forNetworkFailure(completedAttempts = 5)

        assertFalse(decision.shouldRetry)
    }
}
