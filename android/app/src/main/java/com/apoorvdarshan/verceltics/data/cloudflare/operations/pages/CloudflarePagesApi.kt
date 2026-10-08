package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflarePaginationGuard
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRequestAuth
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.requireCloudflareConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Every iOS Cloudflare Pages operation: project detail, deployments, logs, retry, rollback and delete
 * (`CloudflareAPI`), project settings, build cache, custom domains (`CloudflarePagesOperationsAPI`)
 * and the Wrangler-compatible direct upload (`CloudflarePagesDirectUpload`).
 *
 * Every mutation verifies its [CloudflareMutationConfirmation] against the same resource iOS uses
 * before building any request.
 */
class CloudflarePagesApi(
    private val client: CloudflareRestClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val boundaryFactory: () -> String = { "Verceltics-" + UUID.randomUUID().toString().uppercase() },
    private val maximumBucketBytes: Int = CloudflarePagesUploadPackage.ANDROID_MAXIMUM_BUCKET_BYTES,
) {
    // MARK: Paths (confirmation resources use the same encoded paths the requests use)

    fun projectSegments(accountId: String, projectName: String): List<String> =
        listOf("accounts", accountId, "pages", "projects", projectName)

    fun projectPath(accountId: String, projectName: String): String = path(projectSegments(accountId, projectName))

    fun deploymentsPath(accountId: String, projectName: String): String =
        path(projectSegments(accountId, projectName) + "deployments")

    fun purgeBuildCachePath(accountId: String, projectName: String): String =
        path(projectSegments(accountId, projectName) + "purge_build_cache")

    fun domainsPath(accountId: String, projectName: String): String = path(projectSegments(accountId, projectName) + "domains")

    fun domainPath(accountId: String, projectName: String, domainName: String): String =
        path(projectSegments(accountId, projectName) + listOf("domains", domainName))

    private fun path(segments: List<String>): String = CloudflareRestRequest(CloudflareHttpMethod.GET, segments).apiPath

    private fun deploymentSegments(accountId: String, projectName: String, deploymentId: String) =
        projectSegments(accountId, projectName) + listOf("deployments", deploymentId)

    // MARK: Project and deployments (CloudflareAPI.swift)

    suspend fun fetchProject(accountId: String, projectName: String): CloudflarePagesProjectDetail =
        CloudflarePagesParser.project(client.result(get(projectSegments(accountId, projectName))))

    suspend fun fetchDeployments(
        accountId: String,
        projectName: String,
        environment: CloudflarePagesEnvironment? = null,
    ): List<CloudflarePagesDeployment> = CloudflarePagesParser.deployments(
        client.allPages(
            pathSegments = projectSegments(accountId, projectName) + "deployments",
            query = listOfNotNull(environment?.let { "env" to it.wireValue }),
            perPage = 20,
        ),
    )

    suspend fun fetchDeployment(accountId: String, projectName: String, deploymentId: String): CloudflarePagesDeployment =
        CloudflarePagesParser.deployment(client.result(get(deploymentSegments(accountId, projectName, deploymentId))))

    suspend fun fetchDeploymentLogs(accountId: String, projectName: String, deploymentId: String): List<CloudflarePagesDeploymentLog> =
        CloudflarePagesParser.logs(
            client.result(get(deploymentSegments(accountId, projectName, deploymentId) + listOf("history", "logs"))),
        )

    suspend fun retryDeployment(
        accountId: String,
        projectName: String,
        deploymentId: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflarePagesDeployment {
        requireCloudflareConfirmation(confirmation, deploymentId)
        return CloudflarePagesParser.deployment(
            client.result(CloudflareRestRequest(CloudflareHttpMethod.POST, deploymentSegments(accountId, projectName, deploymentId) + "retry")),
        )
    }

    suspend fun rollbackDeployment(
        accountId: String,
        projectName: String,
        deploymentId: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflarePagesDeployment {
        requireCloudflareConfirmation(confirmation, deploymentId)
        return CloudflarePagesParser.deployment(
            client.result(CloudflareRestRequest(CloudflareHttpMethod.POST, deploymentSegments(accountId, projectName, deploymentId) + "rollback")),
        )
    }

    suspend fun deleteDeployment(
        accountId: String,
        projectName: String,
        deploymentId: String,
        confirmation: CloudflareMutationConfirmation,
        force: Boolean = false,
    ) {
        requireCloudflareConfirmation(confirmation, deploymentId)
        client.send(
            CloudflareRestRequest(
                CloudflareHttpMethod.DELETE,
                deploymentSegments(accountId, projectName, deploymentId),
                query = if (force) listOf("force" to "true") else emptyList(),
            ),
        )
    }

    // MARK: Project settings (CloudflarePagesOperationsAPI.swift)

    suspend fun updateProject(
        accountId: String,
        projectName: String,
        draft: CloudflarePagesProjectEditDraft,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflarePagesProjectDetail {
        requireCloudflareConfirmation(confirmation, projectPath(accountId, projectName))
        if (draft.productionBranch.isBlank()) {
            throw CloudflareOperationException.invalidRequest("Production branch cannot be empty.")
        }
        return CloudflarePagesParser.project(
            client.result(CloudflareRestRequest.json(CloudflareHttpMethod.PATCH, projectSegments(accountId, projectName), draft.requestBody())),
        )
    }

    suspend fun purgeBuildCache(accountId: String, projectName: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, purgeBuildCachePath(accountId, projectName))
        client.send(CloudflareRestRequest(CloudflareHttpMethod.POST, projectSegments(accountId, projectName) + "purge_build_cache"))
    }

    suspend fun deleteProject(accountId: String, projectName: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, projectPath(accountId, projectName))
        client.send(CloudflareRestRequest(CloudflareHttpMethod.DELETE, projectSegments(accountId, projectName)))
    }

    // MARK: Custom domains

    /** iOS domain walk: 20 per page, stopping on total pages, total count, or a short page. */
    suspend fun fetchDomains(accountId: String, projectName: String): List<CloudflarePagesCustomDomain> {
        val guard = CloudflarePaginationGuard()
        val domains = mutableListOf<CloudflarePagesCustomDomain>()
        var page = 1
        while (true) {
            val envelope = client.envelope(
                CloudflareRestRequest(
                    CloudflareHttpMethod.GET,
                    projectSegments(accountId, projectName) + "domains",
                    query = listOf("page" to page.toString(), "per_page" to "20"),
                ),
            )
            val batch = envelope.result?.arrayValue.orEmpty()
            guard.record(batch.size, if (batch.isEmpty()) null else batch.hashCode())
            domains += batch.map(CloudflarePagesParser::domain)
            val totalPages = envelope.resultInfo?.totalPages
            val totalCount = envelope.resultInfo?.totalCount
            when {
                totalPages != null -> if (page >= totalPages) break
                totalCount != null -> if (domains.size >= totalCount) break
                batch.size < 20 -> break
            }
            page += 1
        }
        return domains
    }

    suspend fun fetchDomain(accountId: String, projectName: String, domainName: String): CloudflarePagesCustomDomain =
        CloudflarePagesParser.domain(client.result(get(projectSegments(accountId, projectName) + listOf("domains", domainName))))

    suspend fun addDomain(
        accountId: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflarePagesCustomDomain {
        requireCloudflareConfirmation(confirmation, domainsPath(accountId, projectName))
        val name = normalizeDomainName(domainName)
        return CloudflarePagesParser.domain(
            client.result(
                CloudflareRestRequest.json(
                    CloudflareHttpMethod.POST,
                    projectSegments(accountId, projectName) + "domains",
                    ProviderJsonValue.from(mapOf("name" to name)),
                ),
            ),
        )
    }

    suspend fun retryDomainValidation(
        accountId: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflarePagesCustomDomain {
        requireCloudflareConfirmation(confirmation, domainPath(accountId, projectName, domainName))
        return CloudflarePagesParser.domain(
            client.result(CloudflareRestRequest(CloudflareHttpMethod.PATCH, projectSegments(accountId, projectName) + listOf("domains", domainName))),
        )
    }

    suspend fun deleteDomain(
        accountId: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation,
    ) {
        requireCloudflareConfirmation(confirmation, domainPath(accountId, projectName, domainName))
        client.send(CloudflareRestRequest(CloudflareHttpMethod.DELETE, projectSegments(accountId, projectName) + listOf("domains", domainName)))
    }

    // MARK: Direct upload (CloudflarePagesDirectUpload.swift)

    /**
     * Wrangler's three-phase contract: obtain a project upload JWT, upload only the BLAKE3 assets
     * Cloudflare is missing, then create a deployment whose manifest references every asset.
     */
    suspend fun directUpload(
        accountId: String,
        projectName: String,
        folder: CloudflarePagesBuildFolder,
        options: CloudflarePagesDirectUploadOptions,
        confirmation: CloudflareMutationConfirmation,
        progress: (CloudflarePagesDirectUploadProgress) -> Unit = {},
    ): CloudflarePagesDirectUploadResult {
        val deploymentPath = deploymentsPath(accountId, projectName)
        requireCloudflareConfirmation(confirmation, deploymentPath)
        val projectSegments = projectSegments(accountId, projectName)

        progress(CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.AUTHORIZING, 0, 0))
        var uploadJwt = fetchUploadToken(projectSegments)
        val fileLimit = CloudflarePagesUploadTokenClaims.maximumFileCount(uploadJwt)
        val uploadPackage = withContext(ioDispatcher) {
            CloudflarePagesUploadPackage.prepare(folder, fileLimit, progress) { ensureActive() }
        }

        currentCoroutineContext().ensureActive()
        progress(CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.CHECKING, 0, uploadPackage.assets.size))
        val unique = uploadPackage.uniqueAssets
        val missing = assetRequest(CHECK_MISSING, hashListBody(unique.map { it.hash }), uploadJwt, projectSegments)
        uploadJwt = missing.second
        val missingHashes = assetResult(missing.first).arrayValue?.mapNotNull { it.stringValue }?.toSet()
            ?: throw CloudflareOperationException.decoding()
        val filesToUpload = unique.filter { it.hash in missingHashes }
            .sortedWith(compareByDescending<CloudflarePagesPreparedAsset> { it.size }.thenBy { it.relativePath })
        val reusedCount = unique.size - filesToUpload.size

        var uploadedCount = 0
        for (bucket in CloudflarePagesUploadPackage.buckets(filesToUpload, maximumBucketBytes)) {
            currentCoroutineContext().ensureActive()
            val body = withContext(ioDispatcher) { uploadBody(folder, bucket) }
            val uploaded = try {
                assetRequest(UPLOAD, body, uploadJwt, projectSegments)
            } finally {
                body.fill(0)
            }
            uploadJwt = uploaded.second
            validateAssetResponse(uploaded.first, "Cloudflare Pages rejected the asset upload.")
            uploadedCount += bucket.size
            progress(
                CloudflarePagesDirectUploadProgress(
                    CloudflarePagesDirectUploadProgress.Stage.UPLOADING,
                    reusedCount + uploadedCount,
                    unique.size,
                ),
            )
        }

        // The hash cache update is an optimization only; Wrangler and iOS continue when it fails.
        try {
            val upsert = assetRequest(UPSERT_HASHES, hashListBody(unique.map { it.hash }), uploadJwt, projectSegments, maximumAttempts = 2)
            validateAssetResponse(upsert.first, "Cloudflare Pages rejected the asset upload.")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Asset bodies were already accepted; continue to deployment.
        }

        currentCoroutineContext().ensureActive()
        progress(CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.DEPLOYING, unique.size, unique.size))
        val multipart = CloudflarePagesMultipartBody(boundaryFactory())
        multipart.appendText("manifest", uploadPackage.manifestJson())
        options.branch?.let { multipart.appendText("branch", it) }
        options.commitMessage?.let {
            multipart.appendText("commit_message", CloudflarePagesUploadPackage.truncateUtf8(it, maximumBytes = 384))
        }
        multipart.appendText("commit_dirty", "false")
        uploadPackage.specialFiles.forEach { multipart.appendFile(it.fieldName, it.fileName, it.contentType, it.data) }
        val (bodyBytes, contentType) = multipart.finalized()
        val deployment = try {
            CloudflarePagesParser.deployment(
                client.result(
                    CloudflareRestRequest(
                        method = CloudflareHttpMethod.POST,
                        pathSegments = projectSegments + "deployments",
                        body = bodyBytes,
                        contentType = contentType,
                        readTimeoutMillis = UPLOAD_READ_TIMEOUT_MILLIS,
                    ),
                ),
            )
        } finally {
            bodyBytes.fill(0)
        }
        return CloudflarePagesDirectUploadResult(
            deployment = deployment,
            assetCount = uploadPackage.assets.size,
            uploadedAssetCount = uploadedCount,
            reusedAssetCount = reusedCount,
        )
    }

    private suspend fun fetchUploadToken(projectSegments: List<String>): String {
        val result = client.result(get(projectSegments + "upload-token"))
        val jwt = result.str("jwt")?.trim().orEmpty()
        if (jwt.isEmpty() || '\r' in jwt || '\n' in jwt) throw CloudflareOperationException.decoding()
        return jwt
    }

    /**
     * iOS `pagesDirectUploadAssetRequest`: refreshes the JWT on 401 and retries 408/429/5xx with
     * exponential backoff. Only the three Pages asset endpoints may use the upload JWT.
     */
    private suspend fun assetRequest(
        endpoint: List<String>,
        body: ByteArray,
        initialJwt: String,
        projectSegments: List<String>,
        maximumAttempts: Int = 4,
    ): Pair<CloudflareRestResponse, String> {
        require(endpoint in ASSET_ENDPOINTS) { "The Pages asset upload request is invalid." }
        var jwt = initialJwt
        var lastError: Exception? = null
        for (attempt in 0 until maximumAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val token = SecretValue.of(jwt)
                val response = client.executeSuccessful(
                    CloudflareRestRequest(
                        method = CloudflareHttpMethod.POST,
                        pathSegments = endpoint,
                        body = body,
                        contentType = CloudflareRestRequest.JSON_CONTENT_TYPE,
                        auth = CloudflareRequestAuth.PagesUploadToken(token),
                        readTimeoutMillis = UPLOAD_READ_TIMEOUT_MILLIS,
                    ),
                )
                return response to jwt
            } catch (error: CancellationException) {
                throw error
            } catch (error: CloudflareOperationException) {
                lastError = error
                if (error.kind == CloudflareOperationException.Kind.INVALID_CREDENTIALS) {
                    jwt = fetchUploadToken(projectSegments)
                } else if (attempt + 1 >= maximumAttempts || !isRetryable(error)) {
                    throw error
                }
            }
            if (attempt + 1 < maximumAttempts) sleep((1L shl minOf(attempt, 3)) * 1_000L)
        }
        throw lastError ?: CloudflareOperationException.network("The Pages asset upload failed.")
    }

    private fun isRetryable(error: CloudflareOperationException): Boolean {
        if (error.kind != CloudflareOperationException.Kind.REQUEST_FAILED) return false
        val status = error.statusCode ?: return false
        return status == 408 || status == 429 || status in 500..599
    }

    private fun assetResult(response: CloudflareRestResponse): ProviderJsonValue {
        val envelope = CloudflareRestClient.parseEnvelope(response)
        if (!envelope.success) {
            if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
            throw CloudflareOperationException.requestFailed(response.statusCode, "Cloudflare Pages rejected the upload request.")
        }
        return envelope.result?.takeUnless { it.isNull } ?: throw CloudflareOperationException.decoding()
    }

    private fun validateAssetResponse(response: CloudflareRestResponse, rejection: String) {
        if (response.isSuccessful && response.size == 0) return
        val envelope = CloudflareRestClient.parseEnvelope(response)
        if (!envelope.success) {
            if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
            throw CloudflareOperationException.requestFailed(response.statusCode, rejection)
        }
    }

    private fun hashListBody(hashes: List<String>): ByteArray =
        ProviderJsonWriter.writeBytes(ProviderJsonValue.from(mapOf("hashes" to hashes)))

    /** `[{"key":hash,"value":base64,"metadata":{"contentType":...},"base64":true}]`, streamed to bytes. */
    internal fun uploadBody(folder: CloudflarePagesBuildFolder, bucket: List<CloudflarePagesPreparedAsset>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write('['.code)
        bucket.forEachIndexed { index, asset ->
            if (index > 0) output.write(','.code)
            val data = CloudflarePagesUploadPackage.readAsset(folder, asset.entry, asset.relativePath)
            try {
                output.writeUtf8("{\"key\":${quoted(asset.hash)},\"value\":\"")
                output.write(Base64.getEncoder().encode(data))
                output.writeUtf8("\",\"metadata\":{\"contentType\":${quoted(asset.contentType)}},\"base64\":true}")
            } finally {
                data.fill(0)
            }
        }
        output.write(']'.code)
        return output.toByteArray()
    }

    private fun quoted(value: String): String = ProviderJsonWriter.write(ProviderJsonValue.Str(value))

    private fun ByteArrayOutputStream.writeUtf8(value: String) = write(value.toByteArray(StandardCharsets.UTF_8))

    private fun get(segments: List<String>) = CloudflareRestRequest(CloudflareHttpMethod.GET, segments)

    companion object {
        val CHECK_MISSING: List<String> = listOf("pages", "assets", "check-missing")
        val UPLOAD: List<String> = listOf("pages", "assets", "upload")
        val UPSERT_HASHES: List<String> = listOf("pages", "assets", "upsert-hashes")
        private val ASSET_ENDPOINTS = setOf(CHECK_MISSING, UPLOAD, UPSERT_HASHES)
        private const val UPLOAD_READ_TIMEOUT_MILLIS = 180_000

        /** iOS add-domain validation: trimmed, lowercased, no spaces, contains a dot. */
        fun normalizeDomainName(raw: String): String {
            val name = raw.trim().lowercase()
            if (name.isEmpty() || ' ' in name || '.' !in name) {
                throw CloudflareOperationException.invalidRequest("Enter a valid hostname such as www.example.com.")
            }
            return name
        }
    }
}
