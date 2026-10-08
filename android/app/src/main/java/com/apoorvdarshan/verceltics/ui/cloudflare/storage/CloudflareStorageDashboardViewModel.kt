package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1Database
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVNamespace
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Bucket
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Jurisdictions
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which create sheet is open (iOS `CloudflareStorageCreationSheet`). */
enum class CloudflareStorageCreation { D1, KV, R2 }

data class CloudflareStorageDashboardState(
    val databases: List<CloudflareD1Database> = emptyList(),
    val namespaces: List<CloudflareKVNamespace> = emptyList(),
    val buckets: List<CloudflareR2Bucket> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val warnings: List<String> = emptyList(),
    val creation: CloudflareStorageCreation? = null,
    val creationError: String? = null,
)

/** Port of iOS `CloudflareStorageDashboardViewModel` (D1, KV and R2 inventory plus creation). */
class CloudflareStorageDashboardViewModel(
    private val api: CloudflareStorageApi,
    val accountId: String,
    mutations: Flow<CloudflareMutationEvent>? = null,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareStorageDashboardState())
    val state: StateFlow<CloudflareStorageDashboardState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var stale = false
    private val accountPrefix = "/accounts/${CloudflareRestRequest.percentEncodeSegment(accountId)}/"

    init {
        load()
        mutations?.let { flow ->
            viewModelScope.launch {
                flow.collect { event -> if (affectsStorage(event)) stale = true }
            }
        }
    }

    /** Reloads when a storage resource changed on another screen (iOS `onChange` reconciliation). */
    fun onAppear() {
        if (stale) load(force = true)
    }

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true && !force) return
        loadJob?.cancel()
        stale = false
        val hasSnapshot = !_state.value.isLoading
        _state.update { it.copy(isLoading = !hasSnapshot, isRefreshing = hasSnapshot) }
        loadJob = viewModelScope.launch {
            val (d1, kv, r2) = coroutineScope {
                val d1 = async { capture { api.fetchD1Databases(accountId) } }
                val kv = async { capture { api.fetchKVNamespaces(accountId) } }
                val r2 = async { capture { api.fetchR2Buckets(accountId) } }
                Triple(d1.await(), kv.await(), r2.await())
            }
            val warnings = buildList {
                d1.exceptionOrNull()?.let { add("D1: ${cloudflareUserMessage(it)}") }
                kv.exceptionOrNull()?.let { add("KV: ${cloudflareUserMessage(it)}") }
                r2.exceptionOrNull()?.let { add("R2: ${cloudflareUserMessage(it)}") }
            }
            _state.update { current ->
                current.copy(
                    databases = d1.getOrNull()?.let(::sortDatabases) ?: current.databases,
                    namespaces = kv.getOrNull()?.let(::sortNamespaces) ?: current.namespaces,
                    buckets = r2.getOrNull()?.let(::sortBuckets) ?: current.buckets,
                    isLoading = false,
                    isRefreshing = false,
                    warnings = warnings,
                )
            }
        }
    }

    fun openCreation(kind: CloudflareStorageCreation) {
        _state.update { it.copy(creation = kind, creationError = null) }
    }

    fun dismissCreation() {
        _state.update { it.copy(creation = null, creationError = null) }
    }

    fun requestCreateD1(input: CloudflareD1CreateInput) {
        val name = input.name.trim()
        if (name.isEmpty()) return creationFailed("Enter a D1 database name.")
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Create this D1 database?",
                message = "Cloudflare will create $name in this account.",
                confirmLabel = "Create Database",
                resourceId = name,
                workingId = CREATE_WORKING_ID,
            ),
        ) { confirmation ->
            val database = creating { api.createD1Database(accountId, input.copy(name = name), confirmation) }
            loadJob?.cancel()
            _state.update { it.copy(databases = sortDatabases(it.databases.filterNot { db -> db.id == database.id } + database)) }
            finishCreation("D1 database created.")
        }
    }

    fun requestCreateKV(title: String) {
        val normalized = title.trim()
        if (normalized.isEmpty()) return creationFailed("Enter a KV namespace title.")
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Create this KV namespace?",
                message = "Cloudflare will create the KV namespace $normalized in this account.",
                confirmLabel = "Create Namespace",
                resourceId = normalized,
                workingId = CREATE_WORKING_ID,
            ),
        ) { confirmation ->
            val namespace = creating { api.createKVNamespace(accountId, normalized, confirmation) }
            loadJob?.cancel()
            _state.update { it.copy(namespaces = sortNamespaces(it.namespaces.filterNot { ns -> ns.id == namespace.id } + namespace)) }
            finishCreation("KV namespace created.")
        }
    }

    fun requestCreateR2(input: CloudflareR2CreateInput) {
        val name = input.name.trim()
        if (!CloudflareR2Jurisdictions.isValidBucketName(name)) {
            return creationFailed(
                "R2 bucket names must be 3–63 lowercase letters, numbers, or hyphens, and cannot start or end with a hyphen.",
            )
        }
        val scope = input.jurisdiction?.let { " in the ${it.uppercase()} jurisdiction" }.orEmpty()
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Create this R2 bucket?",
                message = "Cloudflare will create the bucket $name$scope. Bucket names cannot be changed after creation.",
                confirmLabel = "Create Bucket",
                resourceId = name,
                workingId = CREATE_WORKING_ID,
            ),
        ) { confirmation ->
            val bucket = creating { api.createR2Bucket(accountId, input.copy(name = name), confirmation) }
            loadJob?.cancel()
            _state.update { it.copy(buckets = sortBuckets(it.buckets.filterNot { b -> b.id == bucket.id } + bucket)) }
            finishCreation("R2 bucket created.")
        }
    }

    val isCreating: Boolean get() = isWorking(CREATE_WORKING_ID)

    private suspend fun <T> creating(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        _state.update { it.copy(creationError = cloudflareUserMessage(error)) }
        throw error
    }

    private fun creationFailed(message: String) {
        _state.update { it.copy(creationError = message) }
    }

    private fun finishCreation(message: String) {
        _state.update { it.copy(creation = null, creationError = null, isLoading = false, isRefreshing = false) }
        showSuccess(message)
    }

    private fun affectsStorage(event: CloudflareMutationEvent): Boolean =
        event.apiPath.startsWith(accountPrefix) && STORAGE_SEGMENTS.any { event.apiPath.startsWith(accountPrefix + it) }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    companion object {
        const val CREATE_WORKING_ID: String = "storage-create"
        private val STORAGE_SEGMENTS = listOf("d1/", "storage/kv/", "r2/")

        fun sortDatabases(values: List<CloudflareD1Database>) = values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        fun sortNamespaces(values: List<CloudflareKVNamespace>) = values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

        fun sortBuckets(values: List<CloudflareR2Bucket>) = values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        /** iOS `d1Subtitle`. */
        fun d1Subtitle(database: CloudflareD1Database): String =
            listOfNotNull(database.jurisdiction?.uppercase() ?: "GLOBAL", database.version).joinToString(" · ")

        /** iOS `r2Subtitle`. */
        fun r2Subtitle(bucket: CloudflareR2Bucket): String =
            listOfNotNull(bucket.location?.uppercase() ?: bucket.jurisdiction?.uppercase() ?: "DEFAULT", bucket.storageClass)
                .joinToString(" · ")
    }
}
