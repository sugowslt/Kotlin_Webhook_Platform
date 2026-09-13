package com.sugowslt.hookrelay.delivery

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

data class DeliveryQueueSnapshot(
    val claimable: Long,
    val scheduled: Long,
    val leased: Long,
    val stalled: Long,
)

interface DeliveryQueueSnapshotStore {
    fun load(now: Instant): DeliveryQueueSnapshot
}

@Repository
class JdbcDeliveryQueueSnapshotStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DeliveryQueueSnapshotStore {
    override fun load(now: Instant): DeliveryQueueSnapshot = checkNotNull(
        jdbcTemplate.queryForObject(
            SNAPSHOT_SQL,
            MapSqlParameterSource("now", Timestamp.from(now)),
        ) { resultSet, _ ->
            DeliveryQueueSnapshot(
                claimable = resultSet.getLong("claimable"),
                scheduled = resultSet.getLong("scheduled"),
                leased = resultSet.getLong("leased"),
                stalled = resultSet.getLong("stalled"),
            )
        },
    )

    companion object {
        private val SNAPSHOT_SQL = """
            SELECT COUNT(*) FILTER (
                       WHERE (
                           status IN ('PENDING', 'RETRY_WAIT')
                           AND next_attempt_at <= :now
                       ) OR (
                           status = 'PROCESSING'
                           AND lease_until <= :now
                       )
                   ) AS claimable,
                   COUNT(*) FILTER (
                       WHERE status IN ('PENDING', 'RETRY_WAIT')
                         AND next_attempt_at > :now
                   ) AS scheduled,
                   COUNT(*) FILTER (
                       WHERE status = 'PROCESSING'
                         AND lease_until > :now
                   ) AS leased,
                   COUNT(*) FILTER (
                       WHERE status = 'PROCESSING'
                         AND lease_until IS NULL
                   ) AS stalled
            FROM webhook_deliveries
            WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING')
        """.trimIndent()
    }
}

@Component
class DeliveryQueueMetricsSampler(
    private val snapshotStore: DeliveryQueueSnapshotStore,
    private val deliveryMetrics: DeliveryMetrics,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DeliveryQueueMetricsSampler::class.java)

    @Scheduled(fixedDelayString = "\${hook-relay.metrics.queue-snapshot-interval-millis:5000}")
    fun sample() {
        try {
            deliveryMetrics.recordQueueSnapshot(snapshotStore.load(Instant.now(clock)))
        } catch (exception: RuntimeException) {
            log.warn("delivery.queue.snapshot.failed", exception)
        }
    }
}
