package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import okhttp3.internal.wait
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory
import ru.quipy.PaymentMetrics
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.Scope
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl.Companion.logger
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val paymentMetrics: PaymentMetrics,
    private val scope: Scope,
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val maxRetries = 1

    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .build()
//    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1));
    private val ongoingWindow = OngoingWindow(parallelRequests)
    private val rateLimiter: RateLimiter = RateLimiter.of(
        "payments-$accountName",
        RateLimiterConfig.custom()
            .limitForPeriod(rateLimitPerSec)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(1000))
            .build()
    )

    fun recordPaymentAttempt(attempts: Int) {
        val label = when (attempts) {
            0 -> "1"
            1 -> "2"
            2 -> "3"
            3 -> "4"
            else -> "4+"
        }

        Counter.builder("payment_attempts_by_count")
            .description("Количество платежей по числу попыток")
            .tag("attempts", label)
            .register(Metrics.globalRegistry)
            .increment()
    }

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        paymentMetrics.performPaymentStartedCounter.increment()
        val startTime = System.currentTimeMillis();
        logger.warn("[$accountName] Submitting payment request for payment $paymentId, avgProcessingTime: $requestAverageProcessingTime")

        val transactionId = UUID.randomUUID()

        scope.esWriter.submit(paymentId) {
            // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
            // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }
            paymentMetrics.submissionLogged.increment()
        }

        val paymentTimeout = 1000L

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
        repeat(maxRetries) { attempt ->
            try {
                ongoingWindow.acquireAsync()
                paymentMetrics.windowAcquired.increment()
                RateLimiter.waitForPermission(rateLimiter);
                if (System.currentTimeMillis() - startTime >= paymentTimeout) {
                    logger.warn("[$accountName] Deadline approaching, stopping retries for $paymentId")
                    return
                }

                paymentMetrics.rateLimiterAcquired.increment()
                val request = HttpRequest.newBuilder()
                    .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(paymentTimeout))
                    .build()

                val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                paymentMetrics.responseReceived.increment()

                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}, reason: ${response.body()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }
                paymentMetrics.bodyRead.increment()

                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, code: ${response.statusCode()}, attempt: $attempt")

                scope.esWriter.submit(paymentId) {
                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                    paymentMetrics.processingLogged.increment()
                }

                if (body.result) {
                    recordPaymentAttempt(attempt)
                    return
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Payment failed for $paymentId", e)
                scope.esWriter.submit(paymentId) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                    paymentMetrics.processingLogged.increment()
                }
            } finally {
                ongoingWindow.release()
                val duration = System.currentTimeMillis() - startTime;
                paymentMetrics.paymentOperationDurationTimer.record(duration, TimeUnit.MILLISECONDS);
            }

            //delay(100L * (attempt + 1))
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled
    override fun getAccountProperties(): PaymentAccountProperties = properties

    override fun name() = properties.accountName
}

public fun now() = System.currentTimeMillis()

data class EsWrite(
    val key: UUID,
    val action: suspend () -> Unit
)

class OrderedEsWriter(
    scope: CoroutineScope,
    shards: Int = 400,
    queueSizePerShard: Int = 20000
) {
    private val channels = Array(shards) { Channel<EsWrite>(queueSizePerShard) }

    init {
        repeat(shards) { i ->
            scope.launch(Dispatchers.IO) {
                for (job in channels[i]) {
                    try {
                        job.action()
                    } catch (e: Exception) {
                        logger.error("[ERROR] Database sending error: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun submit(key: UUID, action: suspend () -> Unit) {
        val idx = shard(key)
        channels[idx].send(EsWrite(key, action))
    }

    private fun shard(key: UUID): Int {
        val h = key.mostSignificantBits xor key.leastSignificantBits
        return (abs(h.toInt()) % channels.size)
    }
}