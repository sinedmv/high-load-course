package ru.quipy.common.utils.exceptions

class TooManyRequestsWithRetryAfterException : Exception {
    private val retryAfter: Int
    constructor(retryAfter: Int) : super() {
        this.retryAfter = retryAfter
    }

    constructor(message: String, retryAfter: Int) : super(message) {
        this.retryAfter = retryAfter
    }

    constructor(message: String, cause: Throwable, retryAfter: Int) : super(message, cause) {
        this.retryAfter = retryAfter
    }

    constructor(cause: Throwable, retryAfter: Int) : super(cause) {
        this.retryAfter = retryAfter
    }

    fun getRetryAfter(): Int = this.retryAfter
}
