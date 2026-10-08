package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CloudflareConnectionRepositoryTest {
    @Test
    fun encryptedRecordRoundTripsTokenAndBoundedDashboardDataWithoutPrintingSecret() {
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val original = connection("sensitive-cloudflare-token", completeSnapshot())

        repository.save(original)

        assertFalse(
            String(checkNotNull(store.bytes), StandardCharsets.UTF_8)
                .contains("sensitive-cloudflare-token"),
        )
        val loaded = checkNotNull(repository.load())
        assertEquals(original.account.apiToken, loaded.account.apiToken)
        assertEquals(original.cachedSnapshot, loaded.cachedSnapshot)
        assertFalse(loaded.toString().contains("sensitive-cloudflare-token"))
        assertFalse(loaded.account.toString().contains("sensitive-cloudflare-token"))
    }

    @Test
    fun globalApiKeyRecordRoundTripsEmailKeyRoutesAndTagsWithoutPrintingSecrets() {
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val profile = CloudflareProfile("user-1", "Ada Lovelace", "active", CloudflareAuthMode.GLOBAL_API_KEY, "owner@example.com")
        val snapshot = completeSnapshot().let { base ->
            val inventory = checkNotNull(base.selectedAccountInventory)
            base.copy(
                profile = profile,
                selectedAccountInventory = inventory.copy(
                    workers = listOf(worker(0).copy(routes = listOf("example.com/api/*"), tags = listOf("billing"))),
                ),
            )
        }
        val original = CloudflareStoredConnection(
            account = CloudflareAccount(
                profile = profile,
                credential = CloudflareCredential.globalApiKey("owner@example.com", "sensitive-global-key"),
                createdAtMillis = 10L,
                updatedAtMillis = 100L,
            ),
            cachedSnapshot = snapshot,
        )

        repository.save(original)

        val stored = String(checkNotNull(store.bytes), StandardCharsets.UTF_8)
        assertFalse(stored.contains("sensitive-global-key"))
        assertFalse(stored.contains("owner@example.com"))
        val loaded = checkNotNull(repository.load())
        val credential = loaded.account.credential as CloudflareCredential.GlobalApiKey
        assertEquals("owner@example.com", credential.email)
        assertEquals("sensitive-global-key", credential.key.use { it })
        assertNull(loaded.account.apiToken)
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, loaded.account.profile.authMode)
        assertEquals(snapshot, loaded.cachedSnapshot)
        assertEquals(listOf("example.com/api/*"), loaded.cachedSnapshot?.selectedAccountInventory?.workers?.single()?.routes)
        assertFalse(loaded.toString().contains("sensitive-global-key"))
        assertFalse(loaded.account.toString().contains("sensitive-global-key"))
    }

    @Test
    fun legacyVersionOneTokenRecordsStillDecodeAsScopedTokens() {
        val bytes = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use { output ->
                fun string(value: String) {
                    val encoded = value.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeInt(1)
                string(CloudflareAccount.PROVIDER_ID)
                string("token-profile")
                string("Cloudflare")
                string("active")
                string("legacy-token")
                output.writeLong(10L)
                output.writeLong(100L)
                output.writeBoolean(false)
            }
        }.toByteArray()

        val decoded = CloudflareConnectionPayloadCodec.decode(bytes)

        assertEquals(CloudflareAuthMode.API_TOKEN, decoded.account.profile.authMode)
        assertEquals(SecretValue.of("legacy-token"), decoded.account.apiToken)
        assertNull(decoded.account.profile.email)
        assertNull(decoded.cachedSnapshot)
    }

    @Test
    fun validationForADifferentAuthModeCannotBeSaved() {
        val connectionStore = CloudflareConnectionStore(
            CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            connectionStore.saveValidatedConnection(
                CloudflareCredential.globalApiKey("owner@example.com", "key"),
                CloudflareFetchResult.Complete(completeSnapshot()),
            )
        }
    }

    @Test
    fun recordUsesDedicatedNoBackupPathAadAndKeystoreAlias() {
        assertEquals("accounts/cloudflare-api-token.account", CloudflareConnectionRepository.ACCOUNT_PATH)
        assertEquals(
            "verceltics.account-envelope.v1:cloudflare-api-token",
            CloudflareConnectionRepository.ASSOCIATED_DATA,
        )
        assertEquals(
            "verceltics.account-storage.cloudflare.v1",
            CloudflareConnectionRepository.KEY_ALIAS,
        )
        assertFalse(CloudflareConnectionRepository.ACCOUNT_PATH.contains("vercel-personal-token"))
        assertFalse(CloudflareConnectionRepository.KEY_ALIAS == "verceltics.account-storage.v1")
    }

    @Test
    fun missingAndCorruptRecordsAreDistinguishedWithoutDeletingCorruption() {
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        assertNull(repository.load())

        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(IllegalArgumentException::class.java, repository::load)
        assertEquals(3, store.bytes?.size)
        assertEquals(
            CloudflareRestoreResult.Unavailable(CloudflareRestoreProblem.SAVED_RECORD_UNREADABLE),
            CloudflareConnectionStore(repository).restore(),
        )
    }

    @Test
    fun offlineCacheIsBoundedAndTruthfullyMarkedIncomplete() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 100L })
        val oversized = completeSnapshot(
            accountCount = CloudflareConnectionStore.MAX_CACHED_ACCOUNTS + 5,
            zoneCount = CloudflareConnectionStore.MAX_CACHED_ZONES + 5,
            pagesCount = CloudflareConnectionStore.MAX_CACHED_PAGES_PROJECTS + 5,
            workerCount = CloudflareConnectionStore.MAX_CACHED_WORKERS + 5,
        )

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("token"),
            CloudflareFetchResult.Complete(oversized),
        )
        connectionStore.acceptValidatedConnection(commit)
        val cached = checkNotNull(repository.load()?.cachedSnapshot)

        assertEquals(CloudflareConnectionStore.MAX_CACHED_ACCOUNTS, cached.accounts.size)
        assertEquals(
            CloudflareConnectionStore.MAX_CACHED_ZONES,
            cached.selectedAccountInventory?.zones?.size,
        )
        assertFalse(cached.isComplete)
        assertTrue(cached.warnings.any { it.contains("bounded") })
        assertTrue(cached.selectedAccountInventory?.warnings?.any { it.contains("bounded") } == true)
    }

    @Test
    fun cancelledNewConnectionDeletesOnlyItsOwnRevision() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 200L })

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("cancelled-token"),
            CloudflareFetchResult.Complete(completeSnapshot()),
        )

        assertTrue(connectionStore.rollbackValidatedConnection(commit))
        assertNull(repository.load())
    }

    @Test
    fun cancelledReplacementRestoresExactPriorEncryptedRecord() {
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val prior = connection("prior-token", completeSnapshot(zoneCount = 2))
        repository.save(prior)
        val exactPriorEnvelope = checkNotNull(store.bytes).copyOf()
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 300L })

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("replacement-token"),
            CloudflareFetchResult.Complete(completeSnapshot(zoneCount = 3)),
        )

        assertTrue(connectionStore.rollbackValidatedConnection(commit))
        assertArrayEquals(exactPriorEnvelope, store.bytes)
        assertEquals(SecretValue.of("prior-token"), repository.load()?.account?.apiToken)
        assertEquals(2, repository.load()?.cachedSnapshot?.selectedAccountInventory?.zones?.size)
        exactPriorEnvelope.fill(0)
    }

    @Test
    fun overlappingConnectIsRejectedAndLateRollbackAfterAcceptCannotChangeNewerData() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection("base-token", completeSnapshot()))
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 400L })
        val pending = connectionStore.saveValidatedConnection(
            SecretValue.of("pending-token"),
            CloudflareFetchResult.Complete(completeSnapshot(zoneCount = 2)),
        )

        assertThrows(IllegalStateException::class.java) {
            connectionStore.saveValidatedConnection(
                SecretValue.of("overlap-token"),
                CloudflareFetchResult.Complete(completeSnapshot(zoneCount = 3)),
            )
        }
        connectionStore.acceptValidatedConnection(pending)
        repository.save(connection("newer-token", completeSnapshot(zoneCount = 4)))

        assertFalse(connectionStore.rollbackValidatedConnection(pending))
        assertEquals(SecretValue.of("newer-token"), repository.load()?.account?.apiToken)
        assertEquals(4, repository.load()?.cachedSnapshot?.selectedAccountInventory?.zones?.size)
    }

    @Test
    fun disconnectDuringPendingConnectWinsAgainstLateRollback() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection("base-token", completeSnapshot()))
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 450L })
        val pending = connectionStore.saveValidatedConnection(
            SecretValue.of("pending-token"),
            CloudflareFetchResult.Complete(completeSnapshot(zoneCount = 2)),
        )

        connectionStore.disconnect()

        assertFalse(connectionStore.rollbackValidatedConnection(pending))
        assertNull(repository.load())
    }

    @Test
    fun disconnectAndNewerSaveWinAgainstStaleRefreshCompareAndSwap() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection("base-token", completeSnapshot()))
        val staleBeforeDisconnect = checkNotNull(repository.loadWithRevision())
        repository.delete()
        assertFalse(
            repository.saveIfRevisionMatches(
                staleBeforeDisconnect.revision,
                connection("stale-token", completeSnapshot(zoneCount = 2)),
            ),
        )
        assertNull(repository.load())

        repository.save(connection("base-token", completeSnapshot()))
        val staleBeforeNewer = checkNotNull(repository.loadWithRevision())
        repository.save(connection("newer-token", completeSnapshot(zoneCount = 3)))
        assertFalse(
            repository.saveIfRevisionMatches(
                staleBeforeNewer.revision,
                connection("stale-token", completeSnapshot(zoneCount = 2)),
            ),
        )
        assertEquals(SecretValue.of("newer-token"), repository.load()?.account?.apiToken)
    }

    @Test
    fun persistRefreshCannotResurrectDisconnectThatWinsAfterLoad() {
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        repository.save(connection("base-token", completeSnapshot()))
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 700L })
        val expected = checkNotNull(connectionStore.loadForRefresh())
        store.bytes = null

        val persisted = connectionStore.persistRefreshResult(
            expected,
            CloudflareFetchResult.Complete(completeSnapshot(fetchedAt = 700L, zoneCount = 2)),
        )

        store.clearInterception()
        assertFalse(persisted)
        assertNull(repository.load())
    }

    @Test
    fun persistRefreshCannotOverwriteNewConnectionThatWinsAfterLoad() {
        val newerStore = MemoryAtomicBytesStore()
        CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, newerStore), TestAccountCipher()).save(
            connection("newer-token", completeSnapshot(zoneCount = 4)),
        )
        val newerEnvelope = checkNotNull(newerStore.bytes).copyOf()
        val store = MemoryAtomicBytesStore()
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        repository.save(connection("base-token", completeSnapshot()))
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 750L })
        val expected = checkNotNull(connectionStore.loadForRefresh())
        store.bytes = newerEnvelope.copyOf()

        val persisted = connectionStore.persistRefreshResult(
            expected,
            CloudflareFetchResult.Complete(completeSnapshot(fetchedAt = 750L, zoneCount = 2)),
        )

        store.clearInterception()
        assertFalse(persisted)
        assertEquals(SecretValue.of("newer-token"), repository.load()?.account?.apiToken)
        assertEquals(4, repository.load()?.cachedSnapshot?.selectedAccountInventory?.zones?.size)
        newerEnvelope.fill(0)
    }

    @Test
    fun partialReconnectPreservesFullerSameProfileOfflineCache() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection("prior-token", completeSnapshot(zoneCount = 3)))
        val connectionStore = CloudflareConnectionStore(repository, nowMillis = { 800L })
        val partial = partialSnapshot()

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("replacement-token"),
            CloudflareFetchResult.Partial(
                partial,
                listOf(CloudflareFailure(CloudflareFailureKind.NETWORK, "Cloudflare could not be reached.")),
            ),
        )
        connectionStore.acceptValidatedConnection(commit)

        assertEquals(3, repository.load()?.cachedSnapshot?.selectedAccountInventory?.zones?.size)
        assertEquals(SecretValue.of("replacement-token"), repository.load()?.account?.apiToken)
    }

    @Test
    fun partialReconnectMergesEachInventorySectionWithoutDiscardingCompleteSections() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val previous = sectionalSnapshot(
            zones = listOf(zone(0, "account-0"), zone(1, "account-0")),
            pages = listOf(pages(0), pages(1)),
            workers = listOf(worker(0)),
            zonesComplete = true,
            pagesComplete = true,
            workersComplete = false,
        )
        repository.save(connection("prior-token", previous))
        val candidate = sectionalSnapshot(
            zones = listOf(zone(9, "account-0")),
            pages = emptyList(),
            workers = listOf(worker(0), worker(1), worker(2)),
            zonesComplete = false,
            pagesComplete = false,
            workersComplete = true,
            fetchedAt = 900L,
        )
        val store = CloudflareConnectionStore(repository, nowMillis = { 900L })

        val commit = store.saveValidatedConnection(
            SecretValue.of("replacement-token"),
            CloudflareFetchResult.Partial(candidate, listOf(NETWORK_FAILURE)),
        )
        store.acceptValidatedConnection(commit)

        val cached = checkNotNull(repository.load()?.cachedSnapshot?.selectedAccountInventory)
        assertEquals(listOf("zone-0", "zone-1"), cached.zones.map { it.id })
        assertEquals(listOf("pages-0", "pages-1"), cached.pagesProjects.map { it.id })
        assertEquals(listOf("worker-0", "worker-1", "worker-2"), cached.workers.map { it.id })
        assertTrue(cached.zonesComplete && cached.pagesComplete && cached.workersComplete)
    }

    @Test
    fun partialRefreshUnionsIncompleteSectionAndPreservesOtherCompleteSections() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val previous = sectionalSnapshot(
            zones = listOf(zone(0, "account-0")),
            pages = listOf(pages(0), pages(1)),
            workers = listOf(worker(0)),
            zonesComplete = false,
            pagesComplete = true,
            workersComplete = false,
        )
        repository.save(connection("token", previous))
        val candidate = sectionalSnapshot(
            zones = listOf(zone(1, "account-0")),
            pages = emptyList(),
            workers = listOf(worker(1), worker(2)),
            zonesComplete = false,
            pagesComplete = false,
            workersComplete = true,
            fetchedAt = 950L,
        )
        val store = CloudflareConnectionStore(repository, nowMillis = { 950L })

        assertTrue(
            store.persistRefreshResult(
                checkNotNull(store.loadForRefresh()),
                CloudflareFetchResult.Partial(candidate, listOf(NETWORK_FAILURE)),
            ),
        )

        val cached = checkNotNull(repository.load()?.cachedSnapshot?.selectedAccountInventory)
        assertEquals(listOf("zone-0", "zone-1"), cached.zones.map { it.id })
        assertFalse(cached.zonesComplete)
        assertEquals(listOf("pages-0", "pages-1"), cached.pagesProjects.map { it.id })
        assertTrue(cached.pagesComplete)
        assertEquals(listOf("worker-1", "worker-2"), cached.workers.map { it.id })
        assertTrue(cached.workersComplete)
        assertEquals(1, cached.warnings.count { it.contains("zone inventory") })
        assertTrue(cached.warnings.none { it.contains("Pages inventory") || it.contains("Worker inventory") })
    }

    @Test
    fun mergedPartialCachesStayBoundedOnReconnectAndRefresh() {
        fun oversizedPartial(offset: Int, fetchedAt: Long): CloudflareSnapshot {
            val base = sectionalSnapshot(
                zones = List(CloudflareConnectionStore.MAX_CACHED_ZONES) {
                    zone(offset + it, "account-0")
                },
                pages = List(CloudflareConnectionStore.MAX_CACHED_PAGES_PROJECTS) {
                    pages(offset + it)
                },
                workers = List(CloudflareConnectionStore.MAX_CACHED_WORKERS) {
                    worker(offset + it)
                },
                zonesComplete = false,
                pagesComplete = false,
                workersComplete = false,
                fetchedAt = fetchedAt,
            )
            val accountWarning = "Cloudflare account inventory is incomplete: retry required."
            return base.copy(
                accounts = listOf(account(0)) + List(CloudflareConnectionStore.MAX_CACHED_ACCOUNTS - 1) {
                    account(offset + it + 1)
                },
                accountsComplete = false,
                warnings = listOf(accountWarning) + base.warnings,
            )
        }

        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection("old-token", oversizedPartial(0, 1_000L)))
        val store = CloudflareConnectionStore(repository, nowMillis = { 1_100L })
        val reconnect = store.saveValidatedConnection(
            SecretValue.of("new-token"),
            CloudflareFetchResult.Partial(oversizedPartial(100, 1_100L), listOf(NETWORK_FAILURE)),
        )
        store.acceptValidatedConnection(reconnect)
        assertCacheBounds(checkNotNull(repository.load()?.cachedSnapshot))

        assertTrue(
            store.persistRefreshResult(
                checkNotNull(store.loadForRefresh()),
                CloudflareFetchResult.Partial(
                    oversizedPartial(200, 1_200L),
                    listOf(NETWORK_FAILURE),
                ),
            ),
        )
        assertCacheBounds(checkNotNull(repository.load()?.cachedSnapshot))
    }

    @Test
    fun completeMergedInventoryDropsObsoleteBoundedWarningInsidePartialSnapshot() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val store = CloudflareConnectionStore(repository, nowMillis = { 1_300L })
        val oversized = completeSnapshot(
            accountCount = CloudflareConnectionStore.MAX_CACHED_ACCOUNTS + 1,
            zoneCount = CloudflareConnectionStore.MAX_CACHED_ZONES + 1,
            pagesCount = CloudflareConnectionStore.MAX_CACHED_PAGES_PROJECTS + 1,
            workerCount = CloudflareConnectionStore.MAX_CACHED_WORKERS + 1,
        )
        val prior = store.saveValidatedConnection(
            SecretValue.of("token"),
            CloudflareFetchResult.Complete(oversized),
        )
        store.acceptValidatedConnection(prior)
        assertTrue(
            repository.load()?.cachedSnapshot?.selectedAccountInventory?.warnings
                ?.any { it.contains("bounded") } == true,
        )

        val completeInventory = completeSnapshot(fetchedAt = 1_300L)
        val accountWarning = "Cloudflare account inventory is incomplete: retry required."
        val otherwisePartial = completeInventory.copy(
            accountsComplete = false,
            warnings = listOf(accountWarning),
        )

        assertTrue(
            store.persistRefreshResult(
                checkNotNull(store.loadForRefresh()),
                CloudflareFetchResult.Partial(otherwisePartial, listOf(NETWORK_FAILURE)),
            ),
        )

        val cached = checkNotNull(repository.load()?.cachedSnapshot)
        val inventory = checkNotNull(cached.selectedAccountInventory)
        assertTrue(inventory.isComplete)
        assertTrue(inventory.warnings.isEmpty())
        assertFalse(cached.isComplete)
        assertTrue(cached.warnings.any { it.contains("account inventory") })
    }

    @Test
    fun complementaryPartialMergeDropsObsoleteWarningWhenSnapshotBecomesComplete() {
        val repository = CloudflareConnectionRepository(primaryFiles(CloudflareConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val store = CloudflareConnectionStore(repository, nowMillis = { 1_400L })
        val accountBounded = completeSnapshot(
            accountCount = CloudflareConnectionStore.MAX_CACHED_ACCOUNTS + 1,
        )
        val prior = store.saveValidatedConnection(
            SecretValue.of("token"),
            CloudflareFetchResult.Complete(accountBounded),
        )
        store.acceptValidatedConnection(prior)
        val boundedCache = checkNotNull(repository.load()?.cachedSnapshot)
        assertFalse(boundedCache.accountsComplete)
        assertTrue(boundedCache.selectedAccountInventory?.isComplete == true)
        assertTrue(boundedCache.warnings.any { it.contains("bounded") })

        assertTrue(
            store.persistRefreshResult(
                checkNotNull(store.loadForRefresh()),
                CloudflareFetchResult.Partial(partialSnapshot(), listOf(NETWORK_FAILURE)),
            ),
        )

        val merged = checkNotNull(repository.load()?.cachedSnapshot)
        assertTrue(merged.isComplete)
        assertTrue(merged.warnings.isEmpty())
        assertTrue(merged.selectedAccountInventory?.warnings?.isEmpty() == true)
    }

    private fun assertCacheBounds(snapshot: CloudflareSnapshot) {
        assertTrue(snapshot.accounts.size <= CloudflareConnectionStore.MAX_CACHED_ACCOUNTS)
        val inventory = checkNotNull(snapshot.selectedAccountInventory)
        assertTrue(inventory.zones.size <= CloudflareConnectionStore.MAX_CACHED_ZONES)
        assertTrue(inventory.pagesProjects.size <= CloudflareConnectionStore.MAX_CACHED_PAGES_PROJECTS)
        assertTrue(inventory.workers.size <= CloudflareConnectionStore.MAX_CACHED_WORKERS)
        assertFalse(snapshot.isComplete)
        assertTrue(snapshot.warnings.any { it.contains("bounded") })
    }

    private fun connection(token: String, snapshot: CloudflareSnapshot?) = CloudflareStoredConnection(
        account = CloudflareAccount(
            profile = PROFILE,
            apiToken = SecretValue.of(token),
            createdAtMillis = 10L,
            updatedAtMillis = 100L,
        ),
        cachedSnapshot = snapshot,
    )

    private fun completeSnapshot(
        fetchedAt: Long = 100L,
        accountCount: Int = 1,
        zoneCount: Int = 1,
        pagesCount: Int = 1,
        workerCount: Int = 1,
    ): CloudflareSnapshot {
        val accounts = List(accountCount) { account(it) }
        val selected = accounts.firstOrNull()
        val inventory = selected?.let {
            CloudflareAccountInventory(
                accountId = it.id,
                zones = List(zoneCount) { index -> zone(index, it.id) },
                pagesProjects = List(pagesCount) { pages(it) },
                workers = List(workerCount) { worker(it) },
                zonesComplete = true,
                pagesComplete = true,
                workersComplete = true,
                warnings = emptyList(),
            )
        }
        return CloudflareSnapshot(
            profile = PROFILE,
            accounts = accounts,
            selectedAccountId = selected?.id,
            selectedAccountInventory = inventory,
            accountsComplete = true,
            fetchedAtMillis = fetchedAt,
            warnings = emptyList(),
        )
    }

    private fun partialSnapshot(): CloudflareSnapshot {
        val account = account(0)
        val warning = "Cloudflare zone inventory is incomplete: Cloudflare could not be reached."
        return CloudflareSnapshot(
            profile = PROFILE,
            accounts = listOf(account),
            selectedAccountId = account.id,
            selectedAccountInventory = CloudflareAccountInventory(
                accountId = account.id,
                zones = listOf(zone(0, account.id)),
                pagesProjects = emptyList(),
                workers = emptyList(),
                zonesComplete = false,
                pagesComplete = true,
                workersComplete = true,
                warnings = listOf(warning),
            ),
            accountsComplete = true,
            fetchedAtMillis = 800L,
            warnings = listOf(warning),
        )
    }

    private fun sectionalSnapshot(
        zones: List<CloudflareZone>,
        pages: List<CloudflarePagesProject>,
        workers: List<CloudflareWorkerScript>,
        zonesComplete: Boolean,
        pagesComplete: Boolean,
        workersComplete: Boolean,
        fetchedAt: Long = 800L,
    ): CloudflareSnapshot {
        val account = account(0)
        val warnings = buildList {
            if (!zonesComplete) add("Cloudflare zone inventory is incomplete: retry required.")
            if (!pagesComplete) add("Cloudflare Pages inventory is incomplete: retry required.")
            if (!workersComplete) add("Cloudflare Worker inventory is incomplete: retry required.")
        }
        return CloudflareSnapshot(
            profile = PROFILE,
            accounts = listOf(account),
            selectedAccountId = account.id,
            selectedAccountInventory = CloudflareAccountInventory(
                accountId = account.id,
                zones = zones,
                pagesProjects = pages,
                workers = workers,
                zonesComplete = zonesComplete,
                pagesComplete = pagesComplete,
                workersComplete = workersComplete,
                warnings = warnings,
            ),
            accountsComplete = true,
            fetchedAtMillis = fetchedAt,
            warnings = warnings,
        )
    }

    private fun account(index: Int) = CloudflareAccountSummary(
        "account-$index", "Account $index", "standard", null,
    )

    private fun zone(index: Int, accountId: String) = CloudflareZone(
        "zone-$index", "zone-$index.example.com", "active", "full", false,
        accountId, "Account", "Free",
    )

    private fun pages(index: Int) = CloudflarePagesProject(
        "pages-$index", "Pages $index", "pages-$index.pages.dev", emptyList(), "main", null, "success",
    )

    private fun worker(index: Int) = CloudflareWorkerScript(
        "worker-$index", null, null, "2026-08-01", listOf("fetch"), false, true,
    )

    private class MemoryAtomicBytesStore : AtomicBytesStore {
        var bytes: ByteArray? = null
        private var readCount = 0
        private var onRead: ((Int) -> Unit)? = null

        override fun read(): ByteArray? {
            readCount += 1
            onRead?.invoke(readCount)
            return bytes?.copyOf()
        }

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }

        fun interceptReads(block: (Int) -> Unit) {
            readCount = 0
            onRead = block
        }

        fun clearInterception() {
            readCount = 0
            onRead = null
        }
    }

    private class TestAccountCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
            SealedPayload(ByteArray(12) { 7 }, transform(plaintext, associatedData))

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
            transform(payload.ciphertext(), associatedData)

        private fun transform(input: ByteArray, associatedData: ByteArray): ByteArray =
            ByteArray(input.size) { index ->
                (input[index].toInt() xor associatedData[index % associatedData.size].toInt()).toByte()
            }
    }

    companion object {
        private val PROFILE = CloudflareProfile("token-profile", "Cloudflare", "active")
        private val NETWORK_FAILURE = CloudflareFailure(
            CloudflareFailureKind.NETWORK,
            "Cloudflare could not be reached.",
        )
    }
}
