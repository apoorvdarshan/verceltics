package com.apoorvdarshan.verceltics.ui.pagespeed

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedApi
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedConnectionRepository
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedConnectionStore
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCredentials
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedHttpTransport
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedJsonParser
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetric
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedRestoreResult
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedStrategy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePageSpeedUiGatewayTest {
    private val files = BlockingFiles()

    @Test
    fun cancellingDuringUninterruptiblePersistenceRollsBackTheExactSavedRevision() = runBlocking {
        val repository = PageSpeedConnectionRepository(files::store, TestAccountCipher())
        val connectionStore = PageSpeedConnectionStore(repository, nowMillis = { 42L })
        val executor = Executors.newSingleThreadExecutor()
        val gateway = NativePageSpeedUiGateway(
            connectionStore = connectionStore,
            api = PageSpeedApi(SuccessTransport(), SuccessParser(), nowMillis = { 42L }),
            executor = executor,
        )

        try {
            cancelDuringBlockedWrite(gateway, executor, "cancelled-key")

            assertEquals(PageSpeedRestoreResult.NotConnected, connectionStore.restore())
            assertTrue(files.savedPaths().isEmpty())
        } finally {
            files.releaseBlockedWrite()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledAdditionRestoresTheExactPreviousSitesAndActiveSite() = runBlocking {
        val repository = PageSpeedConnectionRepository(files::store, TestAccountCipher())
        val connectionStore = PageSpeedConnectionStore(repository, nowMillis = { 42L })
        val executor = Executors.newSingleThreadExecutor()
        val api = PageSpeedApi(SuccessTransport(), SuccessParser(), nowMillis = { 42L })
        val gateway = NativePageSpeedUiGateway(connectionStore, api, executor)
        val originalCredentials = PageSpeedCredentials.create("original-key", "https://example.com/original")
        val originalCommit = connectionStore.saveValidatedConnection(originalCredentials, api.newSnapshotCall(originalCredentials).execute())
        connectionStore.acceptValidatedConnection(originalCommit)
        val originalRecord = checkNotNull(repository.load(originalCommit.connectionId))
        val before = files.snapshot()

        try {
            cancelDuringBlockedWrite(gateway, executor, apiKey = "replacement-key", siteUrl = "https://example.com/replacement")

            assertEquals(before.keys, files.snapshot().keys)
            before.forEach { (path, bytes) -> assertArrayEquals(bytes, files.snapshot()[path]) }
            val restored = connectionStore.restore() as PageSpeedRestoreResult.Restored
            assertEquals(originalRecord.id, restored.connectionId)
            assertEquals(originalRecord.credentials.siteUrl, restored.siteUrl)
            assertEquals(listOf(originalRecord.id), restored.accounts.ids)
            assertEquals(originalRecord.credentials.apiKey, repository.load(originalRecord.id)?.credentials?.apiKey)
        } finally {
            files.releaseBlockedWrite()
            executor.shutdownNow()
        }
    }

    @Test
    fun severalSitesConnectSwitchRefreshAndRemoveThroughTheGateway() = runBlocking {
        val repository = PageSpeedConnectionRepository(files::store, TestAccountCipher())
        val connectionStore = PageSpeedConnectionStore(repository, nowMillis = { 42L })
        val executor = Executors.newSingleThreadExecutor()
        val gateway = NativePageSpeedUiGateway(
            connectionStore,
            PageSpeedApi(SuccessTransport(), SuccessParser(), nowMillis = { 42L }),
            executor,
        )
        try {
            val first = gateway.connect(SecretValue.of("key-1"), "https://one.example").getOrThrow()
            val second = gateway.connect(SecretValue.of("key-2"), "https://two.example").getOrThrow()
            assertEquals(listOf(first.accountId, second.accountId), gateway.accounts().getOrThrow().accounts.map { it.id })
            assertEquals(second.accountId, gateway.accounts().getOrThrow().activeAccountId)
            assertEquals("one.example", gateway.accounts().getOrThrow().accounts.first().title)

            // Re-entering a saved URL rotates its key without adding a site.
            val rotated = gateway.connect(SecretValue.of("key-1b"), "https://ONE.example").getOrThrow()
            assertEquals(first.accountId, rotated.accountId)
            assertEquals(2, gateway.accounts().getOrThrow().accounts.size)
            assertEquals("key-1b", repository.load(first.accountId!!)?.credentials?.apiKey?.use { it })

            val switched = gateway.switchAccount(second.accountId!!).getOrThrow() as PageSpeedRestoreUi.Available
            assertEquals("https://two.example", switched.dashboard.siteUrl)
            assertEquals(second.accountId, switched.dashboard.accountId)
            assertEquals(second.accountId, gateway.refresh().getOrThrow().accountId)

            val next = gateway.removeAccount(second.accountId!!).getOrThrow() as PageSpeedRestoreUi.Available
            assertEquals(first.accountId, next.dashboard.accountId)
            gateway.removeAllAccounts().getOrThrow()
            assertEquals(PageSpeedRestoreUi.NotConnected, gateway.restore().getOrThrow())
            assertTrue(gateway.switchAccount("missing").isFailure)
        } finally {
            executor.shutdownNow()
        }
    }

    private suspend fun cancelDuringBlockedWrite(
        gateway: NativePageSpeedUiGateway,
        executor: ExecutorService,
        apiKey: String,
        siteUrl: String = "https://example.com",
    ) = coroutineScope {
        files.blockNextWrite()
        val connectJob = async(Dispatchers.Default) {
            gateway.connect(SecretValue.of(apiKey), siteUrl)
        }
        assertTrue(files.awaitWriteStarted())

        connectJob.cancelAndJoin()
        files.releaseBlockedWrite()
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** In-memory files whose next write (to any path) can be held mid-flight. */
    private class BlockingFiles {
        private val blockLock = Any()
        private var shouldBlockNextWrite = false
        private var writeStarted = CountDownLatch(0)
        private var allowWriteToFinish = CountDownLatch(0)
        private val contents = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        fun store(path: String): AtomicBytesStore = object : AtomicBytesStore {
            override fun read(): ByteArray? = contents[path]?.copyOf()

            override fun write(bytes: ByteArray) {
                val blockedWrite = synchronized(blockLock) {
                    if (!shouldBlockNextWrite) {
                        null
                    } else {
                        shouldBlockNextWrite = false
                        writeStarted to allowWriteToFinish
                    }
                }
                blockedWrite?.let { (started, finish) ->
                    started.countDown()
                    awaitIgnoringInterrupt(finish)
                }
                contents[path] = bytes.copyOf()
            }

            override fun delete() {
                contents.remove(path)
            }
        }

        fun blockNextWrite() = synchronized(blockLock) {
            check(!shouldBlockNextWrite) { "A blocked write is already armed." }
            writeStarted = CountDownLatch(1)
            allowWriteToFinish = CountDownLatch(1)
            shouldBlockNextWrite = true
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(5, TimeUnit.SECONDS)

        fun releaseBlockedWrite() = allowWriteToFinish.countDown()

        fun snapshot(): Map<String, ByteArray> = contents.mapValues { it.value.copyOf() }

        fun savedPaths(): Set<String> = contents.keys.toSet()

        private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    // Atomic file replacement may be past the point where cancellation can stop it.
                }
            }
        }
    }

    private class SuccessTransport : PageSpeedHttpTransport {
        override fun newInsightsCall(
            credentials: PageSpeedCredentials,
            strategy: PageSpeedStrategy,
        ): CancelableCall<HttpResponse> = FixedCall()

        override fun newCruxCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> =
            FixedCall()

        override fun newCruxHistoryCall(credentials: PageSpeedCredentials): CancelableCall<HttpResponse> =
            FixedCall()
    }

    private class FixedCall : CancelableCall<HttpResponse> {
        override fun execute(): HttpResponse = HttpResponse(200, byteArrayOf(1), emptyMap())

        override fun cancel() = Unit
    }

    private class SuccessParser : PageSpeedJsonParser {
        override fun parseInsights(
            bytes: ByteArray,
            strategy: PageSpeedStrategy,
        ): List<PageSpeedMetric> = listOf(
            PageSpeedMetric(
                key = "pagespeed.${strategy.wireValue}.performance",
                label = "${strategy.label} Performance",
                value = 96.0,
                unit = PageSpeedMetricUnit.SCORE,
            ),
        )

        override fun parseCrux(bytes: ByteArray): List<PageSpeedMetric> = listOf(
            PageSpeedMetric(
                key = "crux.largest_contentful_paint",
                label = "LCP (Page field p75)",
                value = 1_500.0,
                unit = PageSpeedMetricUnit.MILLISECONDS,
            ),
        )
    }

    private class TestAccountCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
            SealedPayload(ByteArray(12) { 5 }, transform(plaintext, associatedData))

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
            transform(payload.ciphertext(), associatedData)

        private fun transform(input: ByteArray, associatedData: ByteArray): ByteArray =
            ByteArray(input.size) { index ->
                (input[index].toInt() xor associatedData[index % associatedData.size].toInt()).toByte()
            }
    }
}
