package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareDisplayText
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsAnalyticsReport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsUsage
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnssecStatus
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDnsSettings
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDnsSettingsDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneResourceIds
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneSetting
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneSettingEditing
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflareZoneOperationsUiState(
    val zone: CloudflareZoneDetail? = null,
    val dnssec: CloudflareDnssecStatus? = null,
    val settings: List<CloudflareZoneSetting> = emptyList(),
    val dnsSettings: CloudflareZoneDnsSettings? = null,
    val dnsUsage: CloudflareDnsUsage? = null,
    val dnsAnalytics: CloudflareDnsAnalyticsReport? = null,
    val zoneError: String? = null,
    val dnssecError: String? = null,
    val settingsError: String? = null,
    val dnsSettingsError: String? = null,
    val dnsUsageError: String? = null,
    val dnsAnalyticsError: String? = null,
    val isLoading: Boolean = true,
    val editingSetting: CloudflareZoneSetting? = null,
    val settingEditorError: String? = null,
    val showingDnsSettingsEditor: Boolean = false,
    val dnsSettingsEditorError: String? = null,
)

/** Port of iOS `CloudflareZoneOperationsViewModel`: DNSSEC, settings, DNS settings, activation and DNS insight. */
class CloudflareZoneOperationsViewModel(
    private val api: CloudflareZoneOperationsApi,
    val zoneId: String,
    private val zoneName: String,
    private val clock: () -> Instant = Instant::now,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareZoneOperationsUiState())
    val state: StateFlow<CloudflareZoneOperationsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var generation = 0
    private var hasLoaded = false
    private var seenRefreshSignal: Int? = null

    val zoneDisplayName: String get() = _state.value.zone?.name ?: zoneName

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
            val until = clock()
            val since = until.minusSeconds(86_400)
            val results = coroutineScope {
                val zone = async { runCatching { api.fetchZone(zoneId) } }
                val dnssec = async { runCatching { api.fetchDnssec(zoneId) } }
                val settings = async { runCatching { api.fetchZoneSettings(zoneId) } }
                val dnsSettings = async { runCatching { api.fetchDnsSettings(zoneId) } }
                val usage = async { runCatching { api.fetchDnsUsage(zoneId) } }
                val analytics = async { runCatching { api.fetchDnsAnalytics(zoneId, since, until) } }
                listOf(zone.await(), dnssec.await(), settings.await(), dnsSettings.await(), usage.await(), analytics.await())
            }
            results.mapNotNull { it.exceptionOrNull() as? CancellationException }.firstOrNull()?.let { throw it }
            if (current != generation) return@launch
            @Suppress("UNCHECKED_CAST")
            _state.update { state ->
                val zone = results[0] as Result<CloudflareZoneDetail>
                val dnssec = results[1] as Result<CloudflareDnssecStatus>
                val settings = results[2] as Result<List<CloudflareZoneSetting>>
                val dnsSettings = results[3] as Result<CloudflareZoneDnsSettings>
                val usage = results[4] as Result<CloudflareDnsUsage>
                val analytics = results[5] as Result<CloudflareDnsAnalyticsReport>
                state.copy(
                    zone = zone.getOrNull() ?: state.zone,
                    zoneError = zone.exceptionOrNull()?.let(::cloudflareUserMessage),
                    dnssec = dnssec.getOrNull() ?: state.dnssec,
                    dnssecError = dnssec.exceptionOrNull()?.let(::cloudflareUserMessage),
                    settings = settings.getOrNull()?.sortedWith(CloudflareZoneSetting.DISPLAY_ORDER) ?: state.settings,
                    settingsError = settings.exceptionOrNull()?.let(::cloudflareUserMessage),
                    dnsSettings = dnsSettings.getOrNull() ?: state.dnsSettings,
                    dnsSettingsError = dnsSettings.exceptionOrNull()?.let(::cloudflareUserMessage),
                    dnsUsage = usage.getOrNull() ?: state.dnsUsage,
                    dnsUsageError = usage.exceptionOrNull()?.let(::cloudflareUserMessage),
                    dnsAnalytics = analytics.getOrNull() ?: state.dnsAnalytics,
                    dnsAnalyticsError = analytics.exceptionOrNull()?.let(::cloudflareUserMessage),
                    isLoading = false,
                )
            }
            hasLoaded = true
        }
    }

    // MARK: DNSSEC

    fun requestDnssec(enabled: Boolean) {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = if (enabled) "Enable DNSSEC?" else "Disable DNSSEC?",
                message = if (enabled) {
                    "Cloudflare will sign $zoneDisplayName. You may need to publish its DS record at your registrar."
                } else {
                    "Zone signing stops for $zoneDisplayName. DNSSEC validation can fail if the existing DS record remains at your registrar."
                },
                confirmLabel = if (enabled) "Enable DNSSEC" else "Disable DNSSEC",
                resourceId = CloudflareZoneResourceIds.dnssec(zoneId),
                destructive = !enabled,
                workingId = WORKING_DNSSEC,
            ),
        ) { confirmation ->
            if (enabled) {
                val status = api.enableDnssec(zoneId, confirmation)
                _state.update { it.copy(dnssec = status, dnssecError = null) }
                showSuccess("DNSSEC enabled. Add the returned DS record at your registrar if Cloudflare requires it.")
            } else {
                api.disableDnssec(zoneId, confirmation)
                val refreshed = runCatching { api.fetchDnssec(zoneId) }.getOrNull()
                _state.update { it.copy(dnssec = refreshed ?: it.dnssec, dnssecError = null) }
                showSuccess("DNSSEC disabled.")
            }
        }
    }

    // MARK: Activation check

    fun requestActivationCheck() {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Request an activation check?",
                message = "Cloudflare will re-check the nameservers for $zoneDisplayName. Cloudflare rate-limits activation checks. " +
                    "Use this after updating the domain’s nameservers.",
                confirmLabel = "Request Check",
                resourceId = CloudflareZoneResourceIds.activationCheck(zoneId),
                workingId = WORKING_ACTIVATION,
            ),
        ) { confirmation ->
            api.requestActivationCheck(zoneId, confirmation)
            runCatching { api.fetchZone(zoneId) }.getOrNull()?.let { zone -> _state.update { it.copy(zone = zone) } }
            showSuccess("Cloudflare accepted the activation check.")
        }
    }

    // MARK: Zone settings

    fun openSettingEditor(setting: CloudflareZoneSetting) {
        if (!setting.editable || !setting.isScalar || isAnyWorking()) return
        _state.update { it.copy(editingSetting = setting, settingEditorError = null) }
    }

    fun dismissSettingEditor() {
        val editing = _state.value.editingSetting ?: return
        if (isWorking(settingWorkingId(editing.id))) return
        _state.update { it.copy(editingSetting = null, settingEditorError = null) }
    }

    fun requestSaveSetting(setting: CloudflareZoneSetting, proposed: ProviderJsonValue?) {
        if (proposed == null) {
            _state.update { it.copy(settingEditorError = "Enter a valid value for ${CloudflareZoneSetting.displayName(setting.id)}.") }
            return
        }
        val name = CloudflareZoneSetting.displayName(setting.id)
        _state.update { it.copy(settingEditorError = null) }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save $name?",
                message = CloudflareZoneSettingEditing.confirmationMessage(setting.value, proposed),
                confirmLabel = "Save setting",
                resourceId = CloudflareZoneResourceIds.setting(zoneId, setting.id),
                workingId = settingWorkingId(setting.id),
            ),
        ) { confirmation ->
            try {
                val updated = api.updateZoneSetting(zoneId, setting.id, proposed, confirmation)
                _state.update { state ->
                    state.copy(
                        settings = state.settings.map { if (it.id == updated.id) updated else it },
                        editingSetting = null,
                        settingEditorError = null,
                    )
                }
                showSuccess("$name updated.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(settingEditorError = cloudflareUserMessage(error)) }
                throw error
            }
        }
    }

    // MARK: DNS settings

    fun openDnsSettingsEditor() {
        if (_state.value.dnsSettings == null) return
        _state.update { it.copy(showingDnsSettingsEditor = true, dnsSettingsEditorError = null) }
    }

    fun dismissDnsSettingsEditor() {
        if (isWorking(WORKING_DNS_SETTINGS)) return
        _state.update { it.copy(showingDnsSettingsEditor = false, dnsSettingsEditorError = null) }
    }

    fun requestSaveDnsSettings(draft: CloudflareZoneDnsSettingsDraft) {
        val original = _state.value.dnsSettings ?: return
        val changes = try {
            draft.validatedChanges(original)
        } catch (error: Exception) {
            _state.update { it.copy(dnsSettingsEditorError = cloudflareUserMessage(error)) }
            return
        }
        _state.update { it.copy(dnsSettingsEditorError = null) }
        val fields = changes.entries.joinToString("\n") { (key, value) -> "${key.replace('_', ' ')}: ${value.cloudflareDisplayText}" }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save DNS settings?",
                message = "This updates ${changes.size} live DNS configuration ${if (changes.size == 1) "field" else "fields"} " +
                    "for $zoneDisplayName.\n\n$fields",
                confirmLabel = "Save changes",
                resourceId = CloudflareZoneResourceIds.dnsSettings(zoneId),
                workingId = WORKING_DNS_SETTINGS,
            ),
        ) { confirmation ->
            try {
                val updated = api.updateDnsSettings(zoneId, changes, confirmation)
                _state.update { it.copy(dnsSettings = updated, showingDnsSettingsEditor = false, dnsSettingsEditorError = null) }
                showSuccess("DNS settings updated.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(dnsSettingsEditorError = cloudflareUserMessage(error)) }
                throw error
            }
        }
    }

    private fun isAnyWorking(): Boolean = working.value.isNotEmpty()

    companion object {
        const val WORKING_DNSSEC = "dnssec"
        const val WORKING_ACTIVATION = "activation"
        const val WORKING_DNS_SETTINGS = "dns-settings"

        fun settingWorkingId(settingId: String) = "setting:$settingId"
    }
}
