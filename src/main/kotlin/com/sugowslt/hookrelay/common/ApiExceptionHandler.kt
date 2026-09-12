package com.sugowslt.hookrelay.common

import com.sugowslt.hookrelay.delivery.DeliveryNotFoundException
import com.sugowslt.hookrelay.delivery.DeliveryNotRedeliverableException
import com.sugowslt.hookrelay.event.IdempotencyKeyConflictException
import com.sugowslt.hookrelay.security.InvalidWebhookUrlException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(DeliveryNotFoundException::class)
    fun handleDeliveryNotFound(
        exception: DeliveryNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = error(
        HttpStatus.NOT_FOUND,
        exception.errorCode,
        exception.message ?: "Delivery was not found",
        request,
    )

    @ExceptionHandler(DeliveryNotRedeliverableException::class)
    fun handleDeliveryNotRedeliverable(
        exception: DeliveryNotRedeliverableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = error(
        HttpStatus.CONFLICT,
        exception.errorCode,
        exception.message ?: "Delivery cannot be redelivered",
        request,
    )

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun handleIdempotencyConflict(
        exception: IdempotencyKeyConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.CONFLICT, exception.errorCode, exception.message, request)

    @ExceptionHandler(InvalidWebhookUrlException::class)
    fun handleInvalidWebhookUrl(
        exception: InvalidWebhookUrlException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.BAD_REQUEST, exception.errorCode, exception.message, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "Request validation failed"
        return error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", message, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        exception: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        error(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", exception.message ?: "Request is invalid", request)

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(status).body(
        ApiErrorResponse(
            code = code,
            message = message,
            path = request.requestURI,
            traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID),
        ),
    )
}

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val path: String,
    val traceId: String?,
)
