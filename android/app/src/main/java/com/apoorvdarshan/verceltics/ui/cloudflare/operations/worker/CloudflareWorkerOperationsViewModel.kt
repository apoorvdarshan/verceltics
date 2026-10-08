package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerDomainInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerScheduleInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerScriptDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerScriptLevelSettingsInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerScriptSettingsInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerSecretInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerSecretText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerSubdomainSettings
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerTailSession
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerVersionInfo
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Worker operations screen shows (iOS `CloudflareWorkerOperationsViewModel` fields). */
data class CloudflareWorkerOperationsState(
    val worker: CloudflareWorkerScriptDetail? = null,
    val versions: List<CloudflareWorkerVersionInfo> = emptyList(),
    val secrets: List<CloudflareWorkerSecretInfo> = emptyList(),
    val schedules: List<CloudflareWorkerScheduleInfo> = emptyList(),
    val domains: List<CloudflareWorkerDomainInfo> = emptyList(),
    val tails: List<CloudflareWorkerTailSession> = emptyList(),
    val settings: CloudflareWorkerScriptSettingsInfo? = null,
    val scriptLevelSettings: CloudflareWorkerScriptLevelSettingsInfo? = null,
    val subdomain: CloudflareWorkerSubdomainSettings? = null,
    val accountSubdomain: String? = null,
    val warnings: List<String> = emptyList(),
    val isLoading: Boolean = true,
    /** A reload over a cached or loaded snapshot (pull-to-refresh / toolbar refresh). */
    val isRefreshing: Boolean = false,
)

/** Port of iOS `CloudflareWorkerOperationsViewModel`. Every change goes through a confirmation. */
class CloudflareWorkerOperationsViewModel(
    private val api: CloudflareWorkerOperationsApi,
    val accountId: String,
    val scriptName: String,
    private val cache: CloudflareWorkerMemoryCache = CloudflareWorkerMemoryCache.shared,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareWorkerOperationsState())
    val state: StateFlow<CloudflareWorkerOperationsState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** iOS `workerDevURL`: `<script>.<account subdomain>.workers.dev`. */
    val workerDevHostname: String?
        get() = _state.value.accountSubdomain?.takeIf(String::isNotEmpty)?.let { "$scriptName.$it.workers.dev" }

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean) {
        val key = CloudflareWorkerMemoryCache.operationsKey(accountId, scriptName)
        var hydrated = false
        cache.get<CloudflareWorkerOperationsState>(key)?.let { (cached, fresh) ->
            _state.value = cached.copy(isLoading = false, isRefreshing = false)
            hydrated = true
            if (!forceRefresh && fresh) return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = !hydrated, isRefreshing = hydrated) }
            val cachedWorker = cache.get<CloudflareWorkerScriptDetail>(CloudflareWorkerMemoryCache.workerKey(accountId, scriptName))
            val warnings = mutableListOf<String>()
            coroutineScope {
                val worker = if (cachedWorker != null && cachedWorker.second && !forceRefresh) {
                    null
                } else {
                    async { capture { api.fetchWorkerScript(accountId, scriptName) } }
                }
                val versions = async { capture { api.fetchVersions(accountId, scriptName) } }
                val secrets = async { capture { api.fetchSecrets(accountId, scriptName) } }
                val schedules = async { capture { api.fetchSchedules(accountId, scriptName) } }
                val domains = async { capture { api.fetchDomains(accountId, scriptName) } }
                val settings = async { capture { api.fetchScriptSettings(accountId, scriptName) } }
                val scriptSettings = async { capture { api.fetchScriptLevelSettings(accountId, scriptName) } }
                val accountSubdomain = async { capture { api.fetchAccountSubdomain(accountId) } }
                val subdomain = async { capture { api.fetchWorkerSubdomain(accountId, scriptName) } }
                val tails = async { capture { api.fetchTails(accountId, scriptName) } }

                val current = _state.value
                val resolvedWorker = worker?.await()?.let { result ->
                    result.getOrNull()?.also { cache.put(CloudflareWorkerMemoryCache.workerKey(accountId, scriptName), it) }
                        ?: run {
                            warnings += "Worker: ${cloudflareUserMessage(result.exceptionOrNull()!!)}"
                            null
                        }
                } ?: cachedWorker?.first ?: current.worker
                _state.value = CloudflareWorkerOperationsState(
                    worker = resolvedWorker,
                    versions = versions.await().orWarn("Versions", warnings) ?: current.versions,
                    secrets = secrets.await().orWarn("Secrets", warnings) ?: current.secrets,
                    schedules = schedules.await().orWarn("Cron triggers", warnings) ?: current.schedules,
                    domains = domains.await().orWarn("Domains", warnings) ?: current.domains,
                    settings = settings.await().orWarn("Settings", warnings) ?: current.settings,
                    scriptLevelSettings = scriptSettings.await().orWarn("Script settings", warnings) ?: current.scriptLevelSettings,
                    accountSubdomain = accountSubdomain.await().orWarn("Account workers.dev", warnings) ?: current.accountSubdomain,
                    subdomain = subdomain.await().orWarn("Worker subdomain", warnings) ?: current.subdomain,
                    tails = tails.await().orWarn("Live tails", warnings) ?: current.tails,
                    warnings = warnings.toList(),
                    isLoading = false,
                )
            }
            updateCache()
        }
    }

    // MARK: Versions

    /** iOS "Deploy this version?" — sends 100% of production traffic to [version] (deploy or rollback). */
    fun requestDeploy(version: CloudflareWorkerVersionInfo) = requestConfirmation(
        CloudflareConfirmationPrompt(
            title = "Deploy this version?",
            message = "The selected version will receive all production traffic. " +
                "${version.displayTitle} (${version.id.take(18)}) will serve 100% of requests to $scriptName.",
            confirmLabel = "Deploy to 100%",
            resourceId = version.id,
            workingId = deployWorkingId(version.id),
        ),
    ) { confirmation ->
        api.deployVersion(accountId, scriptName, version.id, CloudflareWorkerOperationsApi.DEFAULT_DEPLOY_MESSAGE, confirmation)
        reloadSection("Versions") { _state.update { state -> state.copy(versions = api.fetchVersions(accountId, scriptName)) } }
        succeed("Version deployed to 100% of traffic.")
    }

    // MARK: Secrets

    /** The value stays in [value] until Cloudflare receives it; it is wiped right after the body is built. */
    fun requestSaveSecret(name: String, value: CloudflareWorkerSecretText) {
        val trimmed = name.trim()
        val validName = runCatching { CloudflareWorkerOperationsApi.validateSecretName(trimmed) }.getOrElse {
            value.wipe()
            showError(it)
            return
        }
        if (value.isEmpty) {
            showError("Enter a secret value.")
            return
        }
        val replaces = _state.value.secrets.any { it.name == validName }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save secret $validName?",
                message = (if (replaces) "The existing value of $validName will be replaced on $scriptName. " else "$validName will be added to $scriptName. ") +
                    "The value is sent directly to Cloudflare and is never stored or shown again.",
                confirmLabel = "Save secret",
                resourceId = validName,
                workingId = SECRET_SAVE,
            ),
        ) { confirmation ->
            api.putSecret(accountId, scriptName, validName, value, confirmation)
            reloadSection("Secrets") { _state.update { state -> state.copy(secrets = api.fetchSecrets(accountId, scriptName)) } }
            succeed("Secret saved. Its value is not stored or displayed.")
        }
    }

    fun requestDeleteSecret(secret: CloudflareWorkerSecretInfo) = requestConfirmation(
        CloudflareConfirmationPrompt(
            title = "Delete secret ${secret.name}?",
            message = "${secret.name} will be removed from $scriptName. This change takes effect immediately on Cloudflare.",
            confirmLabel = "Delete",
            resourceId = secret.name,
            destructive = true,
            workingId = "secret-${secret.name}",
        ),
    ) { confirmation ->
        api.deleteSecret(accountId, scriptName, secret.name, confirmation)
        reloadSection("Secrets") { _state.update { state -> state.copy(secrets = api.fetchSecrets(accountId, scriptName)) } }
        succeed("Secret deleted.")
    }

    // MARK: Cron triggers

    fun requestAddSchedule(cron: String) {
        val expression = cron.trim()
        if (!CloudflareWorkerOperationsApi.isValidCronExpression(expression)) {
            showError("Cron expressions need five fields, such as */30 * * * *.")
            return
        }
        val values = CloudflareWorkerOperationsApi.schedulesAdding(_state.value.schedules, expression)
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Add cron trigger?",
                message = "$expression (UTC) will invoke $scriptName's scheduled handler. " +
                    "Cron triggers after saving: ${values.joinToString(", ")}.",
                confirmLabel = "Add trigger",
                resourceId = scriptName,
                workingId = SCHEDULES,
            ),
        ) { confirmation -> saveSchedules(values, confirmation, "Cron trigger added.") }
    }

    fun requestDeleteSchedule(schedule: CloudflareWorkerScheduleInfo) {
        val values = CloudflareWorkerOperationsApi.schedulesRemoving(_state.value.schedules, schedule.cron)
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete cron ${schedule.cron}?",
                message = "The ${schedule.cron} trigger will stop invoking $scriptName. " +
                    "This change takes effect immediately on Cloudflare.",
                confirmLabel = "Delete",
                resourceId = scriptName,
                destructive = true,
                workingId = SCHEDULES,
            ),
        ) { confirmation -> saveSchedules(values, confirmation, "Cron trigger deleted.") }
    }

    private suspend fun saveSchedules(
        values: List<String>,
        confirmation: com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation,
        success: String,
    ) {
        val saved = api.updateSchedules(accountId, scriptName, values, confirmation)
        _state.update { it.copy(schedules = saved) }
        succeed(success)
    }

    // MARK: Domains

    fun requestAttachDomain(hostname: String) {
        if (!CloudflareWorkerOperationsApi.isValidHostname(hostname)) {
            showError("Enter a hostname such as api.example.com.")
            return
        }
        val normalized = CloudflareWorkerOperationsApi.normalizeHostname(hostname)
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Attach $normalized?",
                message = "$normalized will serve production traffic from $scriptName. " +
                    "Cloudflare creates the DNS record and certificate for this hostname.",
                confirmLabel = "Attach domain",
                resourceId = normalized,
                workingId = DOMAIN_ADD,
            ),
        ) { confirmation ->
            api.attachDomain(accountId, normalized, scriptName, confirmation)
            reloadSection("Domains") { _state.update { state -> state.copy(domains = api.fetchDomains(accountId, scriptName)) } }
            succeed("Custom domain attached.")
        }
    }

    fun requestDetachDomain(domain: CloudflareWorkerDomainInfo) = requestConfirmation(
        CloudflareConfirmationPrompt(
            title = "Detach ${domain.hostname}?",
            message = "${domain.hostname} will stop serving $scriptName. This change takes effect immediately on Cloudflare.",
            confirmLabel = "Delete",
            resourceId = domain.id,
            destructive = true,
            workingId = "domain-${domain.id}",
        ),
    ) { confirmation ->
        api.detachDomain(accountId, domain.id, confirmation)
        reloadSection("Domains") { _state.update { state -> state.copy(domains = api.fetchDomains(accountId, scriptName)) } }
        succeed("Custom domain detached.")
    }

    // MARK: workers.dev and observability

    fun requestUpdateSubdomain(enabled: Boolean, previewsEnabled: Boolean) {
        val current = _state.value.subdomain
        val hostname = workerDevHostname?.let { " ($it)" }.orEmpty()
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save workers.dev settings?",
                message = "For $scriptName: production URL$hostname ${onOff(enabled)}, " +
                    "version preview URLs ${onOff(previewsEnabled)}.",
                confirmLabel = "Save",
                resourceId = scriptName,
                destructive = (current?.enabled == true && !enabled) || (current?.previewsEnabled == true && !previewsEnabled),
                workingId = SUBDOMAIN,
            ),
        ) { confirmation ->
            val saved = api.updateWorkerSubdomain(accountId, scriptName, enabled, previewsEnabled, confirmation)
            _state.update { it.copy(subdomain = saved) }
            succeed("workers.dev settings updated.")
        }
    }

    /** iOS editor semantics: logs and traces can only be on while event collection is on. */
    fun requestUpdateObservability(enabled: Boolean, logsEnabled: Boolean, tracesEnabled: Boolean) {
        val logs = enabled && logsEnabled
        val traces = enabled && tracesEnabled
        val current = _state.value.scriptLevelSettings?.observability
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save observability settings?",
                message = "For $scriptName: event collection ${onOff(enabled)}, invocation logs ${onOff(logs)}, traces ${onOff(traces)}.",
                confirmLabel = "Save",
                resourceId = scriptName,
                destructive = (current?.enabled == true && !enabled) ||
                    (current?.logs?.enabled == true && !logs) ||
                    (current?.traces?.enabled == true && !traces),
                workingId = OBSERVABILITY,
            ),
        ) { confirmation ->
            val saved = api.updateObservability(accountId, scriptName, enabled, logs, traces, confirmation)
            _state.update { it.copy(scriptLevelSettings = saved) }
            succeed("Observability settings updated.")
        }
    }

    private suspend fun reloadSection(section: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(warnings = it.warnings + "$section: ${cloudflareUserMessage(error)}") }
        }
    }

    private fun succeed(message: String) {
        showSuccess(message)
        updateCache()
    }

    private fun updateCache() {
        val current = _state.value
        cache.put(CloudflareWorkerMemoryCache.operationsKey(accountId, scriptName), current, fresh = current.warnings.isEmpty())
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun <T> Result<T>.orWarn(section: String, warnings: MutableList<String>): T? =
        getOrElse { error ->
            warnings += "$section: ${cloudflareUserMessage(error)}"
            null
        }

    companion object {
        const val SECRET_SAVE: String = "secret-save"
        const val SCHEDULES: String = "schedules"
        const val DOMAIN_ADD: String = "domain-add"
        const val SUBDOMAIN: String = "subdomain"
        const val OBSERVABILITY: String = "observability"

        fun deployWorkingId(versionId: String): String = "deploy-$versionId"

        private fun onOff(value: Boolean) = if (value) "On" else "Off"
    }
}
