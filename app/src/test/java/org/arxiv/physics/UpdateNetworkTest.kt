package org.arxiv.physics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class UpdateNetworkTest {
    @Test fun stalledOriginDoesNotPreventBackupAndHasTotalDeadline() = runBlocking {
        val blocked = MockWebServer(); val backup = MockWebServer()
        try {
            blocked.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            backup.enqueue(MockResponse().setBody("backup manifest"))
            blocked.start(); backup.start()
            val network = UpdateNetwork(350)
            val start = System.nanoTime()
            val values = listOf(blocked, backup).map { server -> async {
                try { network.manifest(server.url("/update.json").toString()) } catch (_: Exception) { null }
            } }.awaitAll()
            assertEquals(listOf(null, "backup manifest"), values)
            assertTrue("Stalled call must end", (System.nanoTime() - start) / 1_000_000 < 3000)
        } finally { blocked.shutdown(); backup.shutdown() }
    }
    @Test fun oversizedMetadataIsRejected() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1))); server.start()
            assertTrue(runCatching { UpdateNetwork().manifest(server.url("/").toString()) }.isFailure)
        } finally { server.shutdown() }
    }
    @Test fun staleMirrorCannotOverrideNewestAndConflictsAreRejected() {
        fun release(code: Long, hash: String = "a".repeat(64), mirror: String = "") =
            AppRelease(code, "0.5.$code", "app.apk", "https://github.com/example", hash, 1000, "", mirror)
        val stale = release(5, mirror = "https://mirror.example/app.apk")
        val latest = release(6)
        assertEquals(latest, UpdatePolicy.select(listOf(stale, latest)))
        val sameMirror = latest.copy(mirrorUrl = "https://mirror.example/app.apk")
        assertEquals(sameMirror, UpdatePolicy.select(listOf(latest, sameMirror)))
        assertTrue(runCatching { UpdatePolicy.select(listOf(latest, release(6, "b".repeat(64)))) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.select(emptyList()) }.isFailure)
    }
    @Test fun resumeRequiresExactContentRangeAndRestartsIfRangeIgnored() {
        assertEquals(100L, UpdatePolicy.resumeOffset(206, "bytes 100-999/1000", 100, 1000))
        assertEquals(0L, UpdatePolicy.resumeOffset(200, null, 100, 1000))
        assertTrue(runCatching { UpdatePolicy.resumeOffset(206, "bytes 0-999/1000", 100, 1000) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.resumeOffset(206, "bytes 100-999/9999", 100, 1000) }.isFailure)
        assertFalse(UpdatePolicy.httpsUrl("http://example.com/update.json"))
        assertFalse(UpdatePolicy.httpsUrl("https://user:password@example.com/update.json"))
        assertTrue(UpdatePolicy.httpsUrl("https://example.com/app/update.json"))
    }
}
