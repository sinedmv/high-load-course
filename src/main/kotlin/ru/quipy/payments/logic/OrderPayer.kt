package ru.quipy.payments.logic

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Metrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.PaymentMetrics
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.common.utils.exceptions.TooManyRequestsWithRetryAfterException
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
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

    private val paymentExecutor = ThreadPoolExecutor(
        16,
        16,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(280), // SLA > T_waiting + T_proc = ActiveQueueSize / RPS + AvgProcessingTime => (в идеальной ситуации) N < 11 * 29 = 319
        NamedThreadFactory("payment-submission-executor"),
        ThreadPoolExecutor.AbortPolicy() // кидает exception, если очередь заполнена
    )
    // T_proc = AvgProcessingTime
    // T_waiting = ActiveQueueSize / RPS

    init {
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
        val createdAt = System.currentTimeMillis()

        try {
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
        } catch (e: RejectedExecutionException) {
            throw TooManyRequestsWithRetryAfterException(1000)
        }

        return createdAt
    }

    private fun randomizeRetryAfter(minValue: Long, jitterFactor: Double = 2.0): Long {
        val jitter = (minValue * jitterFactor * Math.random()).toLong()
        return minValue + jitter
    }
}