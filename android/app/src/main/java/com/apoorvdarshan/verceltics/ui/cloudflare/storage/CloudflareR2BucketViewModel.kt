package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Bucket
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Configuration
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Object
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A file picked for upload, waiting for the user to confirm its object key. */
data class CloudflareR2UploadDraft(
    val file: CloudflarePickedFile,
    val key: String,
    val contentType: String,
    val error: String? = null,
)

sealed interface CloudflareR2ConfigurationState {
    data object Loading : CloudflareR2ConfigurationState

    data class Loaded(val json: String) : CloudflareR2ConfigurationState

    data class Failed(val message: String) : CloudflareR2ConfigurationState
}

data class CloudflareR2BucketState(
    val bucket: CloudflareR2Bucket,
    val isRefreshing: Boolean = false,
    val prefix: String = "",
    val objects: List<CloudflareR2Object> = emptyList(),
    val folders: List<String> = emptyList(),
    val nextCursor: String? = null,
    val isLoadingObjects: Boolean = true,
    val isLoadingMore: Boolean = false,
    val objectsError: String? = null,
    val upload: CloudflareR2UploadDraft? = null,
    val configurations: Map<CloudflareR2Configuration, CloudflareR2ConfigurationState> = emptyMap(),
    val didDelete: Boolean = false,
)

/**
 * Port of iOS `CloudflareR2BucketViewModel` plus typed object browsing, upload, download and delete
 * (iOS reaches those through API-explorer presets).
 */
