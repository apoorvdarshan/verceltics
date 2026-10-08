package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.account.SecretValue
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
class VercelAvatarsTest {
    private var now = 1_000_000L

    @Test
    fun avatarHashesBecomeTheVercelAvatarEndpointLikeIos() {
        assertEquals(
            "https://api.vercel.com/www/avatar/0123abcdef",
            VercelAvatarPolicy.avatarUrl("0123abcdef"),
        )
        assertEquals(
            "https://vercel.com/api/www/avatar/abc?s=64",
            VercelAvatarPolicy.avatarUrl("https://vercel.com/api/www/avatar/abc?s=64"),
        )
        assertNull(VercelAvatarPolicy.avatarUrl(null))
        assertNull(VercelAvatarPolicy.avatarUrl("  "))
    }

    @Test
    fun onlyHttpsVercelHostsAreAllowed() {
        listOf(
            "http://vercel.com/avatar.png",
            "https://evil.example/avatar.png",
            "https://vercel.com.evil.example/avatar.png",
            "https://user:pass@vercel.com/avatar.png",
            "https://vercel.com:8443/avatar.png",
            "https://my-app.vercel.app/avatar.png",
            "../../etc/passwd",
            "has spaces",
        ).forEach { candidate ->
            assertNull(candidate, VercelAvatarPolicy.avatarUrl(candidate))
            assertNull(candidate, VercelAvatarPolicy.storableAvatar(candidate))
        }
        assertEquals("abc", VercelAvatarPolicy.storableAvatar(" abc "))
    }

    @Test
    fun validatedUsersSaveTheirAvatar() {
        val user = VercelUser(id = "user_1", username = "apoorv", email = null, name = " ", avatarUrl = "f00d")

        val account = VercelApi().accountForValidatedUser(user, SecretValue.of("token"), nowMillis = 1L)

        assertEquals("f00d", account.avatar)
        assertEquals("A blank name falls back to the username.", "apoorv", account.displayName)
    }

    @Test
    fun avatarsAreFetchedOnceWithoutCredentialsAndCached() = runTest {
        val url = "https://api.vercel.com/www/avatar/abc"
        val transport = FakeTransport(url to VercelFaviconResponse(200, "image/png", "avatar".encodeToByteArray()))
        val loader = loader(transport)

        assertEquals("avatar", loader.load(url))
        assertEquals("avatar", loader.load(url))

        assertEquals("A cached avatar makes no second request.", 1, transport.requested.size)
        assertEquals("api.vercel.com", transport.hosts.single())
        assertEquals("avatar", loader.cached(url))
    }

    @Test
    fun disallowedUrlsNeverReachTheTransport() = runTest {
        val transport = FakeTransport()
        val loader = loader(transport)

        listOf("https://evil.example/a.png", "http://vercel.com/a.png", "not a url").forEach {
            assertNull(loader.load(it))
        }
        assertTrue(transport.requested.isEmpty())
    }

    @Test
    fun failuresAreRememberedForFiveMinutesAndClearResetsThem() = runTest {
        val url = "https://api.vercel.com/www/avatar/missing"
        val transport = FakeTransport(failure = IOException("offline"))
        val loader = loader(transport)

        assertNull(loader.load(url))
        assertNull(loader.load(url))
        assertEquals(1, transport.requested.size)

        now += VercelAvatarPolicy.FAILURE_RETRY_MILLIS
        assertNull(loader.load(url))
        assertEquals(2, transport.requested.size)

        loader.clear()
        assertNull(loader.load(url))
        assertEquals("Clearing forgets the failure.", 3, transport.requested.size)
    }

    @Test
    fun svgAndErrorResponsesAreRejected() = runTest {
        val svg = "https://api.vercel.com/www/avatar/svg"
        val missing = "https://api.vercel.com/www/avatar/missing"
        val transport = FakeTransport(
            svg to VercelFaviconResponse(200, "image/svg+xml", "<svg/>".encodeToByteArray()),
            missing to VercelFaviconResponse(404, "application/json", "{}".encodeToByteArray()),
        )
        val loader = loader(transport)

        assertNull(loader.load(svg))
        assertNull(loader.load(missing))
    }

    private fun loader(transport: VercelFaviconTransport) = VercelAvatarLoader(
        transport = transport,
        executor = Executor(Runnable::run),
        decode = { String(it) },
        nowMillis = { now },
    )

    private class FakeTransport(
        vararg responses: Pair<String, VercelFaviconResponse>,
        private val failure: Exception? = null,
    ) : VercelFaviconTransport {
        private val responses = responses.toMap()
        val requested = mutableListOf<URI>()
        val hosts = mutableListOf<String>()

        override fun newCall(uri: URI, host: String, accept: String, maximumBytes: Int): CancelableCall<VercelFaviconResponse> {
            requested += uri
            hosts += host
            return object : CancelableCall<VercelFaviconResponse> {
                override fun execute(): VercelFaviconResponse {
                    failure?.let { throw it }
                    return responses[uri.toString()] ?: VercelFaviconResponse(404, "text/html", ByteArray(0))
                }

                override fun cancel() = Unit
            }
        }
    }
}
