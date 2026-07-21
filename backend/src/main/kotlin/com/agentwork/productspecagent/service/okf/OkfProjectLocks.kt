package com.agentwork.productspecagent.service.okf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object OkfProjectLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(projectId: String, block: () -> T): T =
        locks.computeIfAbsent(projectId) { ReentrantLock() }.withLock(block)
}
