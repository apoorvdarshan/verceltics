package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesBuildFolder
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesCustomDomain
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDirectUploadOptions
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDirectUploadProgress
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectEditDraft
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** iOS `CloudflarePagesDirectUploadDraft`: environment, preview branch and commit message. */
data class CloudflarePagesDirectUploadDraft(
    val environment: CloudflarePagesEnvironment,
    val productionBranch: String?,
    val previewBranch: String = "verceltics-preview",
    val commitMessage: String = "Uploaded from Verceltics",
) {
    val options: CloudflarePagesDirectUploadOptions
        get() = CloudflarePagesDirectUploadOptions(
            branch = if (environment == CloudflarePagesEnvironment.PRODUCTION) productionBranch else previewBranch,
            commitMessage = commitMessage,
        )
}

data class CloudflarePagesDomainDetailState(
    val domain: CloudflarePagesCustomDomain,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class CloudflarePagesOperationsState(
    val project: CloudflarePagesProjectDetail? = null,
    val domains: List<CloudflarePagesCustomDomain> = emptyList(),
    val isLoading: Boolean = true,
    /** A reload over an already loaded project (pull-to-refresh / toolbar refresh). */
    val isRefreshing: Boolean = false,
    val loadError: String? = null,
    val domainsError: String? = null,
    val environment: CloudflarePagesEnvironment = CloudflarePagesEnvironment.PRODUCTION,
    val editorDraft: CloudflarePagesProjectEditDraft? = null,
    val editorError: String? = null,
    val isAddingDomain: Boolean = false,
    val addDomainError: String? = null,
    val uploadDraft: CloudflarePagesDirectUploadDraft? = null,
    val uploadDraftError: String? = null,
    val uploadProgress: CloudflarePagesDirectUploadProgress? = null,
    val domainDetail: CloudflarePagesDomainDetailState? = null,
    val didDeleteProject: Boolean = false,
)

/** iOS `CloudflarePagesOperationsViewModel`. Every mutation runs only after its confirmation. */
class CloudflarePagesOperationsViewModel(
    private val api: CloudflarePagesApi,
    val accountId: String,
    val projectName: String,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflarePagesOperationsState())
    val state: StateFlow<CloudflarePagesOperationsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var domainJob: Job? = null
    private var uploadJob: Job? = null

    val projectPath: String = api.projectPath(accountId, projectName)
    val deploymentsPath: String = api.deploymentsPath(accountId, projectName)

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = it.project == null, isRefreshing = it.project != null) }
        loadJob = viewModelScope.launch {
            val (project, domains) = coroutineScope {
                val project = async { capture { api.fetchProject(accountId, projectName) } }
                val domains = async { capture { api.fetchDomains(accountId, projectName) } }
                project.await() to domains.await()
            }
            _state.update { current ->
                current.copy(
                    project = project.getOrNull() ?: current.project,
                    loadError = project.exceptionOrNull()?.let(::cloudflareUserMessage),
                    domains = domains.getOrNull()?.sortedBy { it.name.lowercase() } ?: current.domains,
                    domainsError = domains.exceptionOrNull()?.let(::cloudflareUserMessage),
                    isLoading = false,
                    isRefreshing = false,
                )
            }
        }
    }

    fun selectEnvironment(environment: CloudflarePagesEnvironment) = _state.update { it.copy(environment = environment) }

    private suspend fun refreshDomains() {
        val result = capture { api.fetchDomains(accountId, projectName) }
        _state.update { current ->
            current.copy(
                domains = result.getOrNull()?.sortedBy { it.name.lowercase() } ?: current.domains,
                domainsError = result.exceptionOrNull()?.let(::cloudflareUserMessage),
            )
        }
    }

    private suspend fun refreshProject() {
        capture { api.fetchProject(accountId, projectName) }.getOrNull()?.let { project ->
            _state.update { it.copy(project = project) }
        }
    }

    // MARK: Project settings

    fun openEditor() {
        val project = _state.value.project ?: return
        if (!CloudflarePagesProjectEditDraft.canSafelyEdit(project)) return
        _state.update { it.copy(editorDraft = CloudflarePagesProjectEditDraft.from(project), editorError = null) }
    }

    fun updateDraft(draft: CloudflarePagesProjectEditDraft) = _state.update { it.copy(editorDraft = draft) }

    fun closeEditor() = _state.update { it.copy(editorDraft = null, editorError = null) }

    fun requestSave() {
        val draft = _state.value.editorDraft ?: return
        if (draft.productionBranch.isBlank()) {
            _state.update { it.copy(editorError = "Production branch cannot be empty.") }
            return
        }
        _state.update { it.copy(editorError = null) }
        requestConfirmation(CloudflarePagesPrompts.saveSettings(projectName, draft, projectPath)) { confirmation ->
            try {
                val saved = api.updateProject(accountId, projectName, draft, confirmation)
                _state.update { it.copy(project = saved, editorDraft = null, editorError = null) }
                showSuccess("Project settings saved.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(editorError = cloudflareUserMessage(error)) }
                throw error
            }
        }
    }

    fun requestPurgeBuildCache() = requestConfirmation(
        CloudflarePagesPrompts.purgeBuildCache(projectName, api.purgeBuildCachePath(accountId, projectName)),
    ) { confirmation ->
        api.purgeBuildCache(accountId, projectName, confirmation)
        showSuccess("Build cache purged.")
    }

    fun requestDeleteProject() = requestConfirmation(CloudflarePagesPrompts.deleteProject(projectName, projectPath)) { confirmation ->
        api.deleteProject(accountId, projectName, confirmation)
        showSuccess("Project deleted.")
        _state.update { it.copy(didDeleteProject = true) }
    }

    fun requestRedeploy() {
        val latest = _state.value.project?.latestDeployment ?: return
        requestConfirmation(CloudflarePagesPrompts.redeploy(projectName, latest)) { confirmation ->
            api.retryDeployment(accountId, projectName, latest.id, confirmation)
            refreshProject()
            showSuccess("A new deployment was created from the latest build.")
        }
    }

    // MARK: Custom domains

    fun openAddDomain() = _state.update { it.copy(isAddingDomain = true, addDomainError = null) }

    fun closeAddDomain() = _state.update { it.copy(isAddingDomain = false, addDomainError = null) }

    fun requestAddDomain(rawName: String) {
        val name = try {
            CloudflarePagesApi.normalizeDomainName(rawName)
        } catch (error: CloudflareOperationException) {
            _state.update { it.copy(addDomainError = error.userMessage) }
            return
        }
        _state.update { it.copy(addDomainError = null) }
        requestConfirmation(CloudflarePagesPrompts.addDomain(projectName, name, api.domainsPath(accountId, projectName))) { confirmation ->
            try {
                api.addDomain(accountId, projectName, name, confirmation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(addDomainError = cloudflareUserMessage(error)) }
                throw error
            }
            _state.update { it.copy(isAddingDomain = false, addDomainError = null) }
            refreshDomains()
            showSuccess("Domain added. Cloudflare is validating it.")
        }
    }

    fun requestRetryValidation(domain: CloudflarePagesCustomDomain) = requestConfirmation(
        CloudflarePagesPrompts.retryValidation(domain, api.domainPath(accountId, projectName, domain.name)),
    ) { confirmation ->
        api.retryDomainValidation(accountId, projectName, domain.name, confirmation)
        refreshDomains()
        showSuccess("Validation restarted for ${domain.name}.")
    }

    fun requestDeleteDomain(domain: CloudflarePagesCustomDomain) = requestConfirmation(
        CloudflarePagesPrompts.deleteDomain(domain, api.domainPath(accountId, projectName, domain.name)),
    ) { confirmation ->
        api.deleteDomain(accountId, projectName, domain.name, confirmation)
        refreshDomains()
        showSuccess("${domain.name} removed from Pages.")
    }

    /** iOS `CloudflarePagesDomainDetailSheet`: shows the listed domain, then loads its live detail. */
    fun openDomain(domain: CloudflarePagesCustomDomain) {
        _state.update { it.copy(domainDetail = CloudflarePagesDomainDetailState(domain)) }
        refreshDomainDetail()
    }

    fun refreshDomainDetail() {
        val current = _state.value.domainDetail ?: return
        domainJob?.cancel()
        _state.update { it.copy(domainDetail = current.copy(isRefreshing = true, error = null)) }
        domainJob = viewModelScope.launch {
            val result = capture { api.fetchDomain(accountId, projectName, current.domain.name) }
            _state.update { state ->
                val detail = state.domainDetail ?: return@update state
                if (detail.domain.name != current.domain.name) return@update state
                state.copy(
                    domainDetail = detail.copy(
                        domain = result.getOrNull() ?: detail.domain,
                        isRefreshing = false,
                        error = result.exceptionOrNull()?.let(::cloudflareUserMessage),
                    ),
                )
            }
        }
    }

    fun closeDomain() {
        domainJob?.cancel()
        _state.update { it.copy(domainDetail = null) }
    }

    // MARK: Direct upload

    fun openUpload() {
        val project = _state.value.project ?: return
        _state.update {
            it.copy(
                uploadDraft = CloudflarePagesDirectUploadDraft(it.environment, project.productionBranch?.trim()),
                uploadDraftError = null,
            )
        }
    }

    fun updateUploadDraft(draft: CloudflarePagesDirectUploadDraft) = _state.update { it.copy(uploadDraft = draft, uploadDraftError = null) }

    fun closeUpload() = _state.update { it.copy(uploadDraft = null, uploadDraftError = null) }

    /** iOS validation before opening the folder picker. Returns false when the draft is incomplete. */
    fun validateUploadDraft(): Boolean {
        val draft = _state.value.uploadDraft ?: return false
        if (draft.environment == CloudflarePagesEnvironment.PREVIEW && draft.previewBranch.isBlank()) {
            _state.update { it.copy(uploadDraftError = "Enter a branch name for the preview deployment.") }
            return false
        }
        return true
    }

    /** Called with the folder chosen in the picker; the upload starts only after confirmation. */
    fun requestUpload(folder: CloudflarePagesBuildFolder, folderName: String) {
        val draft = _state.value.uploadDraft ?: return
        _state.update { it.copy(uploadDraft = null, uploadDraftError = null) }
        val options = draft.options
        requestConfirmation(
            CloudflarePagesPrompts.directUpload(projectName, folderName, draft.environment, options, deploymentsPath),
        ) { confirmation ->
            uploadJob = currentCoroutineContext()[Job]
            _state.update {
                it.copy(uploadProgress = CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.AUTHORIZING, 0, 0))
            }
            try {
                val result = api.directUpload(accountId, projectName, folder, options, confirmation) { progress ->
                    _state.update { it.copy(uploadProgress = progress) }
                }
                val deploymentId = result.deployment.shortId ?: result.deployment.id.take(8)
                val reused = if (result.reusedAssetCount > 0) " · ${result.reusedAssetCount} already on Cloudflare" else ""
                showSuccess("Deployment $deploymentId created with ${result.assetCount} files$reused.")
                refreshProject()
            } finally {
                uploadJob = null
                _state.update { it.copy(uploadProgress = null) }
            }
        }
    }

    fun cancelUpload() {
        val job = uploadJob ?: return
        job.cancel()
        showError("Upload cancelled.")
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}