class CloudflareR2BucketViewModel(
    private val api: CloudflareStorageApi,
    private val files: CloudflareStorageFiles?,
    val accountId: String,
    bucketName: String,
    jurisdiction: String?,
) : CloudflareOperationsViewModel() {
    val bucketName: String = bucketName
    val jurisdiction: String? = jurisdiction?.takeIf { it.isNotEmpty() && it != "default" }

    private val _state = MutableStateFlow(
        CloudflareR2BucketState(CloudflareR2Bucket(bucketName, null, this.jurisdiction, null, null)),
    )
    val state: StateFlow<CloudflareR2BucketState> = _state.asStateFlow()

    private var bucketJob: Job? = null
    private var objectsJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadBucket()
        loadObjects(_state.value.prefix)
    }

    private fun loadBucket() {
        if (bucketJob?.isActive == true) return
        _state.update { it.copy(isRefreshing = true) }
        bucketJob = viewModelScope.launch {
            try {
                val bucket = api.fetchR2Bucket(accountId, bucketName, jurisdiction)
                _state.update { it.copy(bucket = bucket, isRefreshing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isRefreshing = false) }
                showError(error)
            }
        }
    }

    /** Lists the objects and folders directly under [prefix]. */
    fun loadObjects(prefix: String) {
        objectsJob?.cancel()
        _state.update {
            it.copy(prefix = prefix, isLoadingObjects = true, isLoadingMore = false, objectsError = null).let { next ->
                if (it.prefix == prefix) next else next.copy(objects = emptyList(), folders = emptyList(), nextCursor = null)
            }
        }
        objectsJob = viewModelScope.launch {
            try {
                val listing = api.listR2Objects(accountId, bucketName, jurisdiction, prefix = prefix)
                _state.update {
                    it.copy(
                        objects = listing.objects,
                        folders = listing.prefixes,
                        nextCursor = listing.nextCursor,
                        isLoadingObjects = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoadingObjects = false, objectsError = cloudflareUserMessage(error)) }
            }
        }
    }

    fun loadMoreObjects() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.isLoadingMore || current.isLoadingObjects) return
        _state.update { it.copy(isLoadingMore = true) }
        objectsJob = viewModelScope.launch {
            try {
                val listing = api.listR2Objects(accountId, bucketName, jurisdiction, prefix = current.prefix, cursor = cursor)
                _state.update {
                    it.copy(
                        objects = (it.objects + listing.objects).distinctBy(CloudflareR2Object::key),
                        folders = (it.folders + listing.prefixes).distinct(),
                        nextCursor = listing.nextCursor,
                        isLoadingMore = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoadingMore = false) }
                showError(error)
            }
        }
    }

    fun openFolder(prefix: String) = loadObjects(prefix)

    /** Moves one folder up (`a/b/` → `a/`). */
    fun openParentFolder() {
        val prefix = _state.value.prefix.trimEnd('/')
        loadObjects(if (prefix.contains('/')) prefix.substringBeforeLast('/') + "/" else "")
    }

    fun loadConfiguration(configuration: CloudflareR2Configuration) {
        _state.update { it.copy(configurations = it.configurations + (configuration to CloudflareR2ConfigurationState.Loading)) }
        viewModelScope.launch {
            val result = try {
                CloudflareR2ConfigurationState.Loaded(
                    CloudflareStorageApi.prettyJson(api.fetchR2BucketConfiguration(accountId, bucketName, jurisdiction, configuration)),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CloudflareR2ConfigurationState.Failed(cloudflareUserMessage(error))
            }
            _state.update { it.copy(configurations = it.configurations + (configuration to result)) }
        }
    }

    // MARK: Upload

    /** Reads the SAF document at [uri] and opens the upload sheet with a suggested key. */
    fun prepareUpload(uri: String) {
        val reader = files ?: return
        viewModelScope.launch {
            try {
                val file = reader.read(uri, CloudflareStorageApi.MAXIMUM_R2_UPLOAD_BYTES)
                _state.update {
                    it.copy(
                        upload = CloudflareR2UploadDraft(
                            file = file,
                            key = it.prefix + file.name,
                            contentType = file.mimeType ?: "application/octet-stream",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun updateUpload(transform: (CloudflareR2UploadDraft) -> CloudflareR2UploadDraft) {
        _state.update { state -> state.upload?.let { state.copy(upload = transform(it)) } ?: state }
    }

    fun dismissUpload() {
        _state.update { it.copy(upload = null) }
    }

    fun requestUpload() {
        val draft = _state.value.upload ?: return
        val key = draft.key
        if (key.isEmpty()) return updateUpload { it.copy(error = "Enter an object key.") }
        val replaces = _state.value.objects.any { it.key == key }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Upload this object?",
                message = "${draft.file.name} (${CloudflareFormat.bytes(draft.file.size.toLong())}) will be uploaded to " +
                    "$bucketName as $key." + if (replaces) " The existing object with this key will be replaced." else "",
                confirmLabel = if (replaces) "Replace Object" else "Upload Object",
                resourceId = key,
                destructive = replaces,
                workingId = UPLOAD_WORKING_ID,
            ),
        ) { confirmation ->
            try {
                api.uploadR2Object(accountId, bucketName, jurisdiction, key, draft.file.data(), draft.contentType, confirmation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateUpload { it.copy(error = cloudflareUserMessage(error)) }
                throw error
            }
            _state.update { it.copy(upload = null) }
            showSuccess("Uploaded $key.")
            loadObjects(_state.value.prefix)
        }
    }

    // MARK: Download and delete

    /** Downloads [objectKey] into the SAF document [destinationUri] the user created. */
    fun download(objectKey: String, destinationUri: String) {
        val writer = files ?: return
        runMutation(downloadWorkingId(objectKey)) {
            val data = api.downloadR2Object(accountId, bucketName, jurisdiction, objectKey)
            val bytes = data.data()
            try {
                writer.write(destinationUri, bytes)
            } finally {
                bytes.fill(0)
            }
            showSuccess("Saved $objectKey (${CloudflareFormat.bytes(data.size.toLong())}).")
        }
    }

    fun requestDeleteObject(item: CloudflareR2Object) {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this object?",
                message = "${item.key} will be permanently deleted from $bucketName.",
                confirmLabel = "Delete Object",
                resourceId = item.key,
                destructive = true,
            ),
        ) { confirmation ->
            api.deleteR2Object(accountId, bucketName, jurisdiction, item.key, confirmation)
            _state.update { state -> state.copy(objects = state.objects.filterNot { it.key == item.key }) }
            showSuccess("Deleted ${item.key}.")
        }
    }

    fun requestDeleteBucket() {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this R2 bucket?",
                message = "Cloudflare only deletes an empty bucket. $bucketName and all of its configuration will be permanently removed.",
                confirmLabel = "Delete Bucket",
                resourceId = bucketName,
                destructive = true,
                workingId = DELETE_WORKING_ID,
            ),
        ) { confirmation ->
            bucketJob?.cancel()
            objectsJob?.cancel()
            api.deleteR2Bucket(accountId, bucketName, jurisdiction, confirmation)
            _state.update { it.copy(didDelete = true, isRefreshing = false) }
        }
    }

    companion object {
        const val UPLOAD_WORKING_ID: String = "r2-upload"
        const val DELETE_WORKING_ID: String = "r2-bucket-delete"

        fun downloadWorkingId(key: String): String = "download:$key"

        /** Folder label for a listed prefix (`photos/2026/` → `2026/`). */
        fun folderName(prefix: String, parent: String): String = prefix.removePrefix(parent).ifEmpty { prefix }

        fun objectName(key: String, parent: String): String = key.removePrefix(parent).ifEmpty { key }

        fun objectSubtitle(item: CloudflareR2Object): String = listOfNotNull(
            item.size?.let { CloudflareFormat.bytes(it) },
            item.contentType,
            item.modifiedDate?.let { com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates.formatDateTime(it) },
        ).joinToString(" · ").ifEmpty { "Object" }
    }
}
