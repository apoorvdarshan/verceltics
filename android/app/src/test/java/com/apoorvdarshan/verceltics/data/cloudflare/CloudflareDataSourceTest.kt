package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class CloudflareDataSourceTest {
    @Test
    fun validatedDashboardCollectsAllPagesAndPreferredAccountResources() {
        val api = FakeApi(
            accountPage = { page ->
                when (page) {
                    1 -> pageCall(listOf(account("one"), account("two")), page, 2)
                    2 -> pageCall(listOf(account("three")), page, 2)
                    else -> error("Unexpected account page")
                }
            },
            zonePage = { accountId, page ->
                assertEquals("two", accountId)
                if (page == 1) pageCall(listOf(zone("z2"), zone("z1")), 1, 1)
                else error("Unexpected zone page")
            },
            pagesPage = { accountId, page ->
                assertEquals("two", accountId)
                pageCall(listOf(pages("p1")), page, 1)
            },
            workers = { accountId ->
                assertEquals("two", accountId)
                valueCall(listOf(worker("w1")))
            },
        )

        val result = CloudflareDataSource(api, nowMillis = { 42L })
            .newDashboardCall(
                TOKEN,
                preferredAccountId = "two",
                pageSize = 2,
                maximumPages = 5,
                maximumItems = 20,
            ).execute() as CloudflareFetchResult.Complete

        assertEquals(listOf(1, 2), api.accountPages)
        assertEquals("two", result.snapshot.selectedAccountId)
        assertEquals(listOf("z1", "z2"), result.snapshot.selectedAccountInventory?.zones?.map { it.id })
        assertEquals(1, result.snapshot.selectedAccountInventory?.pagesProjects?.size)
        assertEquals(1, result.snapshot.selectedAccountInventory?.workers?.size)
        assertEquals(42L, result.snapshot.fetchedAtMillis)
        assertTrue(result.snapshot.warnings.isEmpty())
    }

    @Test
    fun globalApiKeyValidatesWithUserProfileAndUsesTheKeyForEveryInventoryCall() {
        val credential = CloudflareCredential.globalApiKey("Owner@Example.com", "global-key")
        val api = FakeApi(
            accountPage = { pageCall(listOf(account("one")), 1, 1) },
            zonePage = { _, _ -> pageCall(listOf(zone("z")), 1, 1) },
            pagesPage = { _, _ -> pageCall(listOf(pages("p")), 1, 1) },
            workers = { valueCall(listOf(worker("w"))) },
        )

        val result = CloudflareDataSource(api).newDashboardCall(credential).execute() as CloudflareFetchResult.Complete

        assertEquals(0, api.verificationExecutions)
        assertEquals(1, api.userExecutions)
        val profile = result.snapshot.profile
        assertEquals("user-id", profile.id)
        assertEquals("Ada Lovelace", profile.displayName)
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, profile.authMode)
        assertEquals("owner@example.com", profile.email)
        assertEquals(5, api.credentials.size)
        assertTrue(api.credentials.all { it == credential })
    }

    @Test
    fun rejectedGlobalApiKeyStopsBeforeInventory() {
        val api = FakeApi(
            user = throwingCall(
                CloudflareApiException(
                    CloudflareFailure(CloudflareFailureKind.AUTHENTICATION, "Cloudflare rejected this email and Global API Key.", 401),
                    null,
                ),
            ),
        )
        val result = CloudflareDataSource(api)
            .newDashboardCall(CloudflareCredential.globalApiKey("owner@example.com", "bad"))
            .execute() as CloudflareFetchResult.Failure
        assertEquals(CloudflareFailureKind.AUTHENTICATION, result.failure.kind)
        assertTrue(api.accountPages.isEmpty())
    }

    @Test
    fun defaultDashboardWalksEveryPageBeyondTheOldCapsLikeIos() {
        // 12,000 zones over 240 pages: past the old 100-page / 250-row limits.
        val api = FakeApi(
            accountPage = { pageCall(listOf(account("one")), 1, 1) },
            zonePage = { _, page -> pageCall(List(50) { zone("zone-$page-$it") }, page, 240) },
            pagesPage = { _, page -> pageCall(List(20) { pages("site-$page-$it") }, page, 30) },
        )

        val result = CloudflareDataSource(api).newDashboardCall(TOKEN).execute() as CloudflareFetchResult.Complete
        val inventory = checkNotNull(result.snapshot.selectedAccountInventory)

        assertEquals(12_000, inventory.zones.size)
        assertEquals(600, inventory.pagesProjects.size)
        assertTrue(inventory.isComplete)
        // iOS fetchPagesProjects asks for 20 projects per page.
        assertTrue(api.pagesPerPage.all { it == CloudflareDataSource.PAGES_PAGE_SIZE })
        assertEquals(500, CloudflareDataSource.DEFAULT_MAXIMUM_PAGES)
        assertEquals(100_000, CloudflareDataSource.DEFAULT_MAXIMUM_ITEMS)
    }

    @Test
    fun paginationGuardStillStopsAnEndlessCollectionTruthfully() {
        val api = FakeApi(
            accountPage = { pageCall(listOf(account("one")), 1, 1) },
            zonePage = { _, page -> pageCall(List(50) { zone("zone-$page-$it") }, page, null) },
        )

        val result = CloudflareDataSource(api).newDashboardCall(TOKEN).execute() as CloudflareFetchResult.Partial
        val inventory = checkNotNull(result.snapshot.selectedAccountInventory)

        assertEquals(25_000, inventory.zones.size)
        assertTrue(!inventory.zonesComplete)
        assertTrue(result.failures.any { it.message.contains("exceeded 500 pages") })
    }

    @Test
    fun inactiveTokenStopsBeforeInventoryAndReturnsAuthenticationFailure() {
        val api = FakeApi(verification = valueCall(CloudflareTokenVerification("id", "disabled", null, null)))

        val result = CloudflareDataSource(api).newDashboardCall(TOKEN).execute()

        assertEquals(
            CloudflareFailureKind.AUTHENTICATION,
            (result as CloudflareFetchResult.Failure).failure.kind,
        )
        assertTrue(api.accountPages.isEmpty())
    }

    @Test
    fun laterAccountPageFailurePreservesLoadedAccountsAndMarksSnapshotPartial() {
        val api = FakeApi(
            accountPage = { page ->
                if (page == 1) {
                    pageCall(listOf(account("one"), account("two")), 1, null)
                } else {
                    throwingCall(IOException("raw socket detail"))
                }
            },
        )

        val result = CloudflareDataSource(api)
            .newDashboardCall(TOKEN, pageSize = 2, maximumPages = 5, maximumItems = 20)
            .execute() as CloudflareFetchResult.Partial

        assertEquals(2, result.snapshot.accounts.size)
        assertEquals(CloudflareFailureKind.NETWORK, result.failures.single().kind)
        assertTrue(result.snapshot.warnings.single().contains("incomplete"))
        assertTrue(result.snapshot.warnings.none { it.contains("socket detail") })
    }

    @Test
    fun forbiddenWorkersKeepZonesAndPagesWithTruthfulSectionWarning() {
        val denied = CloudflareApiException(
            CloudflareFailure(
                CloudflareFailureKind.AUTHORIZATION,
                "This Cloudflare API token cannot access the requested resource.",
                403,
            ),
            "permission",
        )
        val api = FakeApi(
            accountPage = { pageCall(listOf(account("one")), 1, 1) },
            zonePage = { _, _ -> pageCall(listOf(zone("zone")), 1, 1) },
            pagesPage = { _, _ -> pageCall(listOf(pages("pages")), 1, 1) },
            workers = { throwingCall(denied) },
        )

        val result = CloudflareDataSource(api).newDashboardCall(TOKEN).execute()
            as CloudflareFetchResult.Partial
        val inventory = checkNotNull(result.snapshot.selectedAccountInventory)

        assertEquals(1, inventory.zones.size)
        assertEquals(1, inventory.pagesProjects.size)
        assertTrue(inventory.workers.isEmpty())
        assertTrue(inventory.zonesComplete)
        assertTrue(inventory.pagesComplete)
        assertTrue(!inventory.workersComplete)
        assertEquals(CloudflareFailureKind.AUTHORIZATION, result.failures.single().kind)
        assertTrue(inventory.warnings.single().contains("Worker"))
    }

    @Test
    fun repeatedPagesAndSafetyLimitsNeverClaimCompleteness() {
        val repeated = CloudflareDataSource(
            FakeApi(accountPage = { page -> pageCall(listOf(account("same")), page, null) }),
        ).newDashboardCall(TOKEN, pageSize = 1, maximumPages = 3, maximumItems = 10)
            .execute() as CloudflareFetchResult.Partial
        assertTrue(repeated.failures.any { it.message.contains("repeated") })

        val limited = CloudflareDataSource(
            FakeApi(
                accountPage = { page ->
                    pageCall(listOf(account("$page-a"), account("$page-b")), page, null)
                },
            ),
        ).newDashboardCall(TOKEN, pageSize = 2, maximumPages = 2, maximumItems = 10)
            .execute() as CloudflareFetchResult.Partial
        assertEquals(4, limited.snapshot.accounts.size)
        assertTrue(limited.failures.any { it.message.contains("exceeded 2 pages") })
    }

    @Test
    fun cancellationBeforeExecutionDoesNotStartProviderWork() {
        val api = FakeApi()
        val call = CloudflareDataSource(api).newDashboardCall(TOKEN)
        call.cancel()

        assertThrows(CancellationException::class.java, call::execute)
        assertEquals(0, api.verificationExecutions)
    }

    @Test
    fun invalidPreferredAccountFallsBackWithoutRequestingUntrustedPath() {
        val api = FakeApi(accountPage = { pageCall(listOf(account("known")), 1, 1) })

        val result = CloudflareDataSource(api).newDashboardCall(
            TOKEN,
            preferredAccountId = "unknown-but-safe",
        ).execute() as CloudflareFetchResult.Complete

        assertEquals("known", result.snapshot.selectedAccountId)
        assertEquals(listOf("known"), api.zoneAccounts)
        assertEquals(listOf("known"), api.pagesAccounts)
        assertEquals(listOf("known"), api.workerAccounts)
    }

    @Test
    fun workersSinglePageWithoutPaginationMetadataIsCompleteAtNormalPageSize() {
        val workers = List(50) { worker("worker-$it") }
        val api = FakeApi(
            accountPage = { pageCall(listOf(account("one")), 1, 1) },
            workers = { valueCall(workers) },
        )

        val result = CloudflareDataSource(api)
            .newDashboardCall(TOKEN, pageSize = 50).execute() as CloudflareFetchResult.Complete

        assertEquals(50, result.snapshot.selectedAccountInventory?.workers?.size)
        assertTrue(result.snapshot.selectedAccountInventory?.workersComplete == true)
        assertEquals(listOf("one"), api.workerAccounts)
    }

    private class FakeApi(
        private val verification: CancelableCall<CloudflareTokenVerification> = valueCall(
            CloudflareTokenVerification("token-id", "active", null, null),
        ),
        private val accountPage: (Int) -> CancelableCall<CloudflarePage<CloudflareAccountSummary>> =
            { page -> pageCall(emptyList(), page, 1) },
        private val zonePage: (String, Int) -> CancelableCall<CloudflarePage<CloudflareZone>> =
            { _, page -> pageCall(emptyList(), page, 1) },
        private val pagesPage: (String, Int) -> CancelableCall<CloudflarePage<CloudflarePagesProject>> =
            { _, page -> pageCall(emptyList(), page, 1) },
        private val workers: (String) -> CancelableCall<List<CloudflareWorkerScript>> =
            { valueCall(emptyList()) },
        private val user: CancelableCall<CloudflareUserIdentity> = valueCall(
            CloudflareUserIdentity("user-id", "owner@example.com", "Ada", "Lovelace", false),
        ),
    ) : CloudflareReadApi {
        var verificationExecutions = 0
        var userExecutions = 0
        val accountPages = mutableListOf<Int>()
        val zoneAccounts = mutableListOf<String>()
        val pagesAccounts = mutableListOf<String>()
        val pagesPerPage = mutableListOf<Int>()
        val workerAccounts = mutableListOf<String>()
        val credentials = mutableListOf<CloudflareCredential>()

        override fun newUserCall(credential: CloudflareCredential.GlobalApiKey): CancelableCall<CloudflareUserIdentity> =
            object : CancelableCall<CloudflareUserIdentity> {
                override fun execute(): CloudflareUserIdentity {
                    userExecutions += 1
                    credentials += credential
                    return user.execute()
                }

                override fun cancel() = user.cancel()
            }

        override fun newVerifyTokenCall(token: SecretValue): CancelableCall<CloudflareTokenVerification> =
            object : CancelableCall<CloudflareTokenVerification> {
                override fun execute(): CloudflareTokenVerification {
                    verificationExecutions += 1
                    return verification.execute()
                }

                override fun cancel() = verification.cancel()
            }

        override fun newAccountsPageCall(
            credential: CloudflareCredential,
            page: Int,
            perPage: Int,
        ): CancelableCall<CloudflarePage<CloudflareAccountSummary>> {
            credentials += credential
            accountPages += page
            return accountPage(page)
        }

        override fun newZonesPageCall(
            credential: CloudflareCredential,
            accountId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<CloudflarePage<CloudflareZone>> {
            credentials += credential
            zoneAccounts += accountId
            return zonePage(accountId, page)
        }

        override fun newPagesProjectsPageCall(
            credential: CloudflareCredential,
            accountId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<CloudflarePage<CloudflarePagesProject>> {
            credentials += credential
            pagesAccounts += accountId
            pagesPerPage += perPage
            return pagesPage(accountId, page)
        }

        override fun newWorkerScriptsCall(
            credential: CloudflareCredential,
            accountId: String,
        ): CancelableCall<List<CloudflareWorkerScript>> {
            credentials += credential
            workerAccounts += accountId
            return workers(accountId)
        }
    }

    companion object {
        private val TOKEN = SecretValue.of("cloudflare-secret")

        private fun account(id: String) = CloudflareAccountSummary(id, "Account $id", null, null)
        private fun zone(id: String) = CloudflareZone(
            id, id, "active", "full", false, "one", "Account", "Free",
        )
        private fun pages(id: String) = CloudflarePagesProject(
            id, id, null, emptyList(), "main", null, "success",
        )
        private fun worker(id: String) = CloudflareWorkerScript(
            id, null, null, null, emptyList(), null, null,
        )

        private fun <T> pageCall(
            items: List<T>,
            page: Int,
            totalPages: Int?,
        ): CancelableCall<CloudflarePage<T>> = valueCall(CloudflarePage(items, page, totalPages))

        private fun <T> valueCall(value: T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = value
            override fun cancel() = Unit
        }

        private fun <T> throwingCall(error: Exception): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = throw error
            override fun cancel() = Unit
        }
    }
}
