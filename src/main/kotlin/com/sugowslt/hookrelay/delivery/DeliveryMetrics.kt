package com.sugowslt.hookrelay.delivery

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class DeliveryMetrics(
    private val registry: MeterRegistry,
) {
    private val claimedCounter = Counter.builder(CLAIMED_METRIC)
        .description("Number of webhook deliveries claimed by workers")
        .register(registry)

    private val processingTimers = DeliveryMetricOutcome.entries.associateWith { outcome ->
        Timer.builder(PROCESSING_METRIC)
            .description("Webhook delivery processing time and outcome count")
            .tag(OUTCOME_TAG, outcome.tagValue)
            .register(registry)
    }

    fun recordClaimed(count: Int) {
        require(count >= 0) { "claimed count must not be negative" }
        if (count > 0) {
            claimedCounter.increment(count.toDouble())
        }
    }

    fun startProcessing(): Timer.Sample = Timer.start(registry)

    fun recordProcessing(sample: Timer.Sample, outcome: DeliveryMetricOutcome) {
        sample.stop(processingTimers.getValue(outcome))
    }

    companion object {
        const val CLAIMED_METRIC = "hookrelay.delivery.claimed"
        const val PROCESSING_METRIC = "hookrelay.delivery.processing"
        const val OUTCOME_TAG = "outcome"
    }
}

enum class DeliveryMetricOutcome(
    val tagValue: String,
) {
    SUCCEEDED("succeeded"),
    RETRY_SCHEDULED("retry_scheduled"),
    FAILED("failed"),
    DEAD_LETTER("dead_letter"),
    LEASE_LOST("lease_lost"),
    PROCESSING_ERROR("processing_error"),
}
