package com.nixplorer.screen.main.tab.apps.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Shares one load across tabs; a package change during loading cannot publish stale data. */
internal class InvalidatingCache<T : Any> {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var loadedGeneration = -1L
    private var loadedKey: String? = null
    private var value: T? = null

    fun invalidate() { generation.incrementAndGet() }

    suspend fun get(key: String, load: suspend () -> T): T = mutex.withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = generation.get()
            value?.takeIf { loadedGeneration == revision && loadedKey == key }?.let { return@withLock it }
            val loaded = load()
            currentCoroutineContext().ensureActive()
            if (generation.get() == revision) {
                value = loaded
                loadedGeneration = revision
                loadedKey = key
                return@withLock loaded
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
}
