package com.sugowslt.hookrelay.delivery

import com.sugowslt.hookrelay.security.InvalidWebhookUrlException
import com.sugowslt.hookrelay.security.WebhookSignatureService
import com.sugowslt.hookrelay.security.WebhookUrlPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class WebhookDeliveryWorker(
    private val deliveryQueue: DeliveryQueue,
    private val httpClient: WebhookHttpClient,
    private val signatureService: WebhookSignatureService,
    private val webhookUrlPolicy: WebhookUrlPolicy,
    private val retryPolicy: RetryPolicy,
    private val deliveryMetrics: DeliveryMetrics,
    private val clock: Clock,
    @Value("\${hook-relay.worker.batch-size:20}") private val batchSize: Int,
    @Value("\${hook-relay.worker.lease-seconds:30}") private val leaseSeconds: Long,
    @Value("\${hook-relay.worker.request-timeout-seconds:5}") private val requestTimeoutSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(WebhookDeliveryWorker::class.java)

    init {
        require(batchSize in 1..100) { "batchSize must be between 1 and 100" }
        require(leaseSeconds > 0) { "leaseSeconds must be positive" }
        require(requestTimeoutSeconds > 0) { "requestTimeoutSeconds must be positive" }
    }

    @Scheduled(fixedDelayString = "\${hook-relay.worker.poll-interval-millis:1000}")
    fun processBatch(): Int {
        val claimSample = deliveryMetrics.startClaim()
        val claimed = try {
            deliveryQueue.claim(
                batchSize = batchSize,
                leaseDuration = Duration.ofSeconds(leaseSeconds),
                now = Instant.now(clock),
            )
        } catch (exception: RuntimeException) {
            deliveryMetrics.recordClaim(claimSample, DeliveryClaimMetricOutcome.FAILED)
            throw exception
        }
        deliveryMetrics.recordClaim(claimSample, DeliveryClaimMetricOutcome.SUCCEEDED)
        deliveryMetrics.recordClaimed(claimed.size)
        claimed.forEach { delivery ->
            val sample = deliveryMetrics.startProcessing()
            val outcome = try {
                process(delivery)
            } catch (exception: RuntimeException) {
                log.error("delivery.processing.failed deliveryId={}", delivery.id, exception)
                DeliveryMetricOutcome.PROCESSING_ERROR
            }
            deliveryMetrics.recordProcessing(sample, outcome)
        }
        return claimed.size
    }

    private fun process(delivery: ClaimedDelivery): DeliveryMetricOutcome {
        val resolution = resolve(delivery)
        val recorded = deliveryQueue.recordResult(delivery, resolution, Instant.now(clock))
        if (!recorded) {
            log.warn("delivery.result.ignored deliveryId={} reason=lease_lost", delivery.id)
            return DeliveryMetricOutcome.LEASE_LOST
        }
        return resolution.toMetricOutcome()
    }

    private fun DeliveryResolution.toMetricOutcome(): DeliveryMetricOutcome = when (this) {
        is DeliveryResolution.Succeeded -> DeliveryMetricOutcome.SUCCEEDED
        is DeliveryResolution.RetryAt -> DeliveryMetricOutcome.RETRY_SCHEDULED
        is DeliveryResolution.Failed -> DeliveryMetricOutcome.FAILED
        is DeliveryResolution.DeadLetter -> DeliveryMetricOutcome.DEAD_LETTER
    }

    private fun resolve(delivery: ClaimedDelivery): DeliveryResolution {
        return try {
            webhookUrlPolicy.validate(delivery.endpointUrl)
            val timestampSeconds = Instant.now(clock).epochSecond
            val response = httpClient.send(
                WebhookHttpRequest(
                    endpointUrl = delivery.endpointUrl,
                    payload = delivery.payload,
                    signature = signatureService.sign(
                        secret = delivery.signingSecret,
                        timestampSeconds = timestampSeconds,
                        deliveryId = delivery.id.toString(),
                        payload = delivery.payload,
                    ),
                    timestampSeconds = timestampSeconds,
                    deliveryId = delivery.id.toString(),
                    timeout = Duration.ofSeconds(requestTimeoutSeconds),
                ),
            )
            resolveHttpResponse(delivery, response)
        } catch (exception: InvalidWebhookUrlException) {
            DeliveryResolution.Failed(
                statusCode = null,
                errorMessage = "${exception.errorCode}: ${exception.message}",
            )
        } catch (exception: IOException) {
            resolveNetworkFailure(delivery, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            resolveNetworkFailure(delivery, exception)
        }
    }

    private fun resolveHttpResponse(
        delivery: ClaimedDelivery,
        response: WebhookHttpResponse,
    ): DeliveryResolution {
        if (response.statusCode in 200..299) {
            return DeliveryResolution.Succeeded(response.statusCode)
        }

        val completedAttempts = delivery.completedAttempts + 1
        val decision = retryPolicy.forHttpStatus(
            statusCode = response.statusCode,
            completedAttempts = completedAttempts,
            retryAfter = response.retryAfter,
        )
        if (decision.shouldRetry) {
            return DeliveryResolution.RetryAt(
                nextAttemptAt = Instant.now(clock).plus(checkNotNull(decision.delay)),
                statusCode = response.statusCode,
                errorMessage = "HTTP ${response.statusCode}",
            )
        }
        return if (retryPolicy.isRetryableHttpStatus(response.statusCode)) {
            DeliveryResolution.DeadLetter(
                statusCode = response.statusCode,
                errorMessage = "HTTP ${response.statusCode}; maximum attempts reached",
            )
        } else {
            DeliveryResolution.Failed(
                statusCode = response.statusCode,
                errorMessage = "HTTP ${response.statusCode}",
            )
        }
    }

    private fun resolveNetworkFailure(
        delivery: ClaimedDelivery,
        exception: Exception,
    ): DeliveryResolution {
        val completedAttempts = delivery.completedAttempts + 1
        val decision = retryPolicy.forNetworkFailure(completedAttempts)
        val message = exception.message?.takeIf { it.isNotBlank() } ?: exception.javaClass.simpleName
        return if (decision.shouldRetry) {
            DeliveryResolution.RetryAt(
                nextAttemptAt = Instant.now(clock).plus(checkNotNull(decision.delay)),
                statusCode = null,
                errorMessage = message,
            )
        } else {
            DeliveryResolution.DeadLetter(
                statusCode = null,
                errorMessage = "$message; maximum attempts reached",
            )
        }
    }
}
