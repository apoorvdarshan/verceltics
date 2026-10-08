package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeployment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectDetail
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

data class CloudflarePagesProjectDetailState(
    val project: CloudflarePagesProjectDetail? = null,
    val deployments: List<CloudflarePagesDeployment> = emptyList(),
    val environmentFilter: CloudflarePagesEnvironment? = null,
    val isLoading: Boolean = true,
    val hasLoadedDeployments: Boolean = false,
    val projectError: String? = null,
    val deploymentsError: String? = null,
)

/** iOS `CloudflarePagesProjectDetailViewModel`: project detail, deployments and deployment actions. */
class CloudflarePagesProjectDetailViewModel(
    private val api: CloudflarePagesApi,
    val accountId: String,
    val projectName: String,
    mutations: Flow<CloudflareMutationEvent>? = null,
) : CloudflareOperationsViewModel() {
    constructor(client: CloudflareRestClient, accountId: String, projectName: String) :
        this(CloudflarePagesApi(client), accountId, projectName, client.mutations)

    private val _state = MutableStateFlow(CloudflarePagesProjectDetailState())
    val state: StateFlow<CloudflarePagesProjectDetailState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val projectPath = api.projectPath(accountId, projectName)

    init {
        load()
        mutations?.let { flow ->
            viewModelScope.launch {
                // iOS `cloudflareDataDidChange`: reconcile when another screen changes this project.
                flow.collect { event ->
                    if (event.apiPath == projectPath || event.apiPath.startsWith("$projectPath/")) load()
                }
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        val filter = _state.value.environmentFilter
        _state.update { it.copy(isLoading = !it.hasLoadedDeployments && it.project == null, projectError = null, deploymentsError = null) }
        loadJob = viewModelScope.launch {
            val (projectResult, deploymentsResult) = coroutineScope {
                val project = async { capture { api.fetchProject(accountId, projectName) } }
                val deployments = async { capture { api.fetchDeployments(accountId, projectName, filter) } }
                project.await() to deployments.await()
            }
            _state.update { current ->
                current.copy(
                    project = projectResult.getOrNull() ?: current.project,
                    projectError = projectResult.exceptionOrNull()?.let(::cloudflareUserMessage),
                    deployments = deploymentsResult.getOrNull() ?: current.deployments,
                    deploymentsError = deploymentsResult.exceptionOrNull()?.let(::cloudflareUserMessage),
                    hasLoadedDeployments = current.hasLoadedDeployments || deploymentsResult.isSuccess,
                    isLoading = false,
                )
            }
        }
    }

    fun selectEnvironment(environment: CloudflarePagesEnvironment?) {
        if (_state.value.environmentFilter == environment) return
        _state.update { it.copy(environmentFilter = environment, deployments = emptyList(), hasLoadedDeployments = false) }
        load()
    }

    fun requestRetry(deployment: CloudflarePagesDeployment) =
        requestConfirmation(CloudflarePagesPrompts.retry(projectName, deployment)) { confirmation ->
            api.retryDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Deployment retry started.")
            load()
        }

    fun requestRollback(deployment: CloudflarePagesDeployment) =
        requestConfirmation(CloudflarePagesPrompts.rollback(projectName, deployment)) { confirmation ->
            api.rollbackDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Production rollback started.")
            load()
        }

    fun requestDelete(deployment: CloudflarePagesDeployment) =
        requestConfirmation(CloudflarePagesPrompts.deleteDeployment(projectName, deployment)) { confirmation ->
            api.deleteDeployment(accountId, projectName, deployment.id, confirmation)
            showSuccess("Deployment deleted.")
            _state.update { state -> state.copy(deployments = state.deployments.filterNot { it.id == deployment.id }) }
            load()
        }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
