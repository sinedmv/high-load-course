package ru.quipy.payments.logic

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Metrics
import jakarta.annotation.PostConstruct
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.PaymentMetrics
import ru.quipy.common.utils.*
import ru.quipy.common.utils.exceptions.TooManyRequestsWithRetryAfterException
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Service
class OrderPayer {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentMetrics: PaymentMetrics

    @Autowired
    private lateinit var paymentService: PaymentService

    private lateinit var rateLimiter: RateLimiter
    private var retryAfter: Long = 0

    private val paymentExecutor = ThreadPoolExecutor(
        16,
        16,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(30000),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

    @PostConstruct
    fun init() {
        val accountProperties = paymentService.getAllAccountsProperties()

        val rateLimitPerSec = accountProperties.minOf { it.rateLimitPerSec }
        rateLimiter = TokenBucketRateLimiter(rateLimitPerSec, 100, 1, TimeUnit.SECONDS)
        // SLA > N / RPS + CurrentProcessing = N / 11 + AvgProcessing => N < 132 (bucketCapacity)

        retryAfter = accountProperties.minOf { it.averageProcessingTime }.toMillis() + 3000

        setupMetrics()
    }

    private fun setupMetrics() {
        Gauge.builder("payment.executor.queue.size", paymentExecutor.queue) { queue ->
            queue.size.toDouble()
        }
            .description("Current number of tasks in payment executor queue")
            .register(Metrics.globalRegistry)

        Gauge.builder("payment.executor.active.threads", paymentExecutor) { executor ->
            executor.activeCount.toDouble()
        }
            .description("Number of active threads in payment executor")
            .register(Metrics.globalRegistry)

        Gauge.builder("payment.executor.pool.size", paymentExecutor) { executor ->
            executor.poolSize.toDouble()
        }
            .description("Current number of threads in payment executor pool")
            .register(Metrics.globalRegistry)

        Gauge.builder("payment.executor.pool.max", paymentExecutor) { executor ->
            executor.maximumPoolSize.toDouble()
        }
            .description("Maximum number of threads in payment executor pool")
            .register(Metrics.globalRegistry)
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        if (!rateLimiter.tick()) {
            logger.warn("429 TooMany Req. OrderId: ${orderId}, PaymentId: ${paymentId}")
            throw TooManyRequestsWithRetryAfterException(retryAfter)
        }

        val createdAt = System.currentTimeMillis()

        paymentExecutor.submit {
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            val duration = System.currentTimeMillis() - createdAt;
            paymentMetrics.paymentTotalDurationTimer.record(duration, TimeUnit.MILLISECONDS);
        }
        return createdAt
    }

    private fun randomizeRetryAfter(minValue: Long, jitterFactor: Double = 2.0): Long {
        val jitter = (minValue * jitterFactor * Math.random()).toLong()
        return minValue + jitter
    }
}