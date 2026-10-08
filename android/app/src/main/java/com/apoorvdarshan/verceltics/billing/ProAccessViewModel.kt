package com.apoorvdarshan.verceltics.billing

import android.app.Activity
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PaywallAlert(val title: String, val message: String)

@Immutable
data class ProAccessUiState(
    val isBillingAvailable: Boolean,
    val hasPro: Boolean = false,
    /** False until the first entitlement check finishes; Pro stays locked meanwhile. */
    val hasCheckedEntitlements: Boolean = false,
    val plans: List<ProPlan> = emptyList(),
    val selectedPlanId: String? = null,
    val isLoadingPlans: Boolean = false,
    val plansError: String? = null,
    val isPurchasing: Boolean = false,
    val isRestoring: Boolean = false,
    val alert: PaywallAlert? = null,
) {
    val selectedPlan: ProPlan? get() = plans.firstOrNull { it.packageId == selectedPlanId }
    val isTransactionInFlight: Boolean get() = isPurchasing || isRestoring

    fun plan(kind: ProPlanKind): ProPlan? = plans.firstOrNull { it.kind == kind }
}

/**
 * App-wide Pro state, the Android counterpart of iOS `PaywallManager`. RevenueCat's
 * "Verceltics Pro" entitlement is the only source of truth; nothing is cached locally.
 */
class ProAccessViewModel(
    private val gateway: BillingGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProAccessUiState(isBillingAvailable = gateway.isAvailable))
    val uiState: StateFlow<ProAccessUiState> = _uiState.asStateFlow()

    private var entitlementJob: Job? = null
    private var plansJob: Job? = null

    init {
        viewModelScope.launch {
            gateway.proStatusUpdates.collect { hasPro ->
                _uiState.update { it.copy(hasPro = hasPro, hasCheckedEntitlements = true) }
            }
        }
        refreshEntitlements()
    }

    fun refreshEntitlements() {
        if (entitlementJob?.isActive == true) return
        entitlementJob = viewModelScope.launch { checkEntitlements() }
    }

    /** Re-checks Pro when the app returns to the foreground so lapsed or refunded access locks. */
    fun onForeground() {
        if (_uiState.value.hasCheckedEntitlements) refreshEntitlements()
    }

    fun loadPlans() {
        val state = _uiState.value
        if (state.plans.isNotEmpty() || plansJob?.isActive == true) return
        plansJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPlans = true, plansError = null) }
            val loaded = try {
                gateway.fetchPlans()
            } catch (error: CancellationException) {
                throw error
            } catch (error: BillingUnavailableException) {
                _uiState.update { it.copy(isLoadingPlans = false, plansError = error.message) }
                return@launch
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isLoadingPlans = false, plansError = "Failed to load purchase options.")
                }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    plans = loaded,
                    selectedPlanId = current.selectedPlanId?.takeIf { id -> loaded.any { it.packageId == id } }
                        ?: defaultPlan(loaded)?.packageId,
                    isLoadingPlans = false,
                    plansError = if (loaded.isEmpty()) "No purchase options are available right now." else null,
                )
            }
            checkEntitlements()
        }
    }

    fun selectPlan(packageId: String) {
        _uiState.update { state ->
            if (state.isTransactionInFlight || state.plans.none { it.packageId == packageId }) {
                state
            } else {
                state.copy(selectedPlanId = packageId)
            }
        }
    }

    fun purchaseSelectedPlan(activity: Activity) {
        val plan = _uiState.value.selectedPlan ?: return
        viewModelScope.launch {
            runPurchase { gateway.purchasePlan(activity, plan.packageId) }
        }
    }

    internal suspend fun runPurchase(purchase: suspend () -> PurchaseOutcome) {
        if (_uiState.value.isTransactionInFlight) return
        _uiState.update { it.copy(isPurchasing = true, alert = null) }
        val outcome = try {
            purchase()
        } catch (error: CancellationException) {
            _uiState.update { it.copy(isPurchasing = false) }
            throw error
        } catch (_: Exception) {
            PurchaseOutcome.Failed("Purchase failed. Please try again.")
        }
        when (outcome) {
            is PurchaseOutcome.Completed -> _uiState.update {
                it.copy(
                    isPurchasing = false,
                    hasPro = it.hasPro || outcome.hasPro,
                    hasCheckedEntitlements = true,
                    alert = if (outcome.hasPro) {
                        null
                    } else {
                        PaywallAlert(
                            title = "Purchase couldn’t be completed",
                            message = "Google Play finished the purchase, but Pro isn’t active yet. Tap Restore purchases to try again.",
                        )
                    },
                )
            }

            PurchaseOutcome.Cancelled -> _uiState.update { it.copy(isPurchasing = false) }

            is PurchaseOutcome.Failed -> {
                _uiState.update {
                    it.copy(
                        isPurchasing = false,
                        alert = PaywallAlert("Purchase couldn’t be completed", outcome.message),
                    )
                }
                checkEntitlements()
            }
        }
    }

    fun restorePurchases() {
        if (_uiState.value.isTransactionInFlight) return
        _uiState.update { it.copy(isRestoring = true, alert = null) }
        viewModelScope.launch {
            val hasPro = try {
                gateway.restorePurchases()
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isRestoring = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isRestoring = false,
                        alert = PaywallAlert(
                            title = "Restore couldn’t be completed",
                            message = (error as? BillingUnavailableException)?.message
                                ?: "Restore failed. Please try again.",
                        ),
                    )
                }
                checkEntitlements()
                return@launch
            }
            _uiState.update {
                it.copy(
                    isRestoring = false,
                    hasPro = hasPro,
                    hasCheckedEntitlements = true,
                    alert = if (hasPro) {
                        null
                    } else {
                        PaywallAlert(
                            title = "No purchase found",
                            message = "No active Verceltics Pro purchase was found for this Google account.",
                        )
                    },
                )
            }
        }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(alert = null) }
    }

    private suspend fun checkEntitlements() {
        val hasPro = try {
            gateway.fetchProStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Matches iOS: the first failed check means free; later failures keep the last state.
            null
        }
        _uiState.update {
            it.copy(
                hasPro = hasPro ?: (it.hasCheckedEntitlements && it.hasPro),
                hasCheckedEntitlements = true,
            )
        }
    }

    class Factory(private val gateway: BillingGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProAccessViewModel::class.java))
            return ProAccessViewModel(gateway) as T
        }
    }
}

internal fun defaultPlan(plans: List<ProPlan>): ProPlan? =
    plans.firstOrNull { it.kind == ProPlanKind.YEARLY }
        ?: plans.firstOrNull { it.kind == ProPlanKind.MONTHLY }
        ?: plans.firstOrNull { it.kind == ProPlanKind.LIFETIME }
