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
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
        LinkedBlockingQueue(8_000),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

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
        val createdEvent = paymentESService.create {
            it.create(
                paymentId,
                orderId,
                amount
            )
        }
        logger.trace("Payment ${createdEvent.paymentId} for order $orderId created.")

        paymentExecutor.submit { // Не факт, что поток возьмет сразу. Видимо, в этом и есть проблема
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            val duration = System.currentTimeMillis() - createdAt;
            paymentMetrics.paymentTotalDurationTimer.record(duration, TimeUnit.MILLISECONDS);
        } // Нужно либо увеличить кол-во потоков/ оптимизировать процессы внутри текущей лямбды или всего этого метода
        return createdAt
    }
}