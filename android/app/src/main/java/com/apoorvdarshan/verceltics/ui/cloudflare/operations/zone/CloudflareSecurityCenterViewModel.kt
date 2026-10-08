package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAccessRuleDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareSecurityCategory
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareSecurityItem
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareSecuritySnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneResourceIds
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.cloudflareHumanize
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflareSecurityCenterUiState(
    val snapshot: CloudflareSecuritySnapshot = CloudflareSecuritySnapshot(),
    val isLoading: Boolean = true,
    val showingAccessRuleEditor: Boolean = false,
    val accessRuleEditorError: String? = null,
    /** Item whose raw configuration sheet is open (iOS `CloudflareSecurityItemDetailView`). */
    val selectedItem: CloudflareSecurityItem? = null,
)

/** Port of iOS `CloudflareSecurityCenterViewModel`. */
class CloudflareSecurityCenterViewModel(
    private val api: CloudflareZoneOperationsApi,
    val zoneId: String,
    private val zoneName: String,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareSecurityCenterUiState())
    val state: StateFlow<CloudflareSecurityCenterUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var generation = 0
    private var hasLoaded = false
    private var seenRefreshSignal: Int? = null

    fun onRefreshSignal(signal: Int) {
        val previous = seenRefreshSignal
        seenRefreshSignal = signal
        when {
            !hasLoaded && loadJob == null -> load()
            previous != null && previous != signal -> load(force = true)
        }
    }

    fun load(force: Boolean = false) {
        if (!force && (hasLoaded || loadJob?.isActive == true)) return
        loadJob?.cancel()
        val current = ++generation
        _state.update { it.copy(isLoading = !hasLoaded) }
        loadJob = viewModelScope.launch {
            val (level, categories) = coroutineScope {
                val level = async { runCatching { api.fetchSecurityLevel(zoneId) } }
                val categories = CloudflareSecurityCategory.entries.map { category ->
                    async { category to runCatching { api.fetchSecurityItems(zoneId, category) } }
                }
                level.await() to categories.awaitAll()
            }
            (listOf(level.exceptionOrNull()) + categories.map { it.second.exceptionOrNull() })
                .filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            if (current != generation) return@launch
            var snapshot = _state.value.snapshot.copy(warnings = emptyList())
            val warnings = mutableListOf<String>()
            level.fold(
                onSuccess = { snapshot = snapshot.copy(securityLevel = it) },
                onFailure = { warnings += "Security level: ${cloudflareUserMessage(it)}" },
            )
            categories.forEach { (category, result) ->
                result.fold(
                    onSuccess = { snapshot = snapshot.with(category, it) },
                    onFailure = { warnings += "${category.label}: ${cloudflareUserMessage(it)}" },
                )
            }
            _state.update { it.copy(snapshot = snapshot.copy(warnings = warnings), isLoading = false) }
            hasLoaded = true
        }
    }

    fun requestSecurityLevel(level: String) {
        val current = _state.value.snapshot.securityLevel
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Change the zone security level?",
                message = "This immediately changes how Cloudflare challenges or blocks requests for $zoneName.\n\n" +
                    "${current?.let(::cloudflareHumanize) ?: "Not returned"} → ${cloudflareHumanize(level)}",
                confirmLabel = "Apply Security Level",
                resourceId = CloudflareZoneResourceIds.securityLevel(zoneId),
                workingId = WORKING_SECURITY_LEVEL,
            ),
        ) { confirmation ->
            val updated = api.updateSecurityLevel(zoneId, level, confirmation)
            _state.update { it.copy(snapshot = it.snapshot.copy(securityLevel = updated)) }
            showSuccess("Security level updated to ${level.replace('_', ' ')}.")
        }
    }

    fun openAccessRuleEditor() {
        _state.update { it.copy(showingAccessRuleEditor = true, accessRuleEditorError = null) }
    }

    fun dismissAccessRuleEditor() {
        if (isWorking(WORKING_ACCESS_ADD)) return
        _state.update { it.copy(showingAccessRuleEditor = false, accessRuleEditorError = null) }
    }

    fun requestCreateAccessRule(draft: CloudflareAccessRuleDraft) {
        if (!draft.canSubmit) {
            _state.update { it.copy(accessRuleEditorError = "Enter a value for the rule target.") }
            return
        }
        _state.update { it.copy(accessRuleEditorError = null) }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Create this IP access rule?",
                message = "The ${draft.mode.replace('_', ' ')} action applies immediately to matching traffic.\n\n" +
                    "${cloudflareHumanize(draft.target)}: ${draft.trimmedValue} on $zoneName",
                confirmLabel = "Create Rule",
                resourceId = CloudflareZoneResourceIds.accessRules(zoneId),
                workingId = WORKING_ACCESS_ADD,
            ),
        ) { confirmation ->
            try {
                api.createAccessRule(zoneId, draft.target, draft.trimmedValue, draft.mode, draft.notesOrNull, confirmation)
                _state.update { it.copy(showingAccessRuleEditor = false, accessRuleEditorError = null) }
                showSuccess("IP access rule created.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(accessRuleEditorError = cloudflareUserMessage(error)) }
                throw error
            }
            reloadCategory(CloudflareSecurityCategory.ACCESS_RULES)
        }
    }

    fun requestDeleteAccessRule(rule: CloudflareSecurityItem) {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this IP access rule?",
                message = "${rule.title}${rule.status?.let { " ($it)" }.orEmpty()} will be removed from $zoneName. " +
                    "Traffic matching this rule will immediately stop using its current action.",
                confirmLabel = "Delete Rule",
                resourceId = CloudflareZoneResourceIds.accessRule(zoneId, rule.id),
                destructive = true,
                workingId = accessWorkingId(rule.id),
            ),
        ) { confirmation ->
            api.deleteAccessRule(zoneId, rule.id, confirmation)
            _state.update { state ->
                state.copy(snapshot = state.snapshot.copy(accessRules = state.snapshot.accessRules.filterNot { it.id == rule.id }))
            }
            showSuccess("IP access rule deleted.")
        }
    }

    fun showItem(item: CloudflareSecurityItem) {
        _state.update { it.copy(selectedItem = item) }
    }

    fun dismissItem() {
        _state.update { it.copy(selectedItem = null) }
    }

    private suspend fun reloadCategory(category: CloudflareSecurityCategory) {
        try {
            val items = api.fetchSecurityItems(zoneId, category)
            _state.update { it.copy(snapshot = it.snapshot.with(category, items)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(snapshot = it.snapshot.copy(warnings = it.snapshot.warnings + "${category.label}: ${cloudflareUserMessage(error)}")) }
        }
    }

    companion object {
        const val WORKING_SECURITY_LEVEL = "security-level"
        const val WORKING_ACCESS_ADD = "access-add"

        fun accessWorkingId(ruleId: String) = "access-$ruleId"
    }
}

data class CloudflareRulesetDetailUiState(
    val rules: List<CloudflareSecurityItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedItem: CloudflareSecurityItem? = null,
)

/** Port of iOS `CloudflareRulesetDetailViewModel`. */
class CloudflareRulesetDetailViewModel(
    private val api: CloudflareZoneOperationsApi,
    private val zoneId: String,
    private val rulesetId: String,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareRulesetDetailUiState())
    val state: StateFlow<CloudflareRulesetDetailUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var hasLoaded = false
    private var seenRefreshSignal: Int? = null

    fun onRefreshSignal(signal: Int) {
        val previous = seenRefreshSignal
        seenRefreshSignal = signal
        when {
            !hasLoaded && loadJob == null -> load()
            previous != null && previous != signal -> load(force = true)
        }
    }

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true || (!force && hasLoaded)) return
        _state.update { it.copy(isLoading = it.rules.isEmpty(), error = null) }
        loadJob = viewModelScope.launch {
            try {
                val rules = api.fetchRulesetRules(zoneId, rulesetId)
                _state.update { it.copy(rules = rules, isLoading = false) }
                hasLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoading = false, error = if (it.rules.isEmpty()) cloudflareUserMessage(error) else null) }
            }
        }
    }

    fun showItem(item: CloudflareSecurityItem) = _state.update { it.copy(selectedItem = item) }

    fun dismissItem() = _state.update { it.copy(selectedItem = null) }
}
