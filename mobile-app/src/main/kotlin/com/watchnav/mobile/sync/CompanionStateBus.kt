package com.watchnav.mobile.sync

import java.util.concurrent.CopyOnWriteArraySet

object CompanionStateBus {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun add(listener: () -> Unit) { listeners += listener }
    fun remove(listener: () -> Unit) { listeners -= listener }
    fun emit() { listeners.forEach { it.invoke() } }
}
