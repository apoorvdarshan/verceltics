package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAnalyticsRange
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareCachePurge
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareCachePurgeKind
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecord
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecordDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareDnsRecordInput
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneAnalyticsBreakdowns
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneAnalyticsSummary
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneDetail
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneResourceIds
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which DNS record the editor sheet is open for. */
sealed interface CloudflareDnsEditorTarget {
    val record: CloudflareDnsRecord?

    data object New : CloudflareDnsEditorTarget {
        override val record: CloudflareDnsRecord? get() = null
    }

    data class Existing(override val record: CloudflareDnsRecord) : CloudflareDnsEditorTarget
}

data class CloudflareZoneDetailUiState(
    val zone: CloudflareZoneDetail? = null,
    val zoneError: String? = null,
    val isZoneLoading: Boolean = false,
    val range: CloudflareAnalyticsRange = CloudflareAnalyticsRange.DAYS_7,
    val customFrom: Instant,
    val customTo: Instant,
    val analytics: CloudflareZoneAnalyticsSummary? = null,
    val breakdowns: CloudflareZoneAnalyticsBreakdowns? = null,
    val analyticsError: String? = null,
    val breakdownError: String? = null,
    val isAnalyticsLoading: Boolean = false,
    val dnsRecords: List<CloudflareDnsRecord> = emptyList(),
    val hasLoadedDns: Boolean = false,
    val dnsError: String? = null,
    val isDnsLoading: Boolean = false,
    val dnsEditor: CloudflareDnsEditorTarget? = null,
    val editorError: String? = null,
    val showingPurge: Boolean = false,
    val purgeError: String? = null,
)

