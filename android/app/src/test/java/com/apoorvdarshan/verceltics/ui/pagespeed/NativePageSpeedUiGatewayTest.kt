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
    @Test
    fun cancellingDuringUninterruptiblePersistenceRollsBackTheExactSavedRevision() = runBlocking {
        val atomicStore = BlockingAtomicBytesStore()
        val repository = PageSpeedConnectionRepository(atomicStore, TestAccountCipher())
        val connectionStore = PageSpeedConnectionStore(repository, nowMillis = { 42L })
        val executor = Executors.newSingleThreadExecutor()
        val gateway = NativePageSpeedUiGateway(
            connectionStore = connectionStore,
            api = PageSpeedApi(SuccessTransport(), SuccessParser(), nowMillis = { 42L }),
            executor = executor,
        )

        try {
            cancelDuringBlockedWrite(gateway, atomicStore, executor, "cancelled-key")

            assertNull(repository.load())
        } finally {
            atomicStore.releaseBlockedWrite()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledReplacementRestoresTheExactPreviousEncryptedConnection() = runBlocking {
        val atomicStore = BlockingAtomicBytesStore()
        val repository = PageSpeedConnectionRepository(atomicStore, TestAccountCipher())
        val connectionStore = PageSpeedConnectionStore(repository, nowMillis = { 42L })
        val executor = Executors.newSingleThreadExecutor()
        val api = PageSpeedApi(SuccessTransport(), SuccessParser(), nowMillis = { 42L })
        val gateway = NativePageSpeedUiGateway(connectionStore, api, executor)
        val originalCredentials = PageSpeedCredentials.create(
            "original-key",
            "https://example.com/original",
        )
        val originalResult = api.newSnapshotCall(originalCredentials).execute()
        val originalCommit = connectionStore.saveValidatedConnection(
            originalCredentials,
            originalResult,
        )
        connectionStore.acceptValidatedConnection(originalCommit)
        val originalRecord = checkNotNull(repository.load())
        val originalEnvelope = checkNotNull(atomicStore.snapshotBytes())

        try {
            cancelDuringBlockedWrite(
                gateway,
                atomicStore,
                executor,
                apiKey = "replacement-key",
                siteUrl = "https://example.com/replacement",
            )

            assertArrayEquals(originalEnvelope, atomicStore.snapshotBytes())
            val restored = checkNotNull(repository.load())
            assertEquals(originalRecord.id, restored.id)
            assertEquals(originalRecord.credentials.siteUrl, restored.credentials.siteUrl)
            assertEquals(originalRecord.credentials.apiKey, restored.credentials.apiKey)
            assertEquals(originalRecord.createdAtMillis, restored.createdAtMillis)
            assertEquals(originalRecord.updatedAtMillis, restored.updatedAtMillis)
            assertEquals(originalRecord.cachedSnapshot, restored.cachedSnapshot)
        } finally {
            originalEnvelope.fill(0)
            atomicStore.releaseBlockedWrite()
            executor.shutdownNow()
        }
    }

    private suspend fun cancelDuringBlockedWrite(
        gateway: NativePageSpeedUiGateway,
        atomicStore: BlockingAtomicBytesStore,
        executor: ExecutorService,
        apiKey: String,
        siteUrl: String = "https://example.com",
    ) = coroutineScope {
        atomicStore.blockNextWrite()
        val connectJob = async(Dispatchers.Default) {
            gateway.connect(SecretValue.of(apiKey), siteUrl)
        }
        assertTrue(atomicStore.awaitWriteStarted())

        connectJob.cancelAndJoin()
        atomicStore.releaseBlockedWrite()
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    private class BlockingAtomicBytesStore : AtomicBytesStore {
        private val blockLock = Any()
        private var shouldBlockNextWrite = false
        private var writeStarted = CountDownLatch(0)
        private var allowWriteToFinish = CountDownLatch(0)

        @Volatile
        private var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes?.copyOf()

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
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }

        fun blockNextWrite() = synchronized(blockLock) {
            check(!shouldBlockNextWrite) { "A blocked write is already armed." }
            writeStarted = CountDownLatch(1)
            allowWriteToFinish = CountDownLatch(1)
            shouldBlockNextWrite = true
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(5, TimeUnit.SECONDS)

        fun releaseBlockedWrite() = allowWriteToFinish.countDown()

        fun snapshotBytes(): ByteArray? = bytes?.copyOf()

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
