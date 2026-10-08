package com.apoorvdarshan.verceltics.ui.registrar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionStore
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RegistrarConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class RegistrarOperation {
    /** Reconciling saved state after a cancelled connection. */
    RESTORING,
    CONNECTING,
    REFRESHING,
    DISCONNECTING,
}

data class RegistrarProviderUiState(
    val providerId: String,
    val status: RegistrarConnectionStatus = RegistrarConnectionStatus.RESTORING,
    val dashboard: RegistrarDashboardUi? = null,
    val savedAccount: RegistrarAccountUi? = null,
    val operation: RegistrarOperation? = null,
    val error: String? = null,
    val notice: String? = null,
    /** The operation that produced [error], so screens can title and retry it correctly. */
    val failedOperation: RegistrarOperation? = null,
) {
    val isBusy: Boolean
        get() = operation != null || status == RegistrarConnectionStatus.RESTORING

    val isConnected: Boolean
        get() = status == RegistrarConnectionStatus.CONNECTED ||
            status == RegistrarConnectionStatus.SAVED_UNAVAILABLE
}

/** Public network address helper state (Namecheap ClientIp / Name.com allowlist). */
data class RegistrarPublicIpv4Ui(
    val providerId: String? = null,
    val isDetecting: Boolean = false,
    val address: String? = null,
    val error: String? = null,
)

data class RegistrarUiState(
    val providers: Map<String, RegistrarProviderUiState> =
        RegistrarProvider.ids.associateWith { RegistrarProviderUiState(it) },
    /** Restore failed for every registrar; shown as "Saved registrar accounts need attention". */
    val restoreError: String? = null,
    val selectedProviderId: String? = null,
    val selectedDomainId: String? = null,
    val disconnectConfirmationProviderId: String? = null,
    /** The registrar route currently on screen, used for credential window protection. */
    val visibleProviderId: String? = null,
    val publicIpv4: RegistrarPublicIpv4Ui = RegistrarPublicIpv4Ui(),
) {
    fun provider(providerId: String): RegistrarProviderUiState =
        providers[providerId] ?: RegistrarProviderUiState(providerId, RegistrarConnectionStatus.DISCONNECTED)

    fun selectedDomain(providerId: String): RegistrarDomainUi? {
        if (selectedProviderId != providerId) return null
        val domainId = selectedDomainId ?: return null
        return provider(providerId).dashboard?.domains?.firstOrNull { it.id == domainId }
    }

    override fun toString(): String =
        "RegistrarUiState(providers=${providers.values.map { "${it.providerId}:${it.status}:${it.operation}" }}, " +
            "selectedProviderId=$selectedProviderId, selectedDomainId=$selectedDomainId, " +
            "visibleProviderId=$visibleProviderId)"
}

/** Registrars with a saved connection (healthy or needing attention), in catalog order. */
val RegistrarUiState.connectedProviderIds: Set<String>
    get() = RegistrarProvider.ids.filterTo(LinkedHashSet()) { provider(it).isConnected }

/** The activity owns FLAG_SECURE only while registrar credentials can be visible or in flight. */
val RegistrarUiState.requiresSecureWindow: Boolean
    get() {
        val providerId = visibleProviderId ?: return false
        val provider = provider(providerId)
        return provider.status == RegistrarConnectionStatus.DISCONNECTED ||
            provider.operation == RegistrarOperation.CONNECTING
    }

/**
 * Activity-scoped owner for every registrar connection. Each registrar has independent
 * operations; the open domain detail survives configuration and process recreation.
 */
