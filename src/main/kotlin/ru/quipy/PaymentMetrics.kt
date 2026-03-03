package ru.quipy

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class PaymentMetrics {
    val incomingRequestsCounter = Counter.builder("payment_requests_incoming_total")
        .description("Total number of incoming payment requests")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val processPaymentStartedCounter = Counter.builder("process_payment_started_total")
        .description("Number of process payment started")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val processPaymentRateLimiterPassedCounter = Counter.builder("process_payment_rate_limiter_passed")
        .description("processPaymentRateLimiterPassedCounter")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val performPaymentStartedCounter = Counter.builder("perform_payment_started_total")
        .description("performPaymentStartedCounter")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val submissionLogged = Counter.builder("submission_logged")
        .description("submissionLogged")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val windowAcquired = Counter.builder("window_acquired")
        .description("windowAcquired")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val rateLimiterAcquired = Counter.builder("rate_limiter_acquired")
        .description("rateLimiterAcquired")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val responseReceived = Counter.builder("response_received")
            .description("responseReceived")
            .tag("type", "payment")
            .register(Metrics.globalRegistry)

    val bodyRead = Counter.builder("body_read")
        .description("bodyRead")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val processingLogged = Counter.builder("processing_logged")
        .description("processingLogged")
        .tag("type", "payment")
        .register(Metrics.globalRegistry)

    val paymentOperationDurationTimer = Timer.builder("payment_operation_duration_seconds")
        .description("Payment processing duration in seconds")
        .tag("type", "payment")
        .publishPercentiles(0.95, 0.99)
        .register(Metrics.globalRegistry)

    val paymentTotalDurationTimer = Timer.builder("payment_total_duration_seconds")
        .description("Payment processing duration in seconds")
        .tag("type", "payment")
        .publishPercentiles(0.80, 0.95, 0.99)
        .register(Metrics.globalRegistry)
}