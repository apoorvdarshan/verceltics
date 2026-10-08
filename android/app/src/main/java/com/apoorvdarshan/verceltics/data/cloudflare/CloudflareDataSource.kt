package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cancellable read-only dashboard orchestration with bounded, truthful section states.
 *
 * Collections are walked to completion like iOS `fetchAllPages`; the only limits are iOS's
 * `CloudflarePaginationGuard` safety bounds (500 pages, 100,000 items), which surface as an
 * explicit partial-inventory warning instead of a silent truncation.
 */
class CloudflareDataSource(
    private val api: CloudflareReadApi = CloudflareApi(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun newDashboardCall(
        token: SecretValue,
        preferredAccountId: String? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
        maximumItems: Int = DEFAULT_MAXIMUM_ITEMS,
        pagesPageSize: Int = minOf(pageSize, PAGES_PAGE_SIZE),
    ): CancelableCall<CloudflareFetchResult> = newDashboardCall(
        credential = CloudflareCredential.ApiToken(token),
        preferredAccountId = preferredAccountId,
        pageSize = pageSize,
        maximumPages = maximumPages,
        maximumItems = maximumItems,
        pagesPageSize = pagesPageSize,
    )

    fun newDashboardCall(
        credential: CloudflareCredential,
        preferredAccountId: String? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
        maximumItems: Int = DEFAULT_MAXIMUM_ITEMS,
        pagesPageSize: Int = minOf(pageSize, PAGES_PAGE_SIZE),
    ): CancelableCall<CloudflareFetchResult> {
        require(pageSize in 1..CloudflareApi.MAXIMUM_PAGE_SIZE)
        require(pagesPageSize in 1..pageSize)
        require(maximumPages in 1..CloudflareApi.MAXIMUM_PAGE_NUMBER)
        require(maximumItems in pageSize..HARD_MAXIMUM_ITEMS)
        require(
            preferredAccountId == null ||
                preferredAccountId.isSafeCloudflareText(CF_MAX_ID_CHARACTERS),
        )
        return CloudflareDashboardCall(
            api = api,
            credential = credential,
            preferredAccountId = preferredAccountId,
            pageSize = pageSize,
            pagesPageSize = pagesPageSize,
            maximumPages = maximumPages,
            maximumItems = maximumItems,
            nowMillis = nowMillis,
        )
    }

    companion object {
        /** iOS `fetchAccounts` / `fetchZones` use 50 results per page. */
        const val DEFAULT_PAGE_SIZE = 50

        /** iOS `fetchPagesProjects` uses 20 results per page. */
        const val PAGES_PAGE_SIZE = 20

        /** iOS `CloudflarePaginationGuard.maximumPages`. */
        const val DEFAULT_MAXIMUM_PAGES = 500

        /** iOS `CloudflarePaginationGuard.maximumItems`. */
        const val DEFAULT_MAXIMUM_ITEMS = 100_000
        private const val HARD_MAXIMUM_ITEMS = 100_000
    }
}

private class CloudflareDashboardCall(
    private val api: CloudflareReadApi,
    private val credential: CloudflareCredential,
    private val preferredAccountId: String?,
    private val pageSize: Int,
    private val pagesPageSize: Int,
    private val maximumPages: Int,
    private val maximumItems: Int,
    private val nowMillis: () -> Long,
) : TrackedCloudflareCall<CloudflareFetchResult>() {
    override fun executeTracked(): CloudflareFetchResult {
        verifyCredential()?.let { return it }

        val failures = mutableListOf<CloudflareFailure>()
        val warnings = mutableListOf<String>()
        val accountsResult = collectPages(
            label = "account",
            pageSize = pageSize,
            childFactory = { page -> api.newAccountsPageCall(credential, page, pageSize) },
            identity = CloudflareAccountSummary::id,
        )
        val accounts = accountsResult.itemsOrEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val accountsComplete = accountsResult is CloudflareCollectionResult.Complete
        accountsResult.failureOrNull()?.let { failure ->
            failures += failure
            warnings += "Cloudflare account inventory is incomplete: ${failure.message}"
        }

        val selectedAccountId = preferredAccountId
            ?.takeIf { preferred -> accounts.any { it.id == preferred } }
            ?: accounts.firstOrNull()?.id
        val inventory = selectedAccountId?.let { accountId ->
            loadAccountInventory(accountId, failures, warnings)
        }
        val profile = when (credential) {
            is CloudflareCredential.ApiToken -> {
                val verification = checkNotNull(verifiedToken)
                CloudflareProfile(
                    id = verification.id ?: credentialFingerprint(credential.token),
                    displayName = accounts.firstOrNull()?.name ?: "Cloudflare API Token",
                    tokenStatus = verification.status,
                )
            }
            is CloudflareCredential.GlobalApiKey -> {
                val user = checkNotNull(verifiedUser)
                CloudflareProfile(
                    id = user.id,
                    displayName = user.displayName.take(CF_MAX_NAME_CHARACTERS),
                    tokenStatus = if (user.suspended == true) "suspended" else "active",
                    authMode = CloudflareAuthMode.GLOBAL_API_KEY,
                    email = credential.email,
                )
            }
        }
        val snapshot = CloudflareSnapshot(
            profile = profile,
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            selectedAccountInventory = inventory,
            accountsComplete = accountsComplete,
            fetchedAtMillis = nowMillis(),
            warnings = warnings.distinct(),
        )
        return if (snapshot.isComplete) {
            CloudflareFetchResult.Complete(snapshot)
        } else {
            CloudflareFetchResult.Partial(snapshot, failures.distinct())
        }
    }

    private var verifiedToken: CloudflareTokenVerification? = null
    private var verifiedUser: CloudflareUserIdentity? = null

    /**
     * iOS validates scoped tokens with `/user/tokens/verify` (and requires `active`) and Global API
     * Keys with `/user`. Returns a failure, or null after storing the verified identity.
     */
    private fun verifyCredential(): CloudflareFetchResult.Failure? {
        try {
            when (credential) {
                is CloudflareCredential.ApiToken -> {
                    val verification = executeChild(api.newVerifyTokenCall(credential.token))
                    if (!verification.isActive) {
                        return CloudflareFetchResult.Failure(
                            CloudflareFailure(
                                CloudflareFailureKind.AUTHENTICATION,
                                "This Cloudflare API token is not active.",
                            ),
                        )
                    }
                    verifiedToken = verification
                }
                is CloudflareCredential.GlobalApiKey -> {
                    verifiedUser = executeChild(api.newUserCall(credential))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return CloudflareFetchResult.Failure(safeCloudflareFailure(error))
        }
        return null
    }

    private fun loadAccountInventory(
        accountId: String,
        failures: MutableList<CloudflareFailure>,
        dashboardWarnings: MutableList<String>,
    ): CloudflareAccountInventory {
        val sectionWarnings = mutableListOf<String>()
        val zonesResult = collectPages(
            label = "zone",
            pageSize = pageSize,
            childFactory = { page -> api.newZonesPageCall(credential, accountId, page, pageSize) },
            identity = CloudflareZone::id,
        )
        val pagesResult = collectPages(
            label = "Pages project",
            pageSize = pagesPageSize,
            childFactory = { page -> api.newPagesProjectsPageCall(credential, accountId, page, pagesPageSize) },
            identity = CloudflarePagesProject::id,
        )
        val workersResult = loadWorkerScripts(accountId)

        fun record(section: String, result: CloudflareCollectionResult<*>) {
            result.failureOrNull()?.let { failure ->
                failures += failure
                val warning = "Cloudflare $section inventory is incomplete: ${failure.message}"
                sectionWarnings += warning
                dashboardWarnings += warning
            }
        }
        record("zone", zonesResult)
        record("Pages", pagesResult)
        record("Worker", workersResult)

        return CloudflareAccountInventory(
            accountId = accountId,
            zones = zonesResult.itemsOrEmpty().sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            ),
            pagesProjects = pagesResult.itemsOrEmpty().sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            ),
            workers = workersResult.itemsOrEmpty().sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.id },
            ),
            zonesComplete = zonesResult is CloudflareCollectionResult.Complete,
            pagesComplete = pagesResult is CloudflareCollectionResult.Complete,
            workersComplete = workersResult is CloudflareCollectionResult.Complete,
            warnings = sectionWarnings.distinct(),
        )
    }

    /** Workers scripts is a bounded SinglePage Cloudflare endpoint; it has no page controls. */
    private fun loadWorkerScripts(accountId: String): CloudflareCollectionResult<CloudflareWorkerScript> =
        try {
            CloudflareCollectionResult.Complete(executeChild(api.newWorkerScriptsCall(credential, accountId)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CloudflareCollectionResult.Failure(safeCloudflareFailure(error))
        }

    private fun <T> collectPages(
        label: String,
        pageSize: Int,
        childFactory: (Int) -> CancelableCall<CloudflarePage<T>>,
        identity: (T) -> String,
    ): CloudflareCollectionResult<T> {
        val items = mutableListOf<T>()
        val seenIds = mutableSetOf<String>()
        var completedPages = 0
        for (requestedPage in 1..maximumPages) {
            throwIfCancelled()
            val response = try {
                executeChild(childFactory(requestedPage))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return failedOrPartial(items, completedPages, safeCloudflareFailure(error))
            }
            if (response.page != null && response.page != requestedPage) {
                return failedOrPartial(
                    items,
                    completedPages,
                    invalidPaginationFailure("Cloudflare returned the wrong $label page."),
                )
            }
            completedPages += 1
            if (response.items.isEmpty()) return CloudflareCollectionResult.Complete(items.toList())
            if (response.totalPages != null && response.totalPages < requestedPage) {
                return failedOrPartial(
                    items,
                    completedPages - 1,
                    invalidPaginationFailure(
                        "Cloudflare returned inconsistent $label pagination metadata.",
                    ),
                )
            }
            val uniqueItems = response.items.filter { seenIds.add(identity(it)) }
            if (uniqueItems.isEmpty()) {
                return failedOrPartial(
                    items,
                    completedPages - 1,
                    invalidPaginationFailure(
                        "Cloudflare pagination repeated a $label page without new items.",
                    ),
                )
            }
            if (items.size + uniqueItems.size > maximumItems) {
                val available = (maximumItems - items.size).coerceAtLeast(0)
                items += uniqueItems.take(available)
                return failedOrPartial(
                    items,
                    completedPages,
                    invalidPaginationFailure(
                        "Cloudflare $label inventory exceeded the $maximumItems-item safety limit.",
                    ),
                )
            }
            items += uniqueItems

            val totalPages = response.totalPages
            if (totalPages != null && requestedPage >= totalPages) {
                return CloudflareCollectionResult.Complete(items.toList())
            }
            if (totalPages == null && response.items.size < pageSize) {
                return CloudflareCollectionResult.Complete(items.toList())
            }
        }
        return failedOrPartial(
            items,
            completedPages,
            invalidPaginationFailure(
                "Cloudflare $label pagination exceeded $maximumPages pages.",
            ),
        )
    }

    private fun credentialFingerprint(token: SecretValue): String {
        val tokenBytes = token.use { it.toByteArray(StandardCharsets.UTF_8) }
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(tokenBytes)
        } finally {
            tokenBytes.fill(0)
        }
        return try {
            digest.joinToString("") { "%02x".format(it) }.take(32)
        } finally {
            digest.fill(0)
        }
    }

    private fun <T> failedOrPartial(
        items: List<T>,
        completedPages: Int,
        failure: CloudflareFailure,
    ): CloudflareCollectionResult<T> = if (items.isEmpty() || completedPages == 0) {
        CloudflareCollectionResult.Failure(failure)
    } else {
        CloudflareCollectionResult.Partial(items.toList(), failure, completedPages)
    }
}

private abstract class TrackedCloudflareCall<T> : CancelableCall<T> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeChild = AtomicReference<CancelableCall<*>?>()

    final override fun execute(): T {
        check(started.compareAndSet(false, true)) { "A Cloudflare call can only execute once." }
        throwIfCancelled()
        return executeTracked()
    }

    protected abstract fun executeTracked(): T

    final override fun cancel() {
        cancelled.set(true)
        activeChild.getAndSet(null)?.cancel()
    }

    protected fun <V> executeChild(call: CancelableCall<V>): V {
        throwIfCancelled()
        activeChild.set(call)
        if (cancelled.get()) {
            activeChild.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("The Cloudflare request was cancelled.")
        }
        return try {
            val value = call.execute()
            throwIfCancelled()
            value
        } finally {
            activeChild.compareAndSet(call, null)
        }
    }

    protected fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The Cloudflare request was cancelled.")
    }
}

private fun <T> CloudflareCollectionResult<T>.itemsOrEmpty(): List<T> = when (this) {
    is CloudflareCollectionResult.Complete -> items
    is CloudflareCollectionResult.Partial -> items
    is CloudflareCollectionResult.Failure -> emptyList()
}

private fun CloudflareCollectionResult<*>.failureOrNull(): CloudflareFailure? = when (this) {
    is CloudflareCollectionResult.Complete -> null
    is CloudflareCollectionResult.Partial -> failure
    is CloudflareCollectionResult.Failure -> failure
}