class RegistrarViewModel internal constructor(
    private val gateway: RegistrarUiGateway,
    private val savedStateHandle: SavedStateHandle,
    private val nowMillis: () -> Long,
) : ViewModel() {
    constructor(gateway: RegistrarUiGateway, savedStateHandle: SavedStateHandle) :
        this(gateway, savedStateHandle, System::currentTimeMillis)

    private val _uiState = MutableStateFlow(
        RegistrarUiState(
            selectedProviderId = savedStateHandle[SELECTED_PROVIDER_ID],
            selectedDomainId = savedStateHandle[SELECTED_DOMAIN_ID],
        ),
    )
    val uiState: StateFlow<RegistrarUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private var hasRestored = false
    private val providerJobs = HashMap<String, Job>()
    private val providerGenerations = HashMap<String, Long>()
    private val providerBaselines = HashMap<String, RegistrarProviderUiState>()
    private var publicIpv4Job: Job? = null
    private var publicIpv4Generation = 0L
    private var isForeground = false

    init {
        restore()
    }

    /** Offline restore of every registrar; also used when entering the sample-data preview. */
    fun restore() {
        restoreJob?.cancel()
        providerJobs.values.forEach(Job::cancel)
        providerJobs.clear()
        providerBaselines.clear()
        RegistrarProvider.ids.forEach(::nextGeneration)
        hasRestored = false
        val generation = ++restoreGeneration
        _uiState.update {
            RegistrarUiState(
                selectedProviderId = savedStateHandle[SELECTED_PROVIDER_ID],
                selectedDomainId = savedStateHandle[SELECTED_DOMAIN_ID],
                visibleProviderId = it.visibleProviderId,
                publicIpv4 = it.publicIpv4,
            )
        }
        restoreJob = viewModelScope.launch {
            gateway.restore().fold(
                onSuccess = { restored -> if (generation == restoreGeneration) applyRestore(restored) },
                onFailure = { error ->
                    if (generation == restoreGeneration) {
                        closeDomain()
                        _uiState.update { state ->
                            state.copy(
                                providers = RegistrarProvider.ids.associateWith {
                                    RegistrarProviderUiState(it, RegistrarConnectionStatus.DISCONNECTED)
                                },
                                restoreError = safeMessage(error),
                            )
                        }
                    }
                },
            )
            if (generation == restoreGeneration) {
                restoreJob = null
                hasRestored = true
                refreshStaleProvidersIfForeground()
            }
        }
    }

    fun connect(request: RegistrarConnectRequest) {
        val providerId = request.providerId
        if (RegistrarProvider.fromId(providerId) == null) return
        val current = _uiState.value.provider(providerId)
        if (current.status != RegistrarConnectionStatus.DISCONNECTED || current.isBusy) return
        val baseline = current.copy(error = null, notice = null)
        launchProviderOperation(providerId, RegistrarOperation.CONNECTING, baseline) { generation ->
            gateway.connect(request).fold(
                onSuccess = { dashboard ->
                    if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard)
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setProvider(
                            baseline.copy(
                                operation = null,
                                error = safeMessage(error),
                                failedOperation = RegistrarOperation.CONNECTING,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun refresh(providerId: String) {
        val baseline = _uiState.value.provider(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        launchProviderOperation(providerId, RegistrarOperation.REFRESHING, baseline) { generation ->
            gateway.refresh(providerId).fold(
                onSuccess = { dashboard ->
                    if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard)
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setProvider(
                            baseline.copy(
                                operation = null,
                                error = safeMessage(error),
                                failedOperation = RegistrarOperation.REFRESHING,
                                notice = if (baseline.dashboard != null) {
                                    "Showing the last saved domain portfolio."
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                },
            )
        }
    }

    /** iOS `load()` freshness rule: reload when the shown portfolio is cached or 15+ minutes old. */
    fun refreshIfStale(providerId: String) {
        val state = _uiState.value.provider(providerId)
        if (state.status != RegistrarConnectionStatus.CONNECTED || state.isBusy) return
        val dashboard = state.dashboard ?: return
        val stale = dashboard.cacheState != RegistrarCacheState.LIVE ||
            nowMillis() - dashboard.fetchedAtMillis >= RegistrarConnectionStore.CACHE_LIFETIME_MILLIS
        if (stale) refresh(providerId)
    }

    fun onForeground() {
        isForeground = true
        refreshStaleProvidersIfForeground()
    }

    fun onBackground() {
        isForeground = false
    }

    fun cancelOperation(providerId: String) {
        val current = _uiState.value.provider(providerId)
        val operation = current.operation
        if (operation != RegistrarOperation.CONNECTING && operation != RegistrarOperation.REFRESHING) return
        val baseline = providerBaselines.remove(providerId)
        val generation = nextGeneration(providerId)
        val cancelledJob = providerJobs.remove(providerId)
        cancelledJob?.cancel()
        if (operation == RegistrarOperation.CONNECTING && baseline != null) {
            setProvider(baseline.copy(operation = RegistrarOperation.RESTORING, notice = "Cancelling request…"))
            providerJobs[providerId] = viewModelScope.launch {
                cancelledJob?.join()
                gateway.restore().fold(
                    onSuccess = { restored ->
                        if (isCurrent(providerId, generation)) {
                            val entry = restored.providers[providerId] ?: RegistrarProviderRestoreUi.NotConnected
                            val reconciled = providerStateFrom(providerId, entry)
                            setProvider(
                                reconciled.copy(
                                    notice = if (entry is RegistrarProviderRestoreUi.Available) {
                                        "The connection completed before cancellation and remains saved."
                                    } else {
                                        "Request cancelled."
                                    },
                                ),
                            )
                        }
                    },
                    onFailure = { error ->
                        if (isCurrent(providerId, generation)) {
                            setProvider(
                                baseline.copy(
                                    operation = null,
                                    error = safeMessage(error),
                                    notice = "Request cancelled; saved connection status could not be verified.",
                                ),
                            )
                        }
                    },
                )
                if (isCurrent(providerId, generation)) providerJobs.remove(providerId)
            }
            return
        }
        setProvider((baseline ?: current).copy(operation = null, notice = "Request cancelled."))
    }

    fun requestDisconnectConfirmation(providerId: String) {
        val provider = _uiState.value.provider(providerId)
        if (provider.isConnected && !provider.isBusy) {
            _uiState.update { it.copy(disconnectConfirmationProviderId = providerId) }
        }
    }

    fun dismissDisconnectConfirmation() {
        _uiState.update { it.copy(disconnectConfirmationProviderId = null) }
    }

    fun confirmDisconnect() {
        val providerId = _uiState.value.disconnectConfirmationProviderId ?: return
        val baseline = _uiState.value.provider(providerId)
        _uiState.update { it.copy(disconnectConfirmationProviderId = null) }
        if (!baseline.isConnected || baseline.isBusy) return
        if (_uiState.value.selectedProviderId == providerId) closeDomain()
        launchProviderOperation(providerId, RegistrarOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect(providerId).fold(
                onSuccess = {
                    if (isCurrent(providerId, generation)) {
                        setProvider(RegistrarProviderUiState(providerId, RegistrarConnectionStatus.DISCONNECTED))
                    }
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setProvider(
                            baseline.copy(
                                operation = null,
                                error = safeMessage(error),
                                failedOperation = RegistrarOperation.DISCONNECTING,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun openDomain(providerId: String, domainId: String) {
        val domain = _uiState.value.provider(providerId).dashboard?.domains?.firstOrNull { it.id == domainId }
            ?: return
        savedStateHandle[SELECTED_PROVIDER_ID] = providerId
        savedStateHandle[SELECTED_DOMAIN_ID] = domain.id
        _uiState.update { it.copy(selectedProviderId = providerId, selectedDomainId = domain.id) }
    }

    fun closeDomain() {
        savedStateHandle[SELECTED_PROVIDER_ID] = null
        savedStateHandle[SELECTED_DOMAIN_ID] = null
        _uiState.update {
            if (it.selectedProviderId == null && it.selectedDomainId == null) {
                it
            } else {
                it.copy(selectedProviderId = null, selectedDomainId = null)
            }
        }
    }

    /** Returns true when the route consumed back instead of asking the app shell to close it. */
    fun handleBack(): Boolean = when {
        _uiState.value.disconnectConfirmationProviderId != null -> {
            dismissDisconnectConfirmation()
            true
        }
        _uiState.value.selectedDomainId != null -> {
            closeDomain()
            true
        }
        else -> false
    }

    fun setVisibleProvider(providerId: String) {
        val state = _uiState.value
        if (state.selectedProviderId != null && state.selectedProviderId != providerId) closeDomain()
        _uiState.update { if (it.visibleProviderId == providerId) it else it.copy(visibleProviderId = providerId) }
    }

    fun clearVisibleProvider(providerId: String) {
        _uiState.update { if (it.visibleProviderId == providerId) it.copy(visibleProviderId = null) else it }
    }

    /**
     * Detects this network's public IPv4. With [force] false an existing result (or a lookup in
     * flight) for the same registrar is reused, so recomposition does not re-query the service.
     */
    fun detectPublicIpv4(providerId: String, force: Boolean = true) {
        val provider = RegistrarProvider.fromId(providerId) ?: return
        if (!provider.showsPublicIpv4Helper) return
        val current = _uiState.value.publicIpv4
        if (!force && current.providerId == providerId && (current.address != null || current.isDetecting)) return
        val generation = ++publicIpv4Generation
        publicIpv4Job?.cancel()
        _uiState.update { it.copy(publicIpv4 = RegistrarPublicIpv4Ui(providerId = providerId, isDetecting = true)) }
        publicIpv4Job = viewModelScope.launch {
            gateway.detectPublicIpv4().fold(
                onSuccess = { address ->
                    if (generation == publicIpv4Generation) {
                        _uiState.update {
                            it.copy(publicIpv4 = RegistrarPublicIpv4Ui(providerId = providerId, address = address))
                        }
                    }
                },
                onFailure = {
                    if (generation == publicIpv4Generation) {
                        _uiState.update {
                            it.copy(
                                publicIpv4 = RegistrarPublicIpv4Ui(
                                    providerId = providerId,
                                    error = publicIpv4FailureMessage(provider),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    fun resetPublicIpv4() {
        publicIpv4Generation += 1
        publicIpv4Job?.cancel()
        publicIpv4Job = null
        _uiState.update { it.copy(publicIpv4 = RegistrarPublicIpv4Ui()) }
    }

    fun clearFeedback(providerId: String) {
        updateProvider(providerId) { it.copy(error = null, notice = null, failedOperation = null) }
    }

    private fun applyRestore(restored: RegistrarRestoreUi) {
        val providers = RegistrarProvider.ids.associateWith { id ->
            providerStateFrom(id, restored.providers[id] ?: RegistrarProviderRestoreUi.NotConnected)
        }
        val state = _uiState.value
        val selectionValid = state.selectedProviderId?.let { providerId ->
            providers[providerId]?.dashboard?.domains?.any { it.id == state.selectedDomainId }
        } == true
        if (!selectionValid) {
            savedStateHandle[SELECTED_PROVIDER_ID] = null
            savedStateHandle[SELECTED_DOMAIN_ID] = null
        }
        _uiState.update {
            it.copy(
                providers = providers,
                restoreError = null,
                selectedProviderId = if (selectionValid) it.selectedProviderId else null,
                selectedDomainId = if (selectionValid) it.selectedDomainId else null,
            )
        }
    }

    private fun providerStateFrom(providerId: String, restored: RegistrarProviderRestoreUi): RegistrarProviderUiState =
        when (restored) {
            RegistrarProviderRestoreUi.NotConnected ->
                RegistrarProviderUiState(providerId, RegistrarConnectionStatus.DISCONNECTED)
            is RegistrarProviderRestoreUi.Available -> RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.CONNECTED,
                dashboard = restored.dashboard,
                savedAccount = restored.dashboard.account,
            )
            is RegistrarProviderRestoreUi.SavedWithoutInventory -> RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                notice = "This connection has no saved domain portfolio. Refresh when you are online.",
            )
            is RegistrarProviderRestoreUi.SavedUnavailable -> RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.SAVED_UNAVAILABLE,
                error = restored.message,
            )
        }

    private fun applyDashboard(providerId: String, dashboard: RegistrarDashboardUi) {
        setProvider(
            RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.CONNECTED,
                dashboard = dashboard,
                savedAccount = dashboard.account,
            ),
        )
        val state = _uiState.value
        if (state.selectedProviderId == providerId &&
            dashboard.domains.none { it.id == state.selectedDomainId }
        ) {
            closeDomain()
        }
    }

    private fun launchProviderOperation(
        providerId: String,
        operation: RegistrarOperation,
        baseline: RegistrarProviderUiState,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (providerJobs[providerId]?.isActive == true) return
        val generation = nextGeneration(providerId)
        providerBaselines[providerId] = baseline
        setProvider(baseline.copy(operation = operation, error = null, notice = null, failedOperation = null))
        val job = viewModelScope.launch {
            try {
                block(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(providerId, generation)) {
                    setProvider(baseline.copy(operation = null, error = safeMessage(error), failedOperation = operation))
                }
            } finally {
                if (isCurrent(providerId, generation)) {
                    providerJobs.remove(providerId)
                    providerBaselines.remove(providerId)
                }
            }
        }
        if (job.isActive && isCurrent(providerId, generation)) providerJobs[providerId] = job
    }

    private fun refreshStaleProvidersIfForeground() {
        if (!isForeground || !hasRestored) return
        RegistrarProvider.ids.forEach(::refreshIfStale)
    }

    private fun setProvider(state: RegistrarProviderUiState) {
        _uiState.update { it.copy(providers = it.providers + (state.providerId to state)) }
    }

    private fun updateProvider(providerId: String, transform: (RegistrarProviderUiState) -> RegistrarProviderUiState) {
        _uiState.update { state ->
            state.copy(providers = state.providers + (providerId to transform(state.provider(providerId))))
        }
    }

    private fun nextGeneration(providerId: String): Long =
        ((providerGenerations[providerId] ?: 0L) + 1L).also { providerGenerations[providerId] = it }

    private fun isCurrent(providerId: String, generation: Long): Boolean =
        providerGenerations[providerId] == generation

    private fun safeMessage(error: Throwable): String =
        (error as? RegistrarUiException)?.message ?: "The registrar could not complete this request."

    override fun onCleared() {
        restoreGeneration += 1
        publicIpv4Generation += 1
        RegistrarProvider.ids.forEach(::nextGeneration)
        restoreJob?.cancel()
        publicIpv4Job?.cancel()
        providerJobs.values.forEach(Job::cancel)
        providerJobs.clear()
        super.onCleared()
    }

    class Factory(
        private val gateway: RegistrarUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(RegistrarViewModel::class.java)) {
                "Unsupported registrar ViewModel class."
            }
            return RegistrarViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val SELECTED_PROVIDER_ID = "registrar.selectedProviderId"
        internal const val SELECTED_DOMAIN_ID = "registrar.selectedDomainId"

        /** iOS copy for a failed public IPv4 detection. */
        internal fun publicIpv4FailureMessage(provider: RegistrarProvider): String =
            if (provider == RegistrarProvider.NAMECHEAP) {
                "Couldn’t detect this network. You can still enter the address manually."
            } else {
                "Couldn’t detect this network. Retry here or manage Name.com’s optional allowlist in its API settings."
            }
    }
}
