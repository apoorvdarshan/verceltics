package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VercelFaviconLoaderTest {
    private var now = 1_000_000L

    @Test
    fun directAppleTouchIconWinsAndIsCached() = runTest {
        val transport = FakeTransport(
            "https://studio.example/apple-touch-icon.png" to image("touch"),
            "https://studio.example/favicon.ico" to image("ico"),
        )
        val loader = loader(transport)

        val first = loader.load("Studio.Example")
        val callsAfterFirst = transport.requested.size
        val second = loader.load("studio.example")

        assertTrue(first == "touch" || first == "ico")
        assertEquals(first, second)
        assertEquals("A cached favicon makes no new requests.", callsAfterFirst, transport.requested.size)
        assertEquals(first, loader.cached("studio.example"))
    }

    @Test
    fun faviconIcoIsUsedWhenTheTouchIconIsMissing() = runTest {
        val transport = FakeTransport(
            "https://studio.example/apple-touch-icon.png" to VercelFaviconResponse(404, "text/html", ByteArray(200)),
            "https://studio.example/favicon.ico" to image("ico"),
        )

        assertEquals("ico", loader(transport).load("studio.example"))
    }

    @Test
    fun homePageIconLinksAreTheFallback() = runTest {
        val transport = FakeTransport(
            "https://studio.example/" to VercelFaviconResponse(
                200,
                "text/html",
                """<link rel="icon" href="https://cdn.example/x.png"><link rel="icon" href="/brand/icon.png">""".encodeToByteArray(),
            ),
            "https://studio.example/brand/icon.png" to image("brand"),
        )

        assertEquals("brand", loader(transport).load("studio.example"))
        assertTrue(
            "Every request stays on the project's own origin.",
            transport.requested.all { it.host == "studio.example" && it.scheme == "https" },
        )
        assertTrue(transport.requested.none { it.host == "cdn.example" })
    }

    @Test
    fun svgTinyAndUndecodableResponsesAreRejected() = runTest {
        val transport = FakeTransport(
            "https://studio.example/apple-touch-icon.png" to VercelFaviconResponse(200, "image/svg+xml", ByteArray(400)),
            "https://studio.example/favicon.ico" to VercelFaviconResponse(200, "image/x-icon", ByteArray(20)),
            "https://studio.example/" to VercelFaviconResponse(200, "text/html", """<link rel="icon" href="/bad.png">""".encodeToByteArray()),
            "https://studio.example/bad.png" to VercelFaviconResponse(200, "image/png", "undecodable-but-long-enough-to-pass-the-size-check!".encodeToByteArray()),
        )

        assertNull(loader(transport, decode = { null }).load("studio.example"))
    }

    @Test
    fun failuresAreRememberedForFiveMinutes() = runTest {
        val transport = FakeTransport()
        val loader = loader(transport)

        assertNull(loader.load("studio.example"))
        val attempts = transport.requested.size
        assertTrue(attempts >= 3)
        assertTrue(loader.isKnownUnavailable("studio.example"))

        now += VercelFaviconPolicy.FAILURE_RETRY_MILLIS - 1
        assertNull(loader.load("studio.example"))
        assertEquals("No retry inside the failure window.", attempts, transport.requested.size)

        now += 1
        assertNull(loader.load("studio.example"))
        assertTrue("Retries once the window passes.", transport.requested.size > attempts)
    }

    @Test
    fun nonPublicDomainsNeverReachTheTransport() = runTest {
        val transport = FakeTransport()
        val loader = loader(transport)

        listOf("localhost", "10.0.0.1", "printer.local", "http://studio.example", "https://studio.example:8443").forEach {
            assertNull(loader.load(it))
        }
        assertTrue(transport.requested.isEmpty())
        assertTrue(loader.isKnownUnavailable(null))
    }

    @Test
    fun transportErrorsFallThroughToTheLetterTile() = runTest {
        val transport = FakeTransport(failure = IOException("connection reset"))

        assertNull(loader(transport).load("studio.example"))
    }

    @Test
    fun clearDropsImagesAndFailures() = runTest {
        val transport = FakeTransport("https://studio.example/apple-touch-icon.png" to image("touch"))
        val loader = loader(transport)
        loader.load("studio.example")
        assertNull(loader.load("broken.example"))

        loader.clear()

        assertNull(loader.cached("studio.example"))
        assertTrue(!loader.isKnownUnavailable("broken.example"))
    }

    @Test
    fun slowSitesTimeOutAndCancelTheirRequests() = runTest {
        val calls = mutableListOf<RecordingCall>()
        val transport = VercelFaviconTransport { uri, _, _, _ -> RecordingCall(uri).also(calls::add) }
        // An executor that never runs work models a site that never answers.
        val loader = VercelFaviconLoader(
            transport = transport,
            executor = Executor { },
            decode = { String(it) },
            nowMillis = { now },
            timeoutMillis = 8_000L,
        )

        assertNull(loader.load("slow.example"))

        assertTrue(calls.isNotEmpty())
        assertTrue("Timed-out requests are cancelled.", calls.all { it.cancelled })
        assertTrue(loader.isKnownUnavailable("slow.example"))
    }

    @Test
    fun cacheIsBoundedLeastRecentlyUsed() = runTest {
        val transport = FakeTransport(
            "https://a.example/apple-touch-icon.png" to image("a"),
            "https://b.example/apple-touch-icon.png" to image("b"),
            "https://c.example/apple-touch-icon.png" to image("c"),
        )
        val loader = loader(transport, cacheLimit = 2)

        loader.load("a.example")
        loader.load("b.example")
        loader.cached("a.example")
        loader.load("c.example")

        assertEquals("a", loader.cached("a.example"))
        assertNull("The least recently used entry is evicted.", loader.cached("b.example"))
        assertEquals("c", loader.cached("c.example"))
    }

    private fun loader(
        transport: VercelFaviconTransport,
        decode: (ByteArray) -> String? = { String(it).substringBefore('|') },
        cacheLimit: Int = VercelFaviconLoader.DEFAULT_CACHE_LIMIT,
    ) = VercelFaviconLoader(
        transport = transport,
        executor = Executor(Runnable::run),
        decode = decode,
        nowMillis = { now },
        cacheLimit = cacheLimit,
    )

    private fun image(name: String) = VercelFaviconResponse(200, "image/png", "$name|${"x".repeat(80)}".encodeToByteArray())

    private class FakeTransport(
        vararg responses: Pair<String, VercelFaviconResponse>,
        private val failure: Exception? = null,
    ) : VercelFaviconTransport {
        private val responses = responses.toMap()
        val requested = mutableListOf<URI>()

        override fun newCall(uri: URI, host: String, accept: String, maximumBytes: Int): CancelableCall<VercelFaviconResponse> {
            synchronized(requested) { requested += uri }
            return object : CancelableCall<VercelFaviconResponse> {
                override fun execute(): VercelFaviconResponse {
                    failure?.let { throw it }
                    return responses[uri.toString()] ?: VercelFaviconResponse(404, "text/html", ByteArray(0))
                }

                override fun cancel() = Unit
            }
        }
    }

    private class RecordingCall(val uri: URI) : CancelableCall<VercelFaviconResponse> {
        @Volatile var cancelled = false

        override fun execute(): VercelFaviconResponse = error("Never executed.")

        override fun cancel() {
            cancelled = true
        }
    }
}
