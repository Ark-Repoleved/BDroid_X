package com.example.bd2modmanager.service

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe occupancy token tracker (ported from BD2ToolsBox).
 * Prevents Activity destruction from unbinding Shizuku while background tasks
 * (e.g. InstallService) still hold active tokens.
 */
internal class ConnectionOccupancy(private val onAllReleased: () -> Unit) {

    private val activeTokens: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap())

    val activeCount: Int get() = activeTokens.size

    fun acquire(): Any {
        val token = Any()
        activeTokens.add(token)
        return token
    }

    fun release(token: Any) {
        val actuallyRemoved = activeTokens.remove(token)
        if (actuallyRemoved && activeTokens.isEmpty()) {
            onAllReleased()
        }
    }
}
