package ru.quipy.middlewares

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.quipy.common.utils.exceptions.TooManyRequestsWithRetryAfterException

@RestControllerAdvice
class ExceptionMiddleware {

    @ExceptionHandler(TooManyRequestsWithRetryAfterException::class)
    fun handleTooManyRequests(e: TooManyRequestsWithRetryAfterException): ResponseEntity<Void> {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", e.getRetryAfter().toString())
                .build()
    }
}