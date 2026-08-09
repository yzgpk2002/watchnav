package com.watchnav.packages

import java.io.IOException
import kotlin.math.max

internal class RetryableHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
) : IOException("HTTP $statusCode")

internal object HttpRetryPolicy {
    const val MAXIMUM_ATTEMPTS = 4

    fun shouldRetry(statusCode: Int): Boolean =
        statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504

    fun delayMillis(attempt: Int, retryAfterMillis: Long? = null): Long {
        require(attempt >= 1)
        val exponential = (2_000L shl (attempt - 1).coerceAtMost(3)).coerceAtMost(15_000L)
        return max(exponential, retryAfterMillis ?: 0L).coerceAtMost(30_000L)
    }

    fun retryAfterMillis(rawValue: String?): Long? = rawValue
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.times(1_000L)
}
