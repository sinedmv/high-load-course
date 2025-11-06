package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.PaymentMetrics
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit


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

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val timeoutMultiplier = 1.2

    private val maxRetries = 4

    private val client = OkHttpClient.Builder()
        .connectTimeout(requestAverageProcessingTime.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(requestAverageProcessingTime.toMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(requestAverageProcessingTime.toMillis(), TimeUnit.MILLISECONDS)
        .callTimeout((requestAverageProcessingTime.toMillis() * timeoutMultiplier).toLong(), TimeUnit.MILLISECONDS)
        .build()
    private val rateLimiter = SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1));
    private val ongoingWindow = OngoingWindow(parallelRequests)

    private val requestLatency = Timer.builder("request_latency")
        .description("Request latency in seconds")
        .publishPercentiles(0.5, 0.8, 0.99)
        .register(Metrics.globalRegistry)

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val startTime = System.currentTimeMillis();
        logger.warn("[$accountName] Submitting payment request for payment $paymentId, avgProcessingTime: $requestAverageProcessingTime")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")
        repeat(maxRetries) { attempt ->
            var isSuccess = false
            try {
                ongoingWindow.acquire();
                rateLimiter.tickBlocking();
                val request = Request.Builder().run {
                    url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                    post(emptyBody)
                }.build()

                val requestStartTime = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    val durationMillis = response.receivedResponseAtMillis - requestStartTime
                    requestLatency
                        .record(durationMillis, TimeUnit.MILLISECONDS)

                    val body = try {
                        mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, code: ${response.code}, attempt: $attempt")

                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }

                    if (body.result) {
                        isSuccess = true
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is SocketTimeoutException -> {
                        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                        }
                    }

                    else -> {
                        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = e.message)
                        }
                    }
                }
            } finally {
                val duration = System.currentTimeMillis() - startTime;
                paymentMetrics.paymentOperationDurationTimer.record(duration, TimeUnit.MILLISECONDS);
                ongoingWindow.release();
            }

            if (isSuccess) {
                return
            } else {
                Thread.sleep(100 * (attempt.toLong() + 1)) // 0.1s 0.2s ...
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled
    override fun getAccountProperties(): PaymentAccountProperties = properties

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()