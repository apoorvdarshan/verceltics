package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerDeploymentInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerScriptDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflareWorkerDetailState(
    val worker: CloudflareWorkerScriptDetail? = null,
    val deployments: List<CloudflareWorkerDeploymentInfo> = emptyList(),
    /** True only before the first deployment snapshot exists (iOS `isLoading`). */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val workerError: String? = null,
    val deploymentsError: String? = null,
    val didDeleteWorker: Boolean = false,
)

/** Port of iOS `CloudflareWorkerDetailViewModel`. */
class CloudflareWorkerDetailViewModel(
    private val api: CloudflareWorkerOperationsApi,
    val accountId: String,
    val scriptName: String,
    mutations: Flow<CloudflareMutationEvent>? = null,
    private val cache: CloudflareWorkerMemoryCache = CloudflareWorkerMemoryCache.shared,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CloudflareWorkerDetailState> = _state.asStateFlow()

    private var workerJob: Job? = null
    private var deploymentsJob: Job? = null
    private var deletingWorker = false
    private val pathPrefix = CloudflareWorkerRoutes.scriptApiPathPrefix(accountId, scriptName)

    init {
        load(forceRefresh = false)
        if (mutations != null) {
            viewModelScope.launch {
                mutations.collect { event ->
                    // Changes made from Worker operations (deploys, settings) refresh this detail.
                    if (!deletingWorker && !_state.value.didDeleteWorker && event.apiPath.startsWith(pathPrefix)) {
                        load(forceRefresh = true)
                    }
                }
            }
        }
    }

    fun load(forceRefresh: Boolean) {
        loadWorker(forceRefresh)
        loadDeployments(forceRefresh)
    }

    private fun initialState(): CloudflareWorkerDetailState {
        val worker = cache.get<CloudflareWorkerScriptDetail>(CloudflareWorkerMemoryCache.workerKey(accountId, scriptName))?.first
        val deployments = cache.get<List<CloudflareWorkerDeploymentInfo>>(CloudflareWorkerMemoryCache.deploymentsKey(accountId, scriptName))?.first
        return CloudflareWorkerDetailState(
            worker = worker,
            deployments = deployments.orEmpty(),
            isLoading = deployments == null,
        )
    }

    private fun loadWorker(forceRefresh: Boolean) {
        val key = CloudflareWorkerMemoryCache.workerKey(accountId, scriptName)
        cache.get<CloudflareWorkerScriptDetail>(key)?.let { (worker, fresh) ->
            _state.update { it.copy(worker = worker, workerError = null) }
            if (!forceRefresh && fresh) return
        }
        if (workerJob?.isActive == true) return
        workerJob = viewModelScope.launch {
            _state.update { it.copy(workerError = null) }
            try {
                val worker = api.fetchWorkerScript(accountId, scriptName)
                cache.put(key, worker)
                _state.update { it.copy(worker = worker) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(workerError = cloudflareUserMessage(error)) }
            }
        }
    }

    private fun loadDeployments(forceRefresh: Boolean) {
        val key = CloudflareWorkerMemoryCache.deploymentsKey(accountId, scriptName)
        cache.get<List<CloudflareWorkerDeploymentInfo>>(key)?.let { (deployments, fresh) ->
            _state.update { it.copy(deployments = deployments, isLoading = false) }
            if (!forceRefresh && fresh) return
        }
        if (deploymentsJob?.isActive == true) return
        deploymentsJob = viewModelScope.launch {
            val hasSnapshot = !_state.value.isLoading
            _state.update { it.copy(isLoading = !hasSnapshot, isRefreshing = hasSnapshot, deploymentsError = null) }
            try {
                val deployments = api.fetchDeployments(accountId, scriptName)
                cache.put(key, deployments)
                _state.update { it.copy(deployments = deployments, isLoading = false, isRefreshing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, deploymentsError = cloudflareUserMessage(error))
                }
            }
        }
    }

    /** iOS `PendingWorkerAction.deleteDeployment`. */
    fun requestDeleteDeployment(deployment: CloudflareWorkerDeploymentInfo) = requestConfirmation(
        CloudflareConfirmationPrompt(
            title = "Delete this deployment?",
            message = "This permanently removes the selected Worker deployment from Cloudflare. " +
                "Deployment ${deployment.id.take(14)} of $scriptName will be deleted.",
            confirmLabel = "Delete Deployment",
            resourceId = deployment.id,
            destructive = true,
        ),
    ) { confirmation ->
        cancelLoads()
        api.deleteDeployment(accountId, scriptName, deployment.id, confirmation)
        _state.update { state -> state.copy(deployments = state.deployments.filterNot { it.id == deployment.id }) }
        cache.put(CloudflareWorkerMemoryCache.deploymentsKey(accountId, scriptName), _state.value.deployments)
        showSuccess("Worker deployment deleted.")
    }

    /** iOS `PendingWorkerAction.deleteWorker`. */
    fun requestDeleteWorker() = requestConfirmation(
        CloudflareConfirmationPrompt(
            title = "Delete this Worker?",
            message = "This permanently removes the Worker script and can immediately interrupt routed traffic. " +
                "$scriptName will be deleted from Cloudflare.",
            confirmLabel = "Delete Worker",
            resourceId = scriptName,
            destructive = true,
        ),
    ) { confirmation ->
        cancelLoads()
        deletingWorker = true
        try {
            api.deleteWorker(accountId, scriptName, confirmation)
        } catch (error: Exception) {
            deletingWorker = false
            throw error
        }
        cache.remove(CloudflareWorkerMemoryCache.workerKey(accountId, scriptName))
        cache.remove(CloudflareWorkerMemoryCache.deploymentsKey(accountId, scriptName))
        cache.remove(CloudflareWorkerMemoryCache.operationsKey(accountId, scriptName))
        showSuccess("Worker deleted.")
        _state.update { it.copy(didDeleteWorker = true) }
    }

    private fun cancelLoads() {
        workerJob?.cancel()
        deploymentsJob?.cancel()
        _state.update { it.copy(isLoading = false, isRefreshing = false) }
    }
}
