package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory
import ru.quipy.PaymentMetrics
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val paymentMetrics: PaymentMetrics
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

    private val maxRetries = 10

    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .build()
    private val rateLimiter = RateLimiter.of("rate-limiter", RateLimiterConfig.custom()
        .limitForPeriod(rateLimitPerSec)
        .limitRefreshPeriod(Duration.ofMillis(1000))
        .build())
    private val ongoingWindow = OngoingWindow(parallelRequests)

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
        val startTime = System.currentTimeMillis();
        logger.warn("[$accountName] Submitting payment request for payment $paymentId, avgProcessingTime: $requestAverageProcessingTime")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val paymentTimeout = 20L

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
        repeat(maxRetries) { attempt ->
            if (System.currentTimeMillis() - startTime >= paymentTimeout) {
                logger.warn("[$accountName] Deadline approaching, stopping retries for $paymentId")
                return
            }

            try {
                ongoingWindow.acquireAsync();
                rateLimiter.tickBlockingAsync();
                val request = HttpRequest.newBuilder()
                    .uri(URI("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(paymentTimeout))
                    .build()

                val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}, reason: ${response.body()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }

                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, code: ${response.statusCode()}, attempt: $attempt")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }

                if (body.result) {
                    recordPaymentAttempt(attempt)
                    return
                }
            } catch (e: Exception) {
                logger.error("[$accountName] Payment failed for $paymentId", e)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = e.message)
                }
            } finally {
                ongoingWindow.release()
            }

            delay(100L * (attempt + 1))
        }
        val duration = System.currentTimeMillis() - startTime;
        paymentMetrics.paymentOperationDurationTimer.record(duration, TimeUnit.MILLISECONDS);
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled
    override fun getAccountProperties(): PaymentAccountProperties = properties

    override fun name() = properties.accountName
}

public fun now() = System.currentTimeMillis()