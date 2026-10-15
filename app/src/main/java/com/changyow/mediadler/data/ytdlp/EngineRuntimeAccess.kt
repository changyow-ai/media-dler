package com.changyow.mediadler.data.ytdlp

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Downloads may share the runtime, but its archive must not be replaced while in use. */
internal class EngineRuntimeAccess {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> execute(block: () -> T): T = locked(lock.readLock(), block)

    fun <T> update(block: () -> T): T = locked(lock.writeLock(), block)

    private fun <T> locked(lock: Lock, block: () -> T): T {
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
