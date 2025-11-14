package ru.quipy.tools

import org.slf4j.LoggerFactory
import ru.quipy.payments.logic.PaymentExternalSystemAdapter
import kotlin.reflect.KClass

object RetryExecutor {
    fun <T> executeWithRetries(
        maxRetries: Int,
        initialDelayMs: Long,
        vararg nonRetriableExceptions: KClass<out Exception>,
        operation: (attempt: Int) -> T
    ): T {
        for (attempt in 0 until maxRetries) {
            try {
                return operation(attempt + 1)
            } catch (e: Exception) {
                val isNonRetriable = nonRetriableExceptions.any { it.isInstance(e) }
                if (isNonRetriable) {
                    throw e
                } else {
                    if (attempt < maxRetries - 1) {
                        val delay = initialDelayMs * (attempt + 1)
                        Thread.sleep(delay)
                    }
                }
            }
        }

        throw Exception("Operation failed after $maxRetries attempts.")
    }
}