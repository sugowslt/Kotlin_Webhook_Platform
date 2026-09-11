package com.sugowslt.hookrelay.subscription

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/subscriptions")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateSubscriptionRequest,
    ): ResponseEntity<CreateSubscriptionResponse> {
        val created = subscriptionService.create(
            CreateSubscriptionCommand(
                name = request.name,
                endpointUrl = request.endpointUrl,
                eventTypes = request.eventTypes,
            ),
        )
        return ResponseEntity.created(URI.create("/api/v1/subscriptions/${created.id}"))
            .body(CreateSubscriptionResponse.from(created))
    }
}

data class CreateSubscriptionRequest(
    @field:NotBlank(message = "Subscription name must not be blank")
    @field:Size(max = 100, message = "Subscription name must not exceed 100 characters")
    val name: String,

    @field:NotBlank(message = "Webhook URL must not be blank")
    @field:Size(max = 2048, message = "Webhook URL must not exceed 2048 characters")
    val endpointUrl: String,

    @field:NotEmpty(message = "At least one event type is required")
    @field:Size(max = 20, message = "Event types must not exceed 20 entries")
    val eventTypes: Set<String>,
)

data class CreateSubscriptionResponse(
    val id: UUID,
    val name: String,
    val endpointUrl: String,
    val eventTypes: Set<String>,
    val signingSecret: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(created: CreatedSubscription): CreateSubscriptionResponse = CreateSubscriptionResponse(
            id = created.id,
            name = created.name,
            endpointUrl = created.endpointUrl,
            eventTypes = created.eventTypes,
            signingSecret = created.signingSecret,
            createdAt = created.createdAt,
        )
    }
}
