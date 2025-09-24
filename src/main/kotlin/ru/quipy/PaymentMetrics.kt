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

    val paymentDurationTimer = Timer.builder("payment_duration_seconds")
        .description("Payment processing duration in seconds")
        .tag("type", "payment")
        .publishPercentiles(0.95, 0.99)
        .register(Metrics.globalRegistry)
}