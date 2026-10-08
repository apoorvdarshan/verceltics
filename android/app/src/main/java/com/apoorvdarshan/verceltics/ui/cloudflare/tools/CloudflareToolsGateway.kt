package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountDetail
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountMember
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountOperationsSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountRole
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountAuditEvent
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerRequestBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDataset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDatasetLoader
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalogStore
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflarePermissionGrant
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareRawResponse
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsApi
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsErrors
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsException
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsFailureKind
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import com.apoorvdarshan.verceltics.data.network.runOnProviderExecutor
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** UI boundary for the Cloudflare tools. Tokens never cross it; failures carry safe messages. */
interface CloudflareToolsGateway {
    /** True for offline sample fixtures, which never send requests or read saved credentials. */
    val isOfflineSample: Boolean

    suspend fun execute(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray?,
    ): Result<CloudflareRawResponse>

    suspend fun loadCatalog(): Result<CloudflareOpenApiCatalog>

    suspend fun loadDatasets(
        scope: CloudflareGraphQLScope,
        accountId: String,
        zoneId: String?,
    ): Result<List<CloudflareGraphQLDataset>>

    suspend fun loadAccountDetail(accountId: String): Result<CloudflareAccountDetail>

    suspend fun loadAccountOperations(accountId: String): Result<CloudflareAccountOperationsSnapshot>
}

/** A safe, user-facing tools failure. */
class CloudflareToolsUiException(message: String) : Exception(message) {
    override fun toString(): String = "CloudflareToolsUiException(message=$message)"
}

/** Supplies the saved Cloudflare API token on demand, or null when Cloudflare is disconnected. */
fun interface CloudflareToolsTokenSource {
    suspend fun loadToken(): SecretValue?
}

/** Production gateway: every request uses the token saved by the Cloudflare dashboard. */
class NativeCloudflareToolsGateway(
    private val tokenSource: CloudflareToolsTokenSource,
    private val catalogStore: CloudflareOpenApiCatalogStore,
    private val api: CloudflareToolsApi = CloudflareToolsApi(),
    private val executor: Executor = CloudflareToolsServices.networkExecutor,
    private val clock: Clock = Clock.systemUTC(),
) : CloudflareToolsGateway {
    override val isOfflineSample: Boolean = false

    private val datasetLoader = CloudflareGraphQLDatasetLoader(api)

    override suspend fun execute(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray?,
    ): Result<CloudflareRawResponse> = capture {
        val token = requireToken()
        api.newRawRequestCall(token, draft, confirmation, attachedBody).awaitProviderCall(executor)
    }

    override suspend fun loadCatalog(): Result<CloudflareOpenApiCatalog> = capture {
        runOnProviderExecutor(executor) { catalogStore.load() }
    }

    override suspend fun loadDatasets(
        scope: CloudflareGraphQLScope,
        accountId: String,
        zoneId: String?,
    ): Result<List<CloudflareGraphQLDataset>> = capture {
        val token = requireToken()
        datasetLoader.newLoadCall(token, scope, accountId, zoneId).awaitProviderCall(executor)
    }

    override suspend fun loadAccountDetail(accountId: String): Result<CloudflareAccountDetail> = capture {
        api.newAccountDetailCall(requireToken(), accountId).awaitProviderCall(executor)
    }

    override suspend fun loadAccountOperations(accountId: String): Result<CloudflareAccountOperationsSnapshot> = capture {
        val token = requireToken()
        val before = clock.instant()
        val since = before.minus(Duration.ofDays(7))
        coroutineScope {
            val account = async { section { api.newAccountDetailCall(token, accountId) } }
            val members = async { section { api.newMembersCall(token, accountId) } }
            val roles = async { section { api.newRolesCall(token, accountId) } }
            val audit = async { section { api.newAuditEventsCall(token, accountId, since, before) } }
            val accountResult = account.await()
            val memberResult = members.await()
            val roleResult = roles.await()
            val auditResult = audit.await()
            CloudflareAccountOperationsSnapshot(
                account = accountResult.getOrNull(),
                members = memberResult.getOrNull().orEmpty().sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
                ),
                roles = roleResult.getOrNull().orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                auditEvents = auditResult.getOrNull().orEmpty(),
                accountError = accountResult.exceptionOrNull()?.let(CloudflareToolsErrors::message),
                membersError = memberResult.exceptionOrNull()?.let(CloudflareToolsErrors::message),
                rolesError = roleResult.exceptionOrNull()?.let(CloudflareToolsErrors::message),
                auditError = auditResult.exceptionOrNull()?.let(CloudflareToolsErrors::message),
            )
        }
    }

    private suspend fun <T> section(factory: () -> CancelableCall<T>): Result<T> = try {
        Result.success(factory().awaitProviderCall(executor))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun requireToken(): SecretValue = tokenSource.loadToken()
        ?: throw CloudflareToolsException(
            CloudflareToolsFailureKind.NOT_CONNECTED,
            "Connect a Cloudflare account first.",
        )
}

/** Process-wide catalog cache and worker pool shared by every tools gateway. */
object CloudflareToolsServices {
    val networkExecutor: Executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "verceltics-cloudflare-tools").apply { isDaemon = true }
        }
    }

    @Volatile
    private var catalogStore: CloudflareOpenApiCatalogStore? = null

    fun catalogStore(context: Context): CloudflareOpenApiCatalogStore {
        catalogStore?.let { return it }
        return synchronized(this) {
            catalogStore ?: CloudflareOpenApiCatalogStore {
                context.applicationContext.assets.open(CloudflareOpenApiCatalogStore.ASSET_NAME)
            }.also { catalogStore = it }
        }
    }
}

private suspend inline fun <T> capture(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(CloudflareToolsUiException(CloudflareToolsErrors.message(error)))
}

/**
 * Offline fixtures for the sample workspace: responses are generated locally, no request is sent
 * and no saved credential is read. The official API catalog is still the bundled asset.
 */
class SampleCloudflareToolsGateway(
    private val catalogStore: CloudflareOpenApiCatalogStore?,
    private val executor: Executor = CloudflareToolsServices.networkExecutor,
) : CloudflareToolsGateway {
    override val isOfflineSample: Boolean = true

    override suspend fun execute(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray?,
    ): Result<CloudflareRawResponse> = capture {
        // Same validation and confirmation rules as a live request, without the network.
        val request = CloudflareExplorerRequestBuilder.build(draft, confirmation, attachedBody)
        val body = """{"success":true,"errors":[],"messages":[{"code":0,"message":"Sample workspace: no request was sent to Cloudflare."}],"result":{"method":"${request.method}","path":"${request.uri.rawPath}"}}"""
        CloudflareRawResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json", "CF-Ray" to "sample-ray"),
            body = body.toByteArray(StandardCharsets.UTF_8),
            elapsedMillis = 42,
        )
    }

    override suspend fun loadCatalog(): Result<CloudflareOpenApiCatalog> = capture {
        val store = catalogStore ?: throw CloudflareToolsException(
            CloudflareToolsFailureKind.INVALID_RESPONSE,
            "The bundled Cloudflare API catalog is missing.",
        )
        runOnProviderExecutor(executor) { store.load() }
    }

    override suspend fun loadDatasets(
        scope: CloudflareGraphQLScope,
        accountId: String,
        zoneId: String?,
    ): Result<List<CloudflareGraphQLDataset>> = Result.success(
        when (scope) {
            CloudflareGraphQLScope.ZONE -> listOf(
                CloudflareGraphQLDataset("httpRequests1dGroups", "HTTP requests grouped by day.", true, listOf("date", "sum_requests", "sum_bytes", "uniq_uniques"), 2_678_400, 31_536_000, 10_000, 30),
                CloudflareGraphQLDataset("httpRequestsAdaptiveGroups", "Adaptive HTTP request groups.", true, listOf("count", "dimensions_datetime", "dimensions_clientCountryName"), 86_400, 2_678_400, 10_000, 30),
                CloudflareGraphQLDataset("firewallEventsAdaptive", "Security events.", false, emptyList(), null, null, null, null),
            )
            CloudflareGraphQLScope.ACCOUNT -> listOf(
                CloudflareGraphQLDataset("workersInvocationsAdaptive", "Worker invocations.", true, listOf("dimensions_scriptName", "sum_requests", "sum_errors"), 2_678_400, 7_776_000, 10_000, 30),
            )
        },
    )

    override suspend fun loadAccountDetail(accountId: String): Result<CloudflareAccountDetail> =
        Result.success(sampleAccount(accountId))

    override suspend fun loadAccountOperations(accountId: String): Result<CloudflareAccountOperationsSnapshot> {
        val role = CloudflareAccountRole(
            id = "sample-role-admin",
            name = "Administrator",
            description = "Can access the full account, except for membership management and billing.",
            permissions = mapOf(
                "dns_records" to CloudflarePermissionGrant(read = true, write = true),
                "analytics" to CloudflarePermissionGrant(read = true, write = false),
            ),
        )
        return Result.success(
            CloudflareAccountOperationsSnapshot(
                account = sampleAccount(accountId),
                members = listOf(
                    CloudflareAccountMember(
                        id = "sample-member",
                        email = "apoorv@example.com",
                        status = "accepted",
                        user = CloudflareAccountMember.User("sample-user", "apoorv@example.com", "Apoorv", "Sample", true),
                        roles = listOf(role),
                        policies = emptyList(),
                    ),
                ),
                roles = listOf(role),
                auditEvents = listOf(
                    CloudflareAccountAuditEvent(
                        eventId = "sample-event", accountId = accountId, accountName = "Studio workspace",
                        actionDescription = "Updated DNS record", actionResult = "success",
                        actionTime = "2026-09-29T12:30:00Z", actionType = "update",
                        actorContext = "api_token", actorEmail = "apoorv@example.com", actorId = "sample-user",
                        actorIpAddress = "192.0.2.10", actorTokenId = null, actorTokenName = "Sample token",
                        actorType = "user", rawCfRayId = "sample-ray", rawMethod = "PATCH", rawStatusCode = 200,
                        rawUri = "/client/v4/zones/sample-zone-0/dns_records/sample", rawUserAgent = "Verceltics",
                        resourceId = "sample", resourceProduct = "dns", resourceType = "dns_record",
                        resourceScope = null, zoneId = "sample-zone-0", zoneName = "studio.example",
                    ),
                ),
                accountError = null,
                membersError = null,
                rolesError = null,
                auditError = null,
            ),
        )
    }

    private fun sampleAccount(accountId: String) = CloudflareAccountDetail(
        id = accountId,
        name = "Studio workspace",
        type = "standard",
        createdOn = "2024-04-02T09:15:00Z",
        enforceTwoFactor = true,
        abuseContactEmail = "abuse@example.com",
        managedByParentOrganizationId = null,
        managedByParentOrganizationName = null,
    )
}
