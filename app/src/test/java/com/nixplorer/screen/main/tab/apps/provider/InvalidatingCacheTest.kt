package com.nixplorer.screen.main.tab.apps.provider

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InvalidatingCacheTest {
    @Test fun repeatedOpensReuseDataAndChangesReload() = runBlocking {
        val cache = InvalidatingCache<List<String>>()
        var loads = 0
        suspend fun read(locale: String = "en") = cache.get(locale) { loads++; listOf("app-$loads") }
        assertEquals(listOf("app-1"), read())
        repeat(10) { assertEquals(listOf("app-1"), read()) }
        assertEquals(1, loads)
        cache.invalidate()
        assertEquals(listOf("app-2"), read())
        assertEquals(listOf("app-3"), read("fr"))
    }

    @Test fun simultaneousTabsShareOneLoad() = runBlocking {
        val cache = InvalidatingCache<String>()
        val loads = AtomicInteger()
        val values = (1..20).map { async(Dispatchers.Default) {
            cache.get("en") { loads.incrementAndGet(); delay(20); "apps" }
        } }.awaitAll()
        assertEquals(1, loads.get())
        assertTrue(values.all { it == "apps" })
    }

    @Test fun packageChangeDuringLoadCannotCacheStaleResult() = runBlocking {
        val cache = InvalidatingCache<String>()
        var loads = 0
        val result = cache.get("en") {
            loads++
            if (loads == 1) cache.invalidate()
            "revision-$loads"
        }
        assertEquals("revision-2", result)
        assertEquals("revision-2", cache.get("en") { error("Should be cached") })
    }

    @Test fun cancellationAndFailureAllowRetry() = runBlocking {
        val cache = InvalidatingCache<String>()
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.get("en") { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        try { cache.get("en") { throw IllegalStateException("query failed") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals("recovered", cache.get("en") { "recovered" })
    }
}
