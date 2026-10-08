package com.apoorvdarshan.verceltics.ui.registrar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionStore
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRawApi
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiBackend
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiProfile
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiWorkspaceController
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
    /** Making another saved account active. */
    SWITCHING,
    /** Removing the current account or every account. */
    DISCONNECTING,
}

data class RegistrarProviderUiState(
    val providerId: String,
    val status: RegistrarConnectionStatus = RegistrarConnectionStatus.RESTORING,
    /** The active account's portfolio. */
    val dashboard: RegistrarDashboardUi? = null,
    val savedAccount: RegistrarAccountUi? = null,
    /** Every saved account of this registrar, in saved order (iOS `RegistrarAccountMenu`). */
    val accounts: List<RegistrarAccountUi> = emptyList(),
    /** The connection form is open to add another account while this registrar stays connected. */
    val isAddingAccount: Boolean = false,
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

    /** The account whose portfolio is shown, when the saved record could be read. */
    val activeAccount: RegistrarAccountUi?
        get() = dashboard?.account ?: savedAccount

    /** Saved accounts for the account menu (just the active one for single-account fixtures). */
    val savedAccounts: List<RegistrarAccountUi>
        get() = accounts.ifEmpty { listOfNotNull(activeAccount) }

    /** The credential form is on screen: a first connection or an additional account. */
    val showsConnectionForm: Boolean
        get() = status == RegistrarConnectionStatus.DISCONNECTED || (isAddingAccount && isConnected)
}

/** What a destructive registrar confirmation removes (iOS `RegistrarAccountMenu.RemovalIntent`). */
enum class RegistrarRemovalScope {
    CURRENT_ACCOUNT,
    ALL_ACCOUNTS,
}

