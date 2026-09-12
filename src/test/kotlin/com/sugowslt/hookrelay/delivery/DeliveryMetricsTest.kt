package com.sugowslt.hookrelay.delivery

import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeliveryMetricsTest {
    private val clock = MockClock()
    private val registry = SimpleMeterRegistry(SimpleConfig.DEFAULT, clock)
    private val metrics = DeliveryMetrics(registry)

    @Test
    fun `선점한 작업 수를 누적한다`() {
        metrics.recordClaimed(0)
        metrics.recordClaimed(3)

        assertEquals(
            3.0,
            registry.get(DeliveryMetrics.CLAIMED_METRIC).counter().count(),
        )
        assertFailsWith<IllegalArgumentException> {
            metrics.recordClaimed(-1)
        }
    }

    @Test
    fun `처리 결과와 소요 시간을 제한된 outcome 태그로 기록한다`() {
        DeliveryMetricOutcome.entries.forEach { outcome ->
            val sample = metrics.startProcessing()
            clock.add(Duration.ofMillis(10))
            metrics.recordProcessing(sample, outcome)
        }

        DeliveryMetricOutcome.entries.forEach { outcome ->
            val timer = registry.get(DeliveryMetrics.PROCESSING_METRIC)
                .tag(DeliveryMetrics.OUTCOME_TAG, outcome.tagValue)
                .timer()

            assertEquals(1L, timer.count())
            assertEquals(10.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001)
        }
        assertEquals(
            DeliveryMetricOutcome.entries.map(DeliveryMetricOutcome::tagValue).toSet(),
            registry.meters
                .filter { it.id.name == DeliveryMetrics.PROCESSING_METRIC }
                .mapNotNull { it.id.getTag(DeliveryMetrics.OUTCOME_TAG) }
                .toSet(),
        )
    }
}