/** Port of iOS `CloudflareZoneDetailViewModel`: zone, traffic analytics, DNS records and cache purge. */
class CloudflareZoneDetailViewModel(
    private val api: CloudflareZoneOperationsApi,
    val zoneId: String,
    private val zoneName: String,
    mutations: Flow<CloudflareMutationEvent> = emptyFlow(),
    private val clock: () -> Instant = Instant::now,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(
        clock().let { now -> CloudflareZoneDetailUiState(customFrom = now.minus(30, ChronoUnit.DAYS), customTo = now) },
    )
    val state: StateFlow<CloudflareZoneDetailUiState> = _state.asStateFlow()

    private data class CachedAnalytics(
        val analytics: CloudflareZoneAnalyticsSummary,
        val breakdowns: CloudflareZoneAnalyticsBreakdowns?,
        val breakdownError: String?,
    )

    private val analyticsCache = HashMap<String, CachedAnalytics>()
    private var zoneJob: Job? = null
    private var analyticsJob: Job? = null
    private var dnsJob: Job? = null
    private var zoneGeneration = 0
    private var analyticsGeneration = 0
    private var dnsGeneration = 0
    private var seenRefreshSignal: Int? = null
    private var hasStarted = false

    /** Set once the dashboard has been asked to reconcile this zone's inventory row. */
    var inventoryReconciled: Boolean = false

    val zoneDisplayName: String get() = _state.value.zone?.name ?: zoneName

    init {
        viewModelScope.launch {
            mutations.collect { event ->
                val prefix = "/zones/$zoneId"
                val path = event.apiPath
                if ((path == prefix || path.startsWith("$prefix/")) && !path.contains("/dns_records")) loadZone(force = true)
            }
        }
    }

    /** First appearance loads everything; later changes of the top-bar refresh signal force a reload. */
    fun onRefreshSignal(signal: Int) {
        val previous = seenRefreshSignal
        seenRefreshSignal = signal
        when {
            !hasStarted -> load()
            previous != null && previous != signal -> load(force = true)
        }
    }

    fun load(force: Boolean = false) {
        hasStarted = true
        loadZone(force)
        loadAnalytics(force)
        loadDns(force)
    }

    fun selectRange(range: CloudflareAnalyticsRange) {
        if (range == CloudflareAnalyticsRange.CUSTOM) return
        if (_state.value.range == range) {
            loadAnalytics(force = false)
            return
        }
        _state.update { it.copy(range = range) }
        restoreAnalyticsForSelectedRange()
        loadAnalytics(force = false)
    }

    /** Returns an error message instead of applying an invalid window. */
    fun selectCustomRange(from: Instant, to: Instant): String? {
        if (!from.isBefore(to)) return "The start must be earlier than the end."
        _state.update { it.copy(customFrom = from, customTo = to, range = CloudflareAnalyticsRange.CUSTOM) }
        restoreAnalyticsForSelectedRange()
        loadAnalytics(force = false)
        return null
    }

    // MARK: DNS editor

    fun openNewRecord() {
        _state.update { it.copy(dnsEditor = CloudflareDnsEditorTarget.New, editorError = null) }
    }

    fun openEditRecord(record: CloudflareDnsRecord) {
        _state.update { it.copy(dnsEditor = CloudflareDnsEditorTarget.Existing(record), editorError = null) }
    }

    fun dismissEditor() {
        if (isWorking(_state.value.dnsEditor?.record?.id ?: CloudflareZoneResourceIds.NEW_DNS_RECORD)) return
        _state.update { it.copy(dnsEditor = null, editorError = null) }
    }

    /** Validates the editor and asks for confirmation; nothing is sent until it is confirmed. */
    fun requestSaveRecord(draft: CloudflareDnsRecordDraft) {
        val existing = _state.value.dnsEditor?.record
        val input = try {
            draft.toInput(existing)
        } catch (error: Exception) {
            _state.update { it.copy(editorError = cloudflareUserMessage(error)) }
            return
        }
        _state.update { it.copy(editorError = null) }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = if (existing == null) "Create this DNS record?" else "Save changes to this DNS record?",
                message = "This changes live DNS for $zoneDisplayName.\n\n${describe(input)}",
                confirmLabel = if (existing == null) "Create Record" else "Save Changes",
                resourceId = existing?.id ?: zoneId,
                workingId = existing?.id ?: CloudflareZoneResourceIds.NEW_DNS_RECORD,
            ),
        ) { confirmation ->
            cancelDnsLoad()
            try {
                val saved = if (existing != null) {
                    api.updateDnsRecord(zoneId, existing.id, input, confirmation)
                } else {
                    api.createDnsRecord(zoneId, input, confirmation)
                }
                // The mutation response is authoritative; reconcile it directly.
                _state.update { state ->
                    state.copy(
                        dnsRecords = (state.dnsRecords.filterNot { it.id == saved.id || it.id == existing?.id } + saved)
                            .sortedWith(CloudflareDnsRecord.DISPLAY_ORDER),
                        hasLoadedDns = true,
                        dnsError = null,
                        dnsEditor = null,
                        editorError = null,
                    )
                }
                showSuccess(if (existing != null) "DNS record updated." else "DNS record created.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(editorError = cloudflareUserMessage(error)) }
                throw error
            }
        }
    }

    fun requestDeleteRecord(record: CloudflareDnsRecord) {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this DNS record?",
                message = "${record.name} → ${record.content ?: "structured data"} will be permanently removed.",
                confirmLabel = "Delete ${record.type} Record",
                resourceId = record.id,
                destructive = true,
            ),
        ) { confirmation ->
            cancelDnsLoad()
            api.deleteDnsRecord(zoneId, record.id, confirmation)
            _state.update { state ->
                state.copy(dnsRecords = state.dnsRecords.filterNot { it.id == record.id }, hasLoadedDns = true)
            }
            showSuccess("DNS record deleted.")
        }
    }

    // MARK: Cache purge

    fun openPurge() {
        _state.update { it.copy(showingPurge = true, purgeError = null) }
    }

    fun dismissPurge() {
        if (isWorking(CloudflareZoneResourceIds.CACHE_PURGE)) return
        _state.update { it.copy(showingPurge = false, purgeError = null) }
    }

    fun requestPurge(kind: CloudflareCachePurgeKind, values: String, confirmationText: String) {
        val purge = try {
            kind.purge(values, confirmationText, zoneDisplayName)
        } catch (error: Exception) {
            _state.update { it.copy(purgeError = cloudflareUserMessage(error)) }
            return
        }
        _state.update { it.copy(purgeError = null) }
        val entries = purge.entries
        val detail = entries?.let { list ->
            val shown = list.take(MAXIMUM_LISTED_PURGE_VALUES).joinToString("\n")
            val more = list.size - MAXIMUM_LISTED_PURGE_VALUES
            "\n\n$shown" + if (more > 0) "\n…and $more more" else ""
        }.orEmpty()
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = kind.confirmationTitle(),
                message = kind.confirmationMessage(zoneDisplayName) + detail,
                confirmLabel = "Purge Cache",
                resourceId = zoneId,
                destructive = true,
                workingId = CloudflareZoneResourceIds.CACHE_PURGE,
            ),
        ) { confirmation ->
            try {
                api.purgeCache(zoneId, purge, confirmation)
                _state.update { it.copy(showingPurge = false, purgeError = null) }
                showSuccess("Cache purge accepted by Cloudflare.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(purgeError = cloudflareUserMessage(error)) }
                throw error
            }
        }
    }

    /** Exposed for tests: the purge payload the sheet would confirm. */
    internal fun purgePreview(kind: CloudflareCachePurgeKind, values: String, confirmationText: String): CloudflareCachePurge =
        kind.purge(values, confirmationText, zoneDisplayName)

    // MARK: Loading

    private fun loadZone(force: Boolean) {
        if (!force && (_state.value.zone != null || zoneJob?.isActive == true)) return
        zoneJob?.cancel()
        val generation = ++zoneGeneration
        _state.update { it.copy(isZoneLoading = true, zoneError = null) }
        zoneJob = viewModelScope.launch {
            try {
                val zone = api.fetchZone(zoneId)
                if (generation == zoneGeneration) _state.update { it.copy(zone = zone, isZoneLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == zoneGeneration) {
                    _state.update { it.copy(zoneError = cloudflareUserMessage(error), isZoneLoading = false) }
                }
            }
        }
    }

    private fun analyticsKey(state: CloudflareZoneDetailUiState = _state.value): String =
        if (state.range == CloudflareAnalyticsRange.CUSTOM) {
            "custom|${state.customFrom.toEpochMilli()}|${state.customTo.toEpochMilli()}"
        } else {
            state.range.name
        }

    private fun restoreAnalyticsForSelectedRange() {
        val cached = analyticsCache[analyticsKey()]
        _state.update {
            it.copy(
                analytics = cached?.analytics,
                breakdowns = cached?.breakdowns,
                breakdownError = cached?.breakdownError,
                analyticsError = null,
            )
        }
    }

    private fun loadAnalytics(force: Boolean) {
        val key = analyticsKey()
        val cached = analyticsCache[key]
        if (cached != null) {
            _state.update {
                it.copy(analytics = cached.analytics, breakdowns = cached.breakdowns, breakdownError = cached.breakdownError, analyticsError = null)
            }
            if (!force && cached.breakdownError == null) return
        }
        analyticsJob?.cancel()
        val generation = ++analyticsGeneration
        val state = _state.value
        val (from, to) = state.range.dates(clock()) ?: (state.customFrom to state.customTo)
        _state.update { it.copy(isAnalyticsLoading = true, analyticsError = null) }
        analyticsJob = viewModelScope.launch {
            try {
                val plan = api.fetchAnalyticsPlan(zoneId, from, to)
                val (summary, breakdowns) = coroutineScope {
                    val summary = async { runCatching { api.fetchZoneAnalytics(zoneId, from, to, plan) } }
                    val breakdowns = async { runCatching { api.fetchZoneAnalyticsBreakdowns(zoneId, from, to, plan) } }
                    summary.await() to breakdowns.await()
                }
                if (generation != analyticsGeneration) return@launch
                listOf(summary.exceptionOrNull(), breakdowns.exceptionOrNull()).filterIsInstance<CancellationException>()
                    .firstOrNull()?.let { throw it }
                val value = summary.getOrNull()
                if (value == null) {
                    _state.update {
                        it.copy(analyticsError = cloudflareUserMessage(summary.exceptionOrNull()!!), isAnalyticsLoading = false)
                    }
                    return@launch
                }
                val breakdownError = breakdowns.exceptionOrNull()?.let(::cloudflareUserMessage)
                analyticsCache[key] = CachedAnalytics(value, breakdowns.getOrNull(), breakdownError)
                _state.update {
                    it.copy(
                        analytics = value,
                        breakdowns = breakdowns.getOrNull(),
                        breakdownError = breakdownError,
                        analyticsError = null,
                        isAnalyticsLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == analyticsGeneration) {
                    _state.update { it.copy(analyticsError = cloudflareUserMessage(error), isAnalyticsLoading = false) }
                }
            }
        }
    }

    private fun loadDns(force: Boolean) {
        if (!force && (_state.value.hasLoadedDns || dnsJob?.isActive == true)) return
        dnsJob?.cancel()
        val generation = ++dnsGeneration
        _state.update { it.copy(isDnsLoading = !it.hasLoadedDns, dnsError = null) }
        dnsJob = viewModelScope.launch {
            try {
                val records = api.fetchDnsRecords(zoneId).sortedWith(CloudflareDnsRecord.DISPLAY_ORDER)
                if (generation == dnsGeneration) {
                    _state.update { it.copy(dnsRecords = records, hasLoadedDns = true, isDnsLoading = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == dnsGeneration) {
                    _state.update { it.copy(dnsError = cloudflareUserMessage(error), isDnsLoading = false) }
                }
            }
        }
    }

    private fun cancelDnsLoad() {
        dnsJob?.cancel()
        dnsJob = null
        dnsGeneration += 1
        _state.update { it.copy(isDnsLoading = false) }
    }

    companion object {
        private const val MAXIMUM_LISTED_PURGE_VALUES = 5

        /** One-line summary of what the save will write, shown in the confirmation. */
        fun describe(input: CloudflareDnsRecordInput): String = buildString {
            append(input.type.uppercase()).append(' ').append(input.name)
            append(" → ").append(input.content ?: "structured data")
            append(" · TTL ").append(CloudflareDnsRecordDraft.ttlLabel(input.ttl))
            input.proxied?.let { append(if (it) " · Proxied" else " · DNS only") }
            input.priority?.let { append(" · Priority ").append(it) }
        }
    }
}