data class RegistrarRemovalConfirmation(
    val providerId: String,
    val scope: RegistrarRemovalScope,
    /** The account captured when removal was requested; null when the saved record is unreadable. */
    val accountId: String? = null,
    val accountName: String? = null,
    /** Saved accounts of the registrar when removal was requested. */
    val accountCount: Int = 1,
) {
    /** Removing the only (or an unreadable) account empties the registrar, like Remove All. */
    val removesEveryAccount: Boolean
        get() = scope == RegistrarRemovalScope.ALL_ACCOUNTS || accountCount <= 1 || accountId.isNullOrEmpty()
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
    /** A pending destructive confirmation (Remove Current / Remove All). */
    val removalConfirmation: RegistrarRemovalConfirmation? = null,
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

/**
 * The activity owns FLAG_SECURE only while registrar credentials can be visible or in flight: the
 * first-connection form, the add-account form, and any connection request.
 */
val RegistrarUiState.requiresSecureWindow: Boolean
    get() {
        val providerId = visibleProviderId ?: return false
        val provider = provider(providerId)
        return provider.showsConnectionForm || provider.operation == RegistrarOperation.CONNECTING
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
    private val apiWorkspaces = HashMap<String, ProviderApiWorkspaceController>()

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

    /**
     * Validates and saves credentials from the connection form: the first account of a
     * disconnected registrar, or (in add-account mode) another account while the current ones stay
     * connected. A reconnect of a saved identity rotates that account in place.
     */
    fun connect(request: RegistrarConnectRequest) {
        val providerId = request.providerId
        if (RegistrarProvider.fromId(providerId) == null) return
        val current = _uiState.value.provider(providerId)
        if (!current.showsConnectionForm || current.isBusy) return
        val baseline = current.copy(error = null, notice = null, failedOperation = null)
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

    /** Refreshes the active account; an open add-account form stays open. */
    fun refresh(providerId: String) {
        val baseline = _uiState.value.provider(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        launchProviderOperation(providerId, RegistrarOperation.REFRESHING, baseline) { generation ->
            gateway.refresh(providerId).fold(
                onSuccess = { dashboard ->
                    if (isCurrent(providerId, generation)) {
                        applyDashboard(providerId, dashboard, keepsAddingAccount = baseline.isAddingAccount)
                    }
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
        if (state.status != RegistrarConnectionStatus.CONNECTED || state.isBusy || state.isAddingAccount) return
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
                            val completed = reconciled.savedIdentity() != baseline.savedIdentity()
                            setProvider(
                                if (completed) {
                                    reconciled.copy(notice = "The connection completed before cancellation and remains saved.")
                                } else {
                                    // Nothing changed: an add-account form stays open for another try.
                                    reconciled.copy(
                                        isAddingAccount = baseline.isAddingAccount && reconciled.isConnected,
                                        notice = "Request cancelled.",
                                    )
                                },
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

    /** Asks to remove the active account ("Remove Current"); the only account disconnects the registrar. */
    fun requestDisconnectConfirmation(providerId: String) =
        requestRemoval(providerId, RegistrarRemovalScope.CURRENT_ACCOUNT)

    /** Asks to remove every saved account of the registrar ("Remove All"). */
    fun requestRemoveAllAccounts(providerId: String) =
        requestRemoval(providerId, RegistrarRemovalScope.ALL_ACCOUNTS)

    private fun requestRemoval(providerId: String, scope: RegistrarRemovalScope) {
        val provider = _uiState.value.provider(providerId)
        if (!provider.isConnected || provider.isBusy) return
        val account = provider.activeAccount
        _uiState.update {
            it.copy(
                removalConfirmation = RegistrarRemovalConfirmation(
                    providerId = providerId,
                    scope = scope,
                    accountId = account?.id,
                    accountName = account?.displayName,
                    accountCount = provider.savedAccounts.size,
                ),
            )
        }
    }

    fun dismissDisconnectConfirmation() {
        _uiState.update { it.copy(removalConfirmation = null) }
    }

    /** Confirms the pending removal: one account, or every account of the registrar. */
    fun confirmDisconnect() {
        val confirmation = _uiState.value.removalConfirmation ?: return
        val providerId = confirmation.providerId
        val baseline = _uiState.value.provider(providerId).copy(isAddingAccount = false)
        _uiState.update { it.copy(removalConfirmation = null) }
        if (!baseline.isConnected || baseline.isBusy) return
        closeApiWorkspace(providerId)
        if (_uiState.value.selectedProviderId == providerId) closeDomain()
        val accountId = confirmation.accountId
        val removesOne = !confirmation.removesEveryAccount && accountId != null && baseline.savedAccounts.size > 1
        launchProviderOperation(
            providerId = providerId,
            operation = RegistrarOperation.DISCONNECTING,
            baseline = baseline,
            onComplete = { refreshIfStale(providerId) },
        ) { generation ->
            val result = if (removesOne && accountId != null) {
                gateway.removeAccount(providerId, accountId)
            } else {
                gateway.disconnect(providerId).map { RegistrarProviderRestoreUi.NotConnected }
            }
            result.fold(
                onSuccess = { entry ->
                    if (isCurrent(providerId, generation)) setProvider(providerStateFrom(providerId, entry))
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

    /**
     * iOS `RegistrarStore.switchAccount(to:)`: shows another saved account's cached portfolio
     * immediately, then refreshes it when stale. A refresh of the previous account is cancelled.
     */
    fun switchAccount(providerId: String, accountId: String) {
        var current = _uiState.value.provider(providerId)
        if (!current.isConnected || current.activeAccount?.id == accountId) return
        if (current.savedAccounts.none { it.id == accountId }) return
        if (current.operation == RegistrarOperation.REFRESHING) {
            val refreshBaseline = providerBaselines.remove(providerId)
            nextGeneration(providerId)
            providerJobs.remove(providerId)?.cancel()
            current = (refreshBaseline ?: current).copy(operation = null)
            setProvider(current)
        }
        if (current.isBusy) return
        closeApiWorkspace(providerId)
        if (_uiState.value.selectedProviderId == providerId) closeDomain()
        val baseline = current.copy(isAddingAccount = false, error = null, notice = null, failedOperation = null)
        launchProviderOperation(
            providerId = providerId,
            operation = RegistrarOperation.SWITCHING,
            baseline = baseline,
            onComplete = { refreshIfStale(providerId) },
        ) { generation ->
            gateway.switchAccount(providerId, accountId).fold(
                onSuccess = { entry ->
                    if (isCurrent(providerId, generation)) setProvider(providerStateFrom(providerId, entry))
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setProvider(
                            baseline.copy(
                                operation = null,
                                error = safeMessage(error),
                                failedOperation = RegistrarOperation.SWITCHING,
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Opens the connection form for another account without disconnecting the current ones. */
    fun startAddingAccount(providerId: String) {
        val current = _uiState.value.provider(providerId)
        if (!current.isConnected || current.isBusy || current.savedAccounts.isEmpty() || current.isAddingAccount) return
        closeApiWorkspace(providerId)
        if (_uiState.value.selectedProviderId == providerId) closeDomain()
        updateProvider(providerId) {
            it.copy(isAddingAccount = true, error = null, notice = null, failedOperation = null)
        }
    }

    /** Closes the add-account form and returns to the active account's portfolio. */
    fun cancelAddingAccount(providerId: String) {
        val current = _uiState.value.provider(providerId)
        if (!current.isAddingAccount || current.isBusy) return
        updateProvider(providerId) {
            it.copy(isAddingAccount = false, error = null, notice = null, failedOperation = null)
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

    /**
     * The registrar's Complete API workspace (iOS `ProviderFullAPICatalogView` and
     * `RegistrarAPIExplorerView`). Created on first use; it restores an open workspace.
     */
    fun apiWorkspace(providerId: String): ProviderApiWorkspaceController? {
        val provider = RegistrarProvider.fromId(providerId) ?: return null
        return apiWorkspaces.getOrPut(providerId) {
            ProviderApiWorkspaceController(
                profile = ProviderApiProfile.registrar(provider),
                scope = viewModelScope,
                backend = object : ProviderApiBackend {
                    override suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse> =
                        gateway.sendApiRequest(providerId, request)
                },
                savedStateHandle = savedStateHandle,
                keyPrefix = "registrar.$providerId.completeApi",
            )
        }
    }

    /** Opens Complete API from the dashboard or (with [domainId]) a domain detail. Pro-gated by the route. */
    fun openApiWorkspace(providerId: String, domainId: String? = null) {
        val provider = RegistrarProvider.fromId(providerId) ?: return
        val state = _uiState.value.provider(providerId)
        if (state.status != RegistrarConnectionStatus.CONNECTED || state.isAddingAccount) return
        val domain = domainId?.let { id -> state.dashboard?.domains?.firstOrNull { it.id == id } }
        apiWorkspace(providerId)?.open(RegistrarRawApi.suggestedPath(provider, domain?.name))
    }

    fun closeApiWorkspace(providerId: String) {
        apiWorkspaces[providerId]?.close()
    }

    /**
     * Returns true when the route consumed back instead of asking the app shell to close it. Back
     * leaves an add-account form (cancelling a request in flight first) before leaving the route.
     */
    fun handleBack(providerId: String? = null): Boolean {
        val addingProviderId = (providerId ?: _uiState.value.visibleProviderId)
            ?.takeIf { _uiState.value.provider(it).isAddingAccount }
        return when {
            providerId != null && apiWorkspaces[providerId]?.state?.value?.isOpen == true -> {
                apiWorkspaces[providerId]?.back()
                true
            }
            _uiState.value.removalConfirmation != null -> {
                dismissDisconnectConfirmation()
                true
            }
            addingProviderId != null -> {
                if (_uiState.value.provider(addingProviderId).operation == RegistrarOperation.CONNECTING) {
                    cancelOperation(addingProviderId)
                } else {
                    cancelAddingAccount(addingProviderId)
                }
                true
            }
            _uiState.value.selectedDomainId != null -> {
                closeDomain()
                true
            }
            else -> false
        }
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
                accounts = restored.dashboard.accounts,
            )
            is RegistrarProviderRestoreUi.SavedWithoutInventory -> RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                accounts = restored.accounts,
                notice = "This connection has no saved domain portfolio. Refresh when you are online.",
            )
            is RegistrarProviderRestoreUi.SavedUnavailable -> RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.SAVED_UNAVAILABLE,
                error = restored.message,
            )
        }

    /** Identity of what is saved: a completed connection always changes it (new account or fresh portfolio). */
    private fun RegistrarProviderUiState.savedIdentity(): Triple<String?, List<String>, Long?> =
        Triple(activeAccount?.id, savedAccounts.map(RegistrarAccountUi::id), dashboard?.fetchedAtMillis)

    private fun applyDashboard(providerId: String, dashboard: RegistrarDashboardUi, keepsAddingAccount: Boolean = false) {
        setProvider(
            RegistrarProviderUiState(
                providerId = providerId,
                status = RegistrarConnectionStatus.CONNECTED,
                dashboard = dashboard,
                savedAccount = dashboard.account,
                accounts = dashboard.accounts,
                isAddingAccount = keepsAddingAccount,
            ),
        )
        val state = _uiState.value
        if (state.selectedProviderId == providerId &&
            dashboard.domains.none { it.id == state.selectedDomainId }
        ) {
            closeDomain()
        }
    }

    /**
     * Runs one operation for [providerId]. [onComplete] runs once the operation finished (and its
     * job was released) while it is still current, so it may start the next operation.
     */
    private fun launchProviderOperation(
        providerId: String,
        operation: RegistrarOperation,
        baseline: RegistrarProviderUiState,
        onComplete: (() -> Unit)? = null,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (providerJobs[providerId]?.isActive == true) return
        val generation = nextGeneration(providerId)
        providerBaselines[providerId] = baseline
        setProvider(baseline.copy(operation = operation, error = null, notice = null, failedOperation = null))
        val job = viewModelScope.launch {
            var completed = false
            try {
                block(generation)
                completed = true
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
            if (completed && isCurrent(providerId, generation)) onComplete?.invoke()
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
