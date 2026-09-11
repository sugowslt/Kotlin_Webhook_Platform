package com.sugowslt.hookrelay.delivery

import java.time.Duration
import kotlin.math.pow

data class RetryDecision(
    val shouldRetry: Boolean,
    val delay: Duration? = null,
)

class RetryPolicy(
    private val maxAttempts: Int = 5,
    private val baseDelay: Duration = Duration.ofSeconds(1),
    private val maxDelay: Duration = Duration.ofMinutes(1),
    private val jitterRatio: Double = 0.2,
    private val random: () -> Double = Math::random,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!baseDelay.isNegative && !baseDelay.isZero) { "baseDelay must be positive" }
        require(maxDelay >= baseDelay) { "maxDelay must be greater than or equal to baseDelay" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0.0 and 1.0" }
    }

    fun forHttpStatus(
        statusCode: Int,
        completedAttempts: Int,
        retryAfter: Duration? = null,
    ): RetryDecision {
        if (!isRetryableStatus(statusCode) || completedAttempts >= maxAttempts) {
            return RetryDecision(shouldRetry = false)
        }

        val delay = retryAfter
            ?.coerceAtMost(maxDelay)
            ?: calculateBackoff(completedAttempts)
        return RetryDecision(shouldRetry = true, delay = delay)
    }

    fun forNetworkFailure(completedAttempts: Int): RetryDecision {
        if (completedAttempts >= maxAttempts) {
            return RetryDecision(shouldRetry = false)
        }
        return RetryDecision(shouldRetry = true, delay = calculateBackoff(completedAttempts))
    }

    private fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    private fun calculateBackoff(completedAttempts: Int): Duration {
        require(completedAttempts > 0) { "completedAttempts must be positive" }

        val exponent = (completedAttempts - 1).coerceAtMost(30)
        val multiplier = 2.0.pow(exponent).toLong()
        val cappedMillis = baseDelay.toMillis()
            .coerceAtMost(maxDelay.toMillis() / multiplier)
            .times(multiplier)
        val jitterFactor = 1.0 - jitterRatio + (2.0 * jitterRatio * random().coerceIn(0.0, 1.0))
        return Duration.ofMillis((cappedMillis * jitterFactor).toLong().coerceAtLeast(1))
    }
}
