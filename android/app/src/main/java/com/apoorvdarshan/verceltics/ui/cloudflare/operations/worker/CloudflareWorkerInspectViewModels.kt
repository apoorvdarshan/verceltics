package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerContent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerVersionDetailInfo
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflareWorkerVersionState(
    val detail: CloudflareWorkerVersionDetailInfo? = null,
    val isLoading: Boolean = true,
    /** A reload over an already loaded version (pull-to-refresh / toolbar refresh). */
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/** Port of iOS `CloudflareWorkerVersionDetailViewModel`. Read-only. */
class CloudflareWorkerVersionViewModel(
    private val api: CloudflareWorkerOperationsApi,
    val accountId: String,
    val scriptName: String,
    val versionId: String,
    private val cache: CloudflareWorkerMemoryCache = CloudflareWorkerMemoryCache.shared,
) : CloudflareOperationsViewModel() {
    private val key = CloudflareWorkerMemoryCache.versionKey(accountId, scriptName, versionId)
    private val _state = MutableStateFlow(
        cache.get<CloudflareWorkerVersionDetailInfo>(key)?.first
            ?.let { CloudflareWorkerVersionState(detail = it, isLoading = false) }
            ?: CloudflareWorkerVersionState(),
    )
    val state: StateFlow<CloudflareWorkerVersionState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean) {
        cache.get<CloudflareWorkerVersionDetailInfo>(key)?.let { (detail, fresh) ->
            _state.update { it.copy(detail = detail, isLoading = false) }
            if (!forceRefresh && fresh) return
        }
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.detail == null, isRefreshing = it.detail != null, error = null) }
            try {
                val detail = api.fetchVersion(accountId, scriptName, versionId)
                cache.put(key, detail)
                _state.value = CloudflareWorkerVersionState(detail = detail, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = if (it.detail == null) cloudflareUserMessage(error) else null)
                }
            }
        }
    }
}

data class CloudflareWorkerContentState(
    val content: CloudflareWorkerContent? = null,
    val isLoading: Boolean = true,
    /** A reload over already loaded source (pull-to-refresh / toolbar refresh). */
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Port of iOS `CloudflareWorkerContentViewModel`. Worker source can contain secrets, so it is held
 * only in this ViewModel (cleared when the screen closes) and never cached or persisted.
 */
class CloudflareWorkerContentViewModel(
    private val api: CloudflareWorkerOperationsApi,
    val accountId: String,
    val scriptName: String,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareWorkerContentState())
    val state: StateFlow<CloudflareWorkerContentState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.content == null, isRefreshing = it.content != null, error = null) }
            try {
                val content = api.fetchContent(accountId, scriptName)
                _state.value = CloudflareWorkerContentState(content = content, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = if (it.content == null) cloudflareUserMessage(error) else null)
                }
            }
        }
    }

    override fun onCleared() {
        _state.value = CloudflareWorkerContentState(isLoading = false)
        super.onCleared()
    }
}
