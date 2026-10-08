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
data class TipJarUiState(
    val products: List<TipProduct> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val purchasingId: String? = null,
    val didTip: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Tip jar backed by RevenueCat so tips are tracked alongside subscriptions. Tips are consumables
 * that unlock nothing: they never grant the "Verceltics Pro" entitlement.
 */
class TipJarViewModel(
    private val gateway: BillingGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TipJarUiState())
    val uiState: StateFlow<TipJarUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadProducts()
    }

    fun loadProducts() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false, errorMessage = null) }
            val products = try {
                gateway.fetchTips(BillingProducts.tipIds)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update {
                it.copy(products = products, isLoading = false, loadFailed = products.isEmpty())
            }
        }
    }

    fun purchase(activity: Activity, productId: String) {
        viewModelScope.launch {
            runPurchase(productId) { gateway.purchaseTip(activity, productId) }
        }
    }

    internal suspend fun runPurchase(productId: String, purchase: suspend () -> PurchaseOutcome) {
        if (_uiState.value.purchasingId != null) return
        _uiState.update { it.copy(purchasingId = productId, errorMessage = null) }
        val outcome = try {
            purchase()
        } catch (error: CancellationException) {
            _uiState.update { it.copy(purchasingId = null) }
            throw error
        } catch (_: Exception) {
            PurchaseOutcome.Failed("Something went wrong. Please try again.")
        }
        _uiState.update {
            when (outcome) {
                is PurchaseOutcome.Completed -> it.copy(purchasingId = null, didTip = true)
                PurchaseOutcome.Cancelled -> it.copy(purchasingId = null)
                is PurchaseOutcome.Failed -> it.copy(purchasingId = null, errorMessage = outcome.message)
            }
        }
    }

    fun dismissThankYou() {
        _uiState.update { it.copy(didTip = false) }
    }

    class Factory(private val gateway: BillingGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TipJarViewModel::class.java))
            return TipJarViewModel(gateway) as T
        }
    }
}
