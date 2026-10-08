package com.apoorvdarshan.verceltics.ui.netlify

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpMethod
import com.apoorvdarshan.verceltics.data.hosting.bearerToken
import com.apoorvdarshan.verceltics.data.hosting.bodyText
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
import com.apoorvdarshan.verceltics.data.netlify.NetlifyBuild
import com.apoorvdarshan.verceltics.data.netlify.NetlifyBuildControls
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionRepository
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionStore
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDataSource
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDeployment
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDomain
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDomainKind
import com.apoorvdarshan.verceltics.data.netlify.NetlifyProfile
import com.apoorvdarshan.verceltics.data.netlify.NetlifyReadApi
import com.apoorvdarshan.verceltics.data.netlify.NetlifySite
import com.apoorvdarshan.verceltics.data.netlify.NetlifySiteDetails
import com.apoorvdarshan.verceltics.data.netlify.NetlifyWriteApi
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeNetlifyUiGatewayTest {
    @Test
    fun cancellationDuringFirstEncryptedSaveRollsBackEmptySlot() = runBlocking {
        val fixture = Fixture(FakeApi(siteCount = 1))
        try {
            fixture.store.blockNextWrite()
            val job = async(Dispatchers.Default) {
                fixture.gateway.connect(SecretValue.of("cancelled-token"))
            }
            assertTrue(fixture.store.awaitWriteStarted())

            job.cancel()
            fixture.store.releaseBlockedWrite()
            job.cancelAndJoin()

            assertNull(fixture.repository.load())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelledReplacementRestoresExactPriorEncryptedRecord() = runBlocking {
        val fixture = Fixture(FakeApi(siteCount = 1))
        try {
            fixture.gateway.connect(SecretValue.of("original-token")).getOrThrow()
            val originalEnvelope = checkNotNull(fixture.store.snapshotBytes())
            val original = checkNotNull(fixture.repository.load())

            fixture.store.blockNextWrite()
            val job = async(Dispatchers.Default) {
                fixture.gateway.connect(SecretValue.of("replacement-token"))
            }
            assertTrue(fixture.store.awaitWriteStarted())
            job.cancel()
            fixture.store.releaseBlockedWrite()
            job.cancelAndJoin()

            assertArrayEquals(originalEnvelope, fixture.store.snapshotBytes())
            val restored = checkNotNull(fixture.repository.load())
            assertEquals(original.account.id, restored.account.id)
            assertEquals(original.account.personalToken, restored.account.personalToken)
            assertEquals(original.account.createdAtMillis, restored.account.createdAtMillis)
            assertEquals(original.cachedSnapshot, restored.cachedSnapshot)
            originalEnvelope.fill(0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationAfterSaveButBeforeAcceptRestoresExactPriorRecord() = runBlocking {
        var blockBeforeAccept = false
        val acceptStarted = CompletableDeferred<Unit>()
        val acceptRelease = CompletableDeferred<Unit>()
        val fixture = Fixture(
            api = FakeApi(siteCount = 1),
            beforeAccept = {
                if (blockBeforeAccept) {
                    acceptStarted.complete(Unit)
                    acceptRelease.await()
                }
            },
        )
        try {
            fixture.gateway.connect(SecretValue.of("original-token")).getOrThrow()
            val originalEnvelope = checkNotNull(fixture.store.snapshotBytes())
            blockBeforeAccept = true

            val job = async(Dispatchers.Default) {
                fixture.gateway.connect(SecretValue.of("replacement-token"))
            }
            acceptStarted.await()
            job.cancelAndJoin()

            assertArrayEquals(originalEnvelope, fixture.store.snapshotBytes())
            assertEquals(
                SecretValue.of("original-token"),
                checkNotNull(fixture.repository.load()).account.personalToken,
            )
            originalEnvelope.fill(0)
        } finally {
            acceptRelease.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun cancellationAfterAcceptKeepsCommittedConnection() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val releaseAfterAccept = CompletableDeferred<Unit>()
        val fixture = Fixture(
            api = FakeApi(siteCount = 1),
            afterAccept = {
                accepted.complete(Unit)
                releaseAfterAccept.await()
            },
        )
        try {
            val job = async(Dispatchers.Default) {
                fixture.gateway.connect(SecretValue.of("committed-token"))
            }
            accepted.await()
            job.cancel()
            releaseAfterAccept.complete(Unit)
            job.cancelAndJoin()

            assertEquals(
                SecretValue.of("committed-token"),
                checkNotNull(fixture.repository.load()).account.personalToken,
            )
        } finally {
            releaseAfterAccept.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun largeInventoryIsPagedCompletelyWithoutTheOldHundredSiteCap() = runBlocking {
        // 1,250 sites span 13 Netlify pages; iOS lists every one, so Android must too.
        val fixture = Fixture(FakeApi(siteCount = 1_250))
        try {
            val dashboard = fixture.gateway.connect(SecretValue.of("token")).getOrThrow()

            assertEquals(1_250, dashboard.sites.size)
            assertEquals(1_250, dashboard.loadedSiteCount)
            assertEquals("site-1249", dashboard.sites.last().id)
            assertEquals(1_250, dashboard.sites.map(NetlifySiteUi::id).toSet().size)
            assertTrue(dashboard.providerInventoryComplete)
            assertFalse(dashboard.isPartial)
            assertEquals(13, fixture.api.sitePageRequests.size)

            val refreshed = fixture.gateway.refresh().getOrThrow()
            assertEquals(1_250, refreshed.sites.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deployHistoryIsCompleteBeyondTheOldHundredItemCap() = runBlocking {
        val fixture = Fixture(FakeApi(siteCount = 1, deploymentCount = 345, buildCount = 230))
        try {
            fixture.gateway.connect(SecretValue.of("token")).getOrThrow()

            val workspace = fixture.gateway.loadSite("site-0").getOrThrow()

            assertEquals(345, workspace.deployments.items.size)
            assertEquals(345, workspace.deployments.loadedItemCount)
            assertTrue(workspace.deployments.providerCollectionComplete)
            assertNull(workspace.deployments.warning)
            assertEquals("deploy-344", workspace.deployments.items.last().id)
            assertEquals(230, workspace.builds.items.size)
            assertTrue(workspace.builds.providerCollectionComplete)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun redeploySendsConfirmedBuildRequestWithSavedToken() = runBlocking {
        val transport = FakeHostingTransport { jsonResponse("""{"id":"build-new","done":false}""") }
        val fixture = Fixture(FakeApi(siteCount = 1), writeTransport = transport)
        try {
            fixture.gateway.connect(SecretValue.of("saved-token")).getOrThrow()

            val message = fixture.gateway.redeploySite("site-0").getOrThrow()

            assertEquals(NativeNetlifyUiGateway.REDEPLOY_ACCEPTED, message)
            val request = transport.requests.single()
            assertEquals(HostingHttpMethod.POST, request.method)
            assertEquals("/api/v1/sites/site-0/builds", request.encodedPath)
            assertEquals("{}", request.bodyText())
            assertEquals("saved-token", request.bearerToken())
            assertEquals("api.netlify.com", request.endpoint.host)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun redeployFailuresAreRedactedAndNeverSentWithoutASavedAccount() = runBlocking {
        val transport = FakeHostingTransport { jsonResponse("""{"message":"secret provider detail"}""", status = 403) }
        val fixture = Fixture(FakeApi(siteCount = 1), writeTransport = transport)
        try {
            val disconnected = fixture.gateway.redeploySite("site-0").exceptionOrNull()
            assertEquals("Connect a Netlify account first.", disconnected?.message)
            assertTrue(transport.requests.isEmpty())

            fixture.gateway.connect(SecretValue.of("saved-token")).getOrThrow()
            val denied = fixture.gateway.redeploySite("site-0").exceptionOrNull()

            assertTrue(denied is NetlifyUiException)
            assertEquals("Netlify denied access. Check that this token can deploy the site.", denied?.message)
            assertFalse(denied?.message.orEmpty().contains("secret"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun siteWorkspaceKeepsIndependentDetailsPartialDeployAndFailedBuildTruth() = runBlocking {
        val fixture = Fixture(
            FakeApi(
                siteCount = 1,
                partialDeployments = true,
                failBuilds = true,
            ),
        )
        try {
            fixture.gateway.connect(SecretValue.of("token")).getOrThrow()

            val workspace = fixture.gateway.loadSite("site-0").getOrThrow()

            assertTrue(workspace.details is NetlifyResourceUi.Available)
            val details = (workspace.details as NetlifyResourceUi.Available).value
            assertEquals("https://github.com/example/site", details.buildControls?.repositoryUrl)
            assertEquals(listOf("main"), details.buildControls?.allowedBranches)
            assertEquals("published-deploy", details.publishedDeployment?.id)
            assertEquals(100, workspace.deployments.items.size)
            assertEquals(100, workspace.deployments.loadedItemCount)
            assertFalse(workspace.deployments.providerCollectionComplete)
            assertTrue(workspace.deployments.warning!!.contains("reached", ignoreCase = true))
            assertTrue(workspace.builds.items.isEmpty())
            assertFalse(workspace.builds.providerCollectionComplete)
            assertTrue(workspace.builds.warning!!.contains("reached", ignoreCase = true))
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        val api: FakeApi,
        beforeAccept: suspend () -> Unit = {},
        afterAccept: suspend () -> Unit = {},
        writeTransport: FakeHostingTransport = FakeHostingTransport { jsonResponse("{}") },
    ) {
        val store = BlockingAtomicBytesStore()
        val repository = NetlifyConnectionRepository(store, TestAccountCipher())
        private val networkExecutor = Executors.newFixedThreadPool(4)
        private val storageExecutor = Executors.newSingleThreadExecutor()
        val gateway = NativeNetlifyUiGateway(
            connectionStore = NetlifyConnectionStore(repository, nowMillis = { 42L }),
            dataSource = NetlifyDataSource(api, nowMillis = { 42L }),
            networkExecutor = networkExecutor,
            storageExecutor = storageExecutor,
            beforeAcceptValidatedConnection = beforeAccept,
            afterAcceptValidatedConnection = afterAccept,
            writeApi = NetlifyWriteApi(writeTransport),
        )

        fun close() {
            store.releaseBlockedWrite()
            networkExecutor.shutdownNow()
            storageExecutor.shutdownNow()
        }
    }

    private class FakeApi(
        siteCount: Int,
        private val partialDeployments: Boolean = false,
        private val failBuilds: Boolean = false,
        private val deploymentCount: Int = 1,
        private val buildCount: Int = 1,
    ) : NetlifyReadApi {
        private val sites = List(siteCount) { index -> site(index) }
        val sitePageRequests = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun newValidatePersonalTokenCall(token: SecretValue): CancelableCall<NetlifyProfile> =
            FixedCall { NetlifyProfile("account", "Netlify Account", "owner@example.com", null) }

        override fun newListSitesPageCall(
            token: SecretValue,
            page: Int,
            perPage: Int,
        ): CancelableCall<List<NetlifySite>> = FixedCall {
            sitePageRequests += page
            val from = ((page - 1) * perPage).coerceAtMost(sites.size)
            val to = (from + perPage).coerceAtMost(sites.size)
            sites.subList(from, to)
        }

        override fun newSiteDetailsCall(
            token: SecretValue,
            siteId: String,
        ): CancelableCall<NetlifySiteDetails> = FixedCall {
            NetlifySiteDetails(
                site = sites.first { it.id == siteId },
                domains = listOf(NetlifyDomain("example.com", NetlifyDomainKind.CUSTOM)),
                buildControls = NetlifyBuildControls(
                    buildsStopped = false,
                    repositoryUrl = "https://github.com/example/site",
                    repositoryPath = null,
                    repositoryBranch = "main",
                    baseDirectory = null,
                    publishDirectory = "dist",
                    functionsDirectory = null,
                    buildCommand = "npm run build",
                    allowedBranches = listOf("main"),
                    provider = "github",
                ),
                publishedDeployment = NetlifyDeployment(
                    id = "published-deploy",
                    title = "Published deploy",
                    status = "ready",
                    createdAtMillis = 42L,
                    url = "https://example.netlify.app",
                    branch = "main",
                    commitMessage = "Publish",
                ),
            )
        }

        override fun newListDeploymentsPageCall(
            token: SecretValue,
            siteId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<List<NetlifyDeployment>> = FixedCall {
            if (partialDeployments && page > 1) throw IOException("provider cannot be reached")
            if (partialDeployments) {
                List(perPage) { index -> deployment(index) }
            } else {
                pageOf(deploymentCount, page, perPage, ::deployment)
            }
        }

        override fun newListBuildsPageCall(
            token: SecretValue,
            siteId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<List<NetlifyBuild>> = FixedCall {
            if (failBuilds) throw IOException("provider cannot be reached")
            pageOf(buildCount, page, perPage, ::build)
        }

        override fun newBuildCall(
            token: SecretValue,
            buildId: String,
        ): CancelableCall<NetlifyBuild> = FixedCall { build(0).copy(id = buildId) }

        private fun <T> pageOf(total: Int, page: Int, perPage: Int, item: (Int) -> T): List<T> {
            val from = ((page - 1) * perPage).coerceAtMost(total)
            val to = (from + perPage).coerceAtMost(total)
            return (from until to).map(item)
        }

        private fun site(index: Int) = NetlifySite(
            id = "site-$index",
            name = "Site $index",
            subtitle = "site-$index.netlify.app",
            url = "https://site-$index.netlify.app",
            status = "current",
            updatedAtMillis = 42L,
            adminUrl = null,
        )

        private fun deployment(index: Int) = NetlifyDeployment(
            id = "deploy-$index",
            title = "Deploy $index",
            status = "ready",
            createdAtMillis = 42L,
            url = null,
            branch = "main",
            commitMessage = null,
        )

        private fun build(index: Int) = NetlifyBuild(
            id = "build-$index",
            deploymentId = "deploy-$index",
            commitSha = "abc$index",
            isDone = true,
            error = null,
            createdAtMillis = 42L,
        )
    }

    private class FixedCall<T>(private val block: () -> T) : CancelableCall<T> {
        @Volatile
        private var cancelled = false

        override fun execute(): T {
            if (cancelled) throw java.util.concurrent.CancellationException()
            return block()
        }

        override fun cancel() {
            cancelled = true
        }
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
                if (!shouldBlockNextWrite) null else {
                    shouldBlockNextWrite = false
                    writeStarted to allowWriteToFinish
                }
            }
            blockedWrite?.let { (started, finish) ->
                started.countDown()
                while (true) {
                    try {
                        finish.await()
                        break
                    } catch (_: InterruptedException) {
                        // Model an atomic replacement already past its cancellable point.
                    }
                }
            }
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }

        fun blockNextWrite() = synchronized(blockLock) {
            writeStarted = CountDownLatch(1)
            allowWriteToFinish = CountDownLatch(1)
            shouldBlockNextWrite = true
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(5, TimeUnit.SECONDS)

        fun releaseBlockedWrite() = allowWriteToFinish.countDown()

        fun snapshotBytes(): ByteArray? = bytes?.copyOf()
    }

    private class TestAccountCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
            SealedPayload(ByteArray(12) { 7 }, transform(plaintext, associatedData))

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
            transform(payload.ciphertext(), associatedData)

        private fun transform(bytes: ByteArray, associatedData: ByteArray): ByteArray =
            ByteArray(bytes.size) { index ->
                (bytes[index].toInt() xor associatedData[index % associatedData.size].toInt()).toByte()
            }
    }
}
