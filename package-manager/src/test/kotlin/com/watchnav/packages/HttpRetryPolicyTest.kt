package com.watchnav.packages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRetryPolicyTest {
    @Test
    fun `only throttling and temporary gateway failures are retried`() {
        assertTrue(HttpRetryPolicy.shouldRetry(429))
        assertTrue(HttpRetryPolicy.shouldRetry(503))
        assertFalse(HttpRetryPolicy.shouldRetry(400))
        assertFalse(HttpRetryPolicy.shouldRetry(404))
    }

    @Test
    fun `server retry-after wins over exponential delay`() {
        assertEquals(2_000L, HttpRetryPolicy.delayMillis(1))
        assertEquals(9_000L, HttpRetryPolicy.delayMillis(2, 9_000L))
        assertEquals(30_000L, HttpRetryPolicy.delayMillis(4, 90_000L))
        assertEquals(12_000L, HttpRetryPolicy.retryAfterMillis("12"))
    }
}
