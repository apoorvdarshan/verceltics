package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogSource
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogStore
import com.apoorvdarshan.verceltics.data.apicatalog.hasMissingRequiredParameters
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog

/**
 * The Complete API workspace for one provider: catalog → operation → explorer (→ multipart),
 * with its own back stack. Provider routes render it in place of their dashboard while
 * [ProviderApiWorkspaceState.isOpen]; opening it is Pro-gated by the caller.
 */
@Composable
fun ProviderApiWorkspace(
    controller: ProviderApiWorkspaceController,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    catalogSource: ProviderApiCatalogSource = ProviderApiCatalogStore.shared(LocalContext.current),
    accent: Color = providerAccent(controller.profile.providerId),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(controller, state.isOpen) {
        if (state.isOpen) controller.loadCatalog(catalogSource)
    }
    BackHandler(enabled = state.isOpen) { controller.back() }
    if (!state.isOpen) return

    val profile = controller.profile
    val catalog = state.loadedCatalog
    val destination = state.destination
    val title = when (destination) {
        ProviderApiDestination.Catalog -> "Complete API"
        is ProviderApiDestination.Operation -> catalog?.operation(destination.operationId)?.summary ?: "Operation"
        ProviderApiDestination.Explorer -> state.explorer?.title ?: "${profile.displayName} API"
        ProviderApiDestination.Multipart -> "Multipart Body"
    }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("providerApi.workspace"),
    ) {
        ProviderApiTopBar(
            title = title,
            onBack = { controller.back() },
            trailing = if (destination == ProviderApiDestination.Catalog && state.catalog !is ProviderApiCatalogState.Loading) {
                { ProviderApiRefreshAction(state.isRefreshingCatalog) { controller.loadCatalog(catalogSource, forceRefresh = true) } }
            } else {
                null
            },
        )
        val contentModifier = Modifier.weight(1f)
        when (destination) {
            ProviderApiDestination.Catalog -> ProviderFullApiCatalogScreen(
                state = state,
                accent = accent,
                onQueryChange = controller::setQuery,
                onSelectTag = controller::selectTag,
                onSelectAccess = controller::setAccess,
                onOpenOperation = controller::openOperation,
                onOpenManualExplorer = controller::openManualExplorer,
                onRetry = { controller.loadCatalog(catalogSource, forceRefresh = true) },
                onOpenLink = onOpenLink,
                modifier = contentModifier,
            )
            is ProviderApiDestination.Operation -> {
                val operation = catalog?.operation(destination.operationId)
                val draft = state.operationDraft
                if (operation != null && draft != null && draft.operationId == operation.id) {
                    ProviderApiOperationScreen(
                        operation = operation,
                        draft = draft,
                        managedHeaders = profile.managedHeaders,
                        hasMissingRequired = operation.hasMissingRequiredParameters(draft.values, profile.managedHeaders),
                        accent = accent,
                        onParameterChange = controller::updateParameter,
                        onBodyChange = controller::updateOperationBody,
                        onContentTypeChange = controller::updateOperationContentType,
                        onReview = controller::reviewOperation,
                        modifier = contentModifier,
                    )
                } else {
                    ProviderFullApiCatalogScreen(
                        state = state,
                        accent = accent,
                        onQueryChange = controller::setQuery,
                        onSelectTag = controller::selectTag,
                        onSelectAccess = controller::setAccess,
                        onOpenOperation = controller::openOperation,
                        onOpenManualExplorer = controller::openManualExplorer,
                        onRetry = { controller.loadCatalog(catalogSource, forceRefresh = true) },
                        onOpenLink = onOpenLink,
                        modifier = contentModifier,
                    )
                }
            }
            ProviderApiDestination.Explorer -> {
                val explorer = state.explorer
                if (explorer != null) {
                    ProviderApiExplorerScreen(
                        profile = profile,
                        explorer = explorer,
                        isSending = state.isSending,
                        showWriteConfirmation = state.showWriteConfirmation,
                        response = state.response,
                        requestError = state.requestError,
                        accent = accent,
                        callbacks = ProviderApiExplorerCallbacks(
                            onMethodChange = controller::setMethod,
                            onPathChange = controller::setPath,
                            onBodyChange = controller::setBody,
                            onHeadersChange = controller::setHeadersText,
                            onContentTypeChange = controller::setContentType,
                            onShowOptionalBody = controller::setShowOptionalBody,
                            onSend = controller::requestSend,
                            onConfirmSend = controller::confirmSend,
                            onDismissConfirmation = controller::dismissWriteConfirmation,
                            onBuildMultipart = controller::openMultipartComposer,
                            onFilePicked = controller::attachBinaryFile,
                            onFileError = controller::reportRequestError,
                            onRemoveAttachment = controller::removeAttachment,
                        ),
                        modifier = contentModifier,
                    )
                } else {
                    Column(contentModifier.padding(18.dp)) {
                        ApiEmptyState(Icons.Rounded.WarningAmber, "Request unavailable", "Go back and choose an operation again.")
                    }
                }
            }
            ProviderApiDestination.Multipart -> {
                val draft = state.multipart
                if (draft != null) {
                    ProviderApiMultipartScreen(
                        draft = draft,
                        accent = accent,
                        onNameChange = controller::updateMultipartName,
                        onValueChange = controller::updateMultipartValue,
                        onFilePicked = controller::attachMultipartFile,
                        onFileError = controller::reportMultipartError,
                        onRemove = controller::removeMultipartPart,
                        onAddField = controller::addMultipartField,
                        onCompose = controller::composeMultipart,
                        modifier = contentModifier,
                    )
                } else {
                    Column(contentModifier.padding(18.dp)) {
                        ApiEmptyState(Icons.Rounded.WarningAmber, "Form unavailable", "Go back and build the multipart upload again.")
                    }
                }
            }
        }
    }
}

/** The provider's catalog accent (falls back to the theme's primary color). */
@Composable
fun providerAccent(providerId: String): Color =
    IntegrationCatalog.provider(providerId)?.let { Color(it.accentColor) } ?: MaterialTheme.colorScheme.primary
