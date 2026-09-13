package com.sugowslt.hookrelay.delivery

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

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

    private val claimTimers = DeliveryClaimMetricOutcome.entries.associateWith { outcome ->
        Timer.builder(CLAIM_METRIC)
            .description("Webhook delivery queue claim query time")
            .tag(OUTCOME_TAG, outcome.tagValue)
            .register(registry)
    }

    private val queueDepths = DeliveryQueueMetricState.entries.associateWith { state ->
        AtomicLong(0).also { value ->
            Gauge.builder(QUEUE_DEPTH_METRIC, value) { it.get().toDouble() }
                .description("Current webhook delivery queue depth by state")
                .tag(STATE_TAG, state.tagValue)
                .register(registry)
        }
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

    fun startClaim(): Timer.Sample = Timer.start(registry)

    fun recordClaim(sample: Timer.Sample, outcome: DeliveryClaimMetricOutcome) {
        sample.stop(claimTimers.getValue(outcome))
    }

    fun recordQueueSnapshot(snapshot: DeliveryQueueSnapshot) {
        require(snapshot.claimable >= 0) { "claimable queue depth must not be negative" }
        require(snapshot.scheduled >= 0) { "scheduled queue depth must not be negative" }
        require(snapshot.leased >= 0) { "leased queue depth must not be negative" }
        require(snapshot.stalled >= 0) { "stalled queue depth must not be negative" }

        queueDepths.getValue(DeliveryQueueMetricState.CLAIMABLE).set(snapshot.claimable)
        queueDepths.getValue(DeliveryQueueMetricState.SCHEDULED).set(snapshot.scheduled)
        queueDepths.getValue(DeliveryQueueMetricState.LEASED).set(snapshot.leased)
        queueDepths.getValue(DeliveryQueueMetricState.STALLED).set(snapshot.stalled)
    }

    companion object {
        const val CLAIMED_METRIC = "hookrelay.delivery.claimed"
        const val CLAIM_METRIC = "hookrelay.delivery.claim"
        const val QUEUE_DEPTH_METRIC = "hookrelay.delivery.queue.depth"
        const val PROCESSING_METRIC = "hookrelay.delivery.processing"
        const val OUTCOME_TAG = "outcome"
        const val STATE_TAG = "state"
    }
}

enum class DeliveryClaimMetricOutcome(
    val tagValue: String,
) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
}

enum class DeliveryQueueMetricState(
    val tagValue: String,
) {
    CLAIMABLE("claimable"),
    SCHEDULED("scheduled"),
    LEASED("leased"),
    STALLED("stalled"),
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
