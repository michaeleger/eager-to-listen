package com.eagertolisten.app.service

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class LiveSessionManagerTest {

    private fun createFakePrefs(isLive: Boolean, lastTime: Long): SharedPreferences {
        val map = mapOf<String, Any>(
            "last_is_live" to isLive,
            "last_listening_time" to lastTime
        )
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getBoolean" -> map[args[0]] as? Boolean ?: args[1]
                    "getLong" -> map[args[0]] as? Long ?: args[1]
                    else -> null
                }
            }
        ) as SharedPreferences
    }

    @Test
    fun isLiveSessionExpired_falseBefore90Minutes_trueAt90Minutes() {
        val heartbeatTime = 1_000_000L
        val prefs = createFakePrefs(isLive = true, lastTime = heartbeatTime)

        // 89 minutes 59 seconds = 5399 seconds = 5,399,000 ms
        val at89m59s = heartbeatTime + (89L * 60L + 59L) * 1000L
        assertFalse(
            "Session must not expire at 89 min 59 s",
            LiveSessionManager.isLiveSessionExpired(prefs, currentTimeMs = at89m59s)
        )

        // Exactly 90 minutes = 90 * 60 * 1000 = 5,400,000 ms
        val at90m = heartbeatTime + (90L * 60L * 1000L)
        assertTrue(
            "Session must expire at 90 min",
            LiveSessionManager.isLiveSessionExpired(prefs, currentTimeMs = at90m)
        )
    }

    @Test
    fun isLiveSessionExpired_returnsFalseIfNotLive() {
        val heartbeatTime = 1_000_000L
        val prefs = createFakePrefs(isLive = false, lastTime = heartbeatTime)
        val at120m = heartbeatTime + (120L * 60L * 1000L)

        assertFalse(
            "Non-live sessions must never expire via LiveSessionManager",
            LiveSessionManager.isLiveSessionExpired(prefs, currentTimeMs = at120m)
        )
    }
}
