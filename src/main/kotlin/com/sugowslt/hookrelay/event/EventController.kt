package com.sugowslt.hookrelay.event

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventIntakeService: EventIntakeService,
) {
    @PostMapping("/{eventType}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(
        @PathVariable eventType: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody payload: String,
    ): ResponseEntity<EventAcceptedResponse> {
        val accepted = eventIntakeService.accept(
            AcceptEventCommand(
                eventType = eventType,
                idempotencyKey = idempotencyKey,
                payload = payload,
            ),
        )
        return ResponseEntity.accepted()
            .header(IDEMPOTENCY_REPLAYED_HEADER, accepted.replayed.toString())
            .header(HttpHeaders.LOCATION, "/api/v1/events/${accepted.eventId}")
            .body(EventAcceptedResponse.from(accepted))
    }

    companion object {
        const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"
    }
}

data class EventAcceptedResponse(
    val eventId: UUID,
    val deliveryCount: Int,
    val replayed: Boolean,
    val acceptedAt: Instant,
) {
    companion object {
        fun from(accepted: AcceptedEvent): EventAcceptedResponse = EventAcceptedResponse(
            eventId = accepted.eventId,
            deliveryCount = accepted.deliveryCount,
            replayed = accepted.replayed,
            acceptedAt = accepted.acceptedAt,
        )
    }
}
