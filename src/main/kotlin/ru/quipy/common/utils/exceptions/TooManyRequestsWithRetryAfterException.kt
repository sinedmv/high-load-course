package ru.quipy.common.utils.exceptions

class TooManyRequestsWithRetryAfterException : Exception {
    private val retryAfter: Long
    constructor() : super() {
        retryAfter = 60000
    }
    constructor(retryAfter: Long) : super() {
        this.retryAfter = retryAfter
    }

    constructor(message: String, retryAfter: Long) : super(message) {
        this.retryAfter = retryAfter
    }

    constructor(message: String, cause: Throwable, retryAfter: Long) : super(message, cause) {
        this.retryAfter = retryAfter
    }

    constructor(cause: Throwable, retryAfter: Long) : super(cause) {
        this.retryAfter = retryAfter
    }

    fun getRetryAfter(): Long = this.retryAfter
}