private fun safeCloudflareFailure(error: Exception): CloudflareFailure = when (error) {
    is CloudflareApiException -> error.failure
    is CloudflareResponseFormatException -> CloudflareFailure(
        CloudflareFailureKind.INVALID_RESPONSE,
        "Cloudflare returned data the app could not parse.",
    )
    is ResponseTooLargeException -> CloudflareFailure(
        CloudflareFailureKind.INVALID_RESPONSE,
        "Cloudflare returned more data than the app can process safely.",
    )
    is UnsafeRedirectException -> CloudflareFailure(
        CloudflareFailureKind.INVALID_RESPONSE,
        "Cloudflare returned an unsafe redirect.",
    )
    is IOException -> CloudflareFailure(
        CloudflareFailureKind.NETWORK,
        "Cloudflare could not be reached. Check your connection and try again.",
    )
    is IllegalArgumentException -> CloudflareFailure(
        CloudflareFailureKind.CONFIGURATION,
        "The Cloudflare request configuration is invalid.",
    )
    else -> CloudflareFailure(
        CloudflareFailureKind.INVALID_RESPONSE,
        "Cloudflare returned an invalid response.",
    )
}

private fun invalidPaginationFailure(message: String) = CloudflareFailure(
    CloudflareFailureKind.INVALID_RESPONSE,
    message,
)
