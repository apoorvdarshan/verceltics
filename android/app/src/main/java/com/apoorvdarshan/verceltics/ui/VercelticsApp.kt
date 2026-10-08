package com.apoorvdarshan.verceltics.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.apoorvdarshan.verceltics.ui.sample.SampleRegistrarScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.content.edit
import com.apoorvdarshan.verceltics.billing.ProAccessGate
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarConnectionCards
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarConnectionStatus
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarOperation
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarRoute
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarUiState
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarViewModel
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceConnectionCards
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceConnectionStatus
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceOperation
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceProviderIds
import com.apoorvdarshan.verceltics.ui.sites.SiteServiceRoute
import com.apoorvdarshan.verceltics.ui.sites.SiteServicesUiState
import com.apoorvdarshan.verceltics.ui.sites.SiteServicesViewModel
import com.apoorvdarshan.verceltics.ui.sites.connectedProviderIds as connectedSiteServiceIdsOf
import com.apoorvdarshan.verceltics.ui.hosting.HostingConnectionStatus
import com.apoorvdarshan.verceltics.ui.hosting.HostingProviderConnectionCards
import com.apoorvdarshan.verceltics.ui.hosting.HostingProviderRoute
import com.apoorvdarshan.verceltics.ui.hosting.HostingProvidersViewModel
import com.apoorvdarshan.verceltics.ui.hosting.connectedProviderIds as connectedHostingIds
import com.apoorvdarshan.verceltics.ui.registrar.connectedProviderIds as connectedRegistrarIdsOf
import com.apoorvdarshan.verceltics.billing.ProAccessViewModel
import com.apoorvdarshan.verceltics.billing.TipJarViewModel
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.billing.PaywallScreen
import com.apoorvdarshan.verceltics.ui.billing.ProAccess
import com.apoorvdarshan.verceltics.ui.billing.TipJarContent
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareConnectionStatus
import com.apoorvdarshan.verceltics.ui.components.AdaptiveNavigationScaffold
import com.apoorvdarshan.verceltics.ui.components.AppNavigationDestination
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareConnectionCard
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareViewModel
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyConnectionCard
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyConnectionStatus
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyRoute
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyViewModel
import com.apoorvdarshan.verceltics.ui.onboarding.FirstConnectionFlow
import com.apoorvdarshan.verceltics.ui.onboarding.FirstLaunchExperienceStore
import com.apoorvdarshan.verceltics.ui.onboarding.FirstLaunchPresentation
import com.apoorvdarshan.verceltics.ui.onboarding.firstLaunchPresentation
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedCacheState
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedConnectionStatus
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedOperation
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedRoute
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedUiState
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedViewModel
import com.apoorvdarshan.verceltics.ui.screens.AboutScreen
import com.apoorvdarshan.verceltics.ui.screens.ProviderDetailScreen
import com.apoorvdarshan.verceltics.ui.screens.VercelWorkspaceScreen
import com.apoorvdarshan.verceltics.ui.screens.WorkspaceScreen
import com.apoorvdarshan.verceltics.ui.screens.about.AboutAppearance
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenAction
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenState
import com.apoorvdarshan.verceltics.ui.screens.about.AboutUpdateState
import com.apoorvdarshan.verceltics.ui.screens.about.currentAndroidAppVersion
import com.apoorvdarshan.verceltics.ui.screens.about.showsUpdateBadge
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleConnectionCard
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleConnectionStatus
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleOperation
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleRoute
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleUiState
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleViewModel
import kotlinx.coroutines.delay

private const val UI_PREFERENCES = "verceltics.ui"
private const val LAST_PRIMARY_WORKSPACE = "lastPrimaryWorkspace"
internal const val PAGE_SPEED_PROVIDER_ID = "pageSpeed"
private const val NETLIFY_PROVIDER_ID = "netlify"
private const val CLOUDFLARE_PROVIDER_ID = "cloudflare"
internal const val SEARCH_CONSOLE_PROVIDER_ID = "googleSearchConsole"

/** Upper bound on the first-launch loading screen if a saved connection is slow to restore. */
private const val RESTORE_WAIT_MILLIS = 2_500L

private enum class MainDestination(
    val id: String,
    val navigationDestination: AppNavigationDestination,
    val workspace: Workspace? = null,
) {
    HOSTING("hosting", AppNavigationDestination.HOSTING, Workspace.HOSTING),
    REGISTRARS("registrars", AppNavigationDestination.REGISTRARS, Workspace.REGISTRARS),
    SITES("sites", AppNavigationDestination.SITES, Workspace.SITES),
    ABOUT("about", AppNavigationDestination.ABOUT),
    ;

    companion object {
        fun fromId(id: String?): MainDestination = entries.firstOrNull { it.id == id } ?: HOSTING

        fun fromNavigation(destination: AppNavigationDestination): MainDestination = when (destination) {
            AppNavigationDestination.HOSTING -> HOSTING
            AppNavigationDestination.REGISTRARS -> REGISTRARS
            AppNavigationDestination.SITES -> SITES
            AppNavigationDestination.ABOUT -> ABOUT
        }

        fun fromWorkspace(workspace: Workspace): MainDestination = when (workspace) {
            Workspace.HOSTING -> HOSTING
            Workspace.REGISTRARS -> REGISTRARS
            Workspace.SITES -> SITES
        }
    }
}

/**
 * Native Android app shell matching the SwiftUI app root and MainTabView hierarchy.
 *
 * Four destinations are persistent. Search is a contextual action that focuses the current or
 * last primary workspace; it is deliberately not a fifth destination and tab taps do not build a
 * synthetic back stack. Compact windows use the bottom dock and windows at least 600dp wide use a
 * navigation rail.
 *
 * When [firstLaunchExperience] is supplied and nothing is connected, the tabs are hidden and the
 * first-connection flow (one-time welcome, then the connect catalog) is shown instead, like iOS.
 * [onProjectsFirstLoaded] is forwarded from the Vercel workspace for the one-time rating prompt.
 */
@Composable
fun VercelticsApp(
    vercelConnectionViewModel: VercelConnectionViewModel,
    pageSpeedViewModel: PageSpeedViewModel,
    netlifyViewModel: NetlifyViewModel,
    cloudflareViewModel: CloudflareViewModel,
    searchConsoleViewModel: SearchConsoleViewModel,
    aboutState: AboutScreenState = defaultAboutScreenState(),
    onAboutAction: (AboutScreenAction) -> Unit = {},
    modifier: Modifier = Modifier,
    isSampleData: Boolean = false,
    onToggleSampleData: (() -> Unit)? = null,
    proAccessViewModel: ProAccessViewModel? = null,
    tipJarViewModel: TipJarViewModel? = null,
    onOpenExternalUri: (String) -> Unit = {},
    hostingViewModel: HostingProvidersViewModel? = null,
    onRequestGoogleSignIn: (Set<String>) -> Unit = {},
    registrarViewModel: RegistrarViewModel? = null,
    siteServicesViewModel: SiteServicesViewModel? = null,
    firstLaunchExperience: FirstLaunchExperienceStore? = null,
    onProjectsFirstLoaded: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
    }
    val restoredWorkspace = remember(preferences) {
        Workspace.entries.firstOrNull {
            it.id == preferences.getString(LAST_PRIMARY_WORKSPACE, Workspace.HOSTING.id)
        } ?: Workspace.HOSTING
    }
    var destinationId by rememberSaveable {
        mutableStateOf(MainDestination.fromWorkspace(restoredWorkspace).id)
    }
    var lastWorkspaceId by rememberSaveable { mutableStateOf(restoredWorkspace.id) }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var hostingSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var registrarSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var sitesSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var pageSpeedSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var netlifySearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var cloudflareSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var searchConsoleSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var hostingRefreshRequestId by rememberSaveable { mutableIntStateOf(0) }
    var hostingProviderSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var registrarRouteSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var siteServicesSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var registrarHubQuery by rememberSaveable { mutableStateOf("") }
    var sitesHubQuery by rememberSaveable { mutableStateOf("") }
    // A provider route opened by Search records the current request id on first composition, so
    // its search request is issued one composition later. Never restored: it must not replay.
    var pendingRouteSearchProviderId by remember { mutableStateOf<String?>(null) }
    var restoreWaitExpired by remember { mutableStateOf(false) }
    val connectionState by vercelConnectionViewModel.uiState.collectAsStateWithLifecycle()
    val pageSpeedState by pageSpeedViewModel.uiState.collectAsStateWithLifecycle()
    val netlifyState by netlifyViewModel.uiState.collectAsStateWithLifecycle()
    val cloudflareState by cloudflareViewModel.uiState.collectAsStateWithLifecycle()
    val searchConsoleState by searchConsoleViewModel.uiState.collectAsStateWithLifecycle()
    val hostingState = hostingViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val connectedHostingProviderIds = hostingState?.connectedHostingIds.orEmpty()
    val registrarState = registrarViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val connectedRegistrarIds = registrarState?.connectedRegistrarIdsOf.orEmpty()
    val siteServicesState = siteServicesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val connectedSiteServiceIds = siteServicesState?.connectedSiteServiceIdsOf.orEmpty()
    val connectedSiteProviderIds = remember(
        pageSpeedState.isConnected,
        searchConsoleState.isConnected,
        connectedSiteServiceIds,
    ) {
        buildSet {
            if (pageSpeedState.isConnected) add(PAGE_SPEED_PROVIDER_ID)
            if (searchConsoleState.isConnected) add(SEARCH_CONSOLE_PROVIDER_ID)
            addAll(connectedSiteServiceIds)
        }
    }
    val registrarRestoreError = registrarRestoreFailureMessage(registrarState)
    val siteServicesRestoreError = siteServicesRestoreFailureMessage(siteServicesState)

    // Hub search across connected cards (iOS `.searchable` on the Registrars and Sites roots).
    val usesRegistrarHubSearch = registrarState != null && usesHubSearch(connectedRegistrarIds.size)
    val registrarHubText = remember(registrarState, usesRegistrarHubSearch) {
        if (usesRegistrarHubSearch) registrarHubSearchText(registrarState) else emptyMap()
    }
    val registrarHubMatches = remember(registrarHubText, registrarHubQuery, connectedRegistrarIds) {
        if (registrarHubText.isEmpty()) connectedRegistrarIds else hubSearchMatches(registrarHubText, registrarHubQuery)
    }
    val siteHubText = remember(pageSpeedState, searchConsoleState, siteServicesState) {
        siteHubSearchText(pageSpeedState, searchConsoleState, siteServicesState)
    }
    val usesSitesHubSearch = usesHubSearch(siteHubText.size)
    val sitesHubMatches = remember(siteHubText, sitesHubQuery, usesSitesHubSearch) {
        if (usesSitesHubSearch) hubSearchMatches(siteHubText, sitesHubQuery) else siteHubText.keys
    }

    // First-launch gating, from the view models the shell already owns.
    val hasAnyConnection = connectionState.status.let {
        it == VercelConnectionStatus.CONNECTED || it == VercelConnectionStatus.SAVED_UNAVAILABLE
    } ||
        netlifyState.isConnected ||
        cloudflareState.isConnected ||
        pageSpeedState.isConnected ||
        searchConsoleState.isConnected ||
        connectedHostingProviderIds.isNotEmpty() ||
        connectedRegistrarIds.isNotEmpty() ||
        connectedSiteServiceIds.isNotEmpty() ||
        // A failed restore implies saved accounts exist; keep the shell so its banner is visible.
        registrarRestoreError != null ||
        siteServicesRestoreError != null
    val isRestoringConnections = connectionState.status == VercelConnectionStatus.RESTORING ||
        netlifyState.status == NetlifyConnectionStatus.RESTORING ||
        cloudflareState.status == CloudflareConnectionStatus.RESTORING ||
        pageSpeedState.status == PageSpeedConnectionStatus.RESTORING ||
        searchConsoleState.status == SearchConsoleConnectionStatus.RESTORING ||
        hostingState?.providers?.values?.any { it.status == HostingConnectionStatus.RESTORING } == true ||
        registrarState?.providers?.values?.any { it.status == RegistrarConnectionStatus.RESTORING } == true ||
        siteServicesState?.services?.values?.any { it.status == SiteServiceConnectionStatus.RESTORING } == true
    val proState = proAccessViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val hasActiveSubscription = proState?.let { it.hasCheckedEntitlements && it.hasPro } == true
    val presentation = firstLaunchPresentation(
        isEnabled = firstLaunchExperience != null,
        isSampleData = isSampleData,
        hasAnyConnection = hasAnyConnection,
        isRestoringConnections = isRestoringConnections && !restoreWaitExpired,
        hasCompletedWelcome = firstLaunchExperience?.hasCompletedWelcome == true,
        hasActiveSubscription = hasActiveSubscription,
    )

    val destination = MainDestination.fromId(destinationId)
    val provider = providerId?.let(IntegrationCatalog::provider)
    val destinationState = rememberSaveableStateHolder()
    val haptic = LocalHapticFeedback.current
    val activity = LocalActivity.current
    val tipJarState = tipJarViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // The pending tap lives only in composition, like iOS @State: it is never restored later.
    val proGate = remember { ProAccessGate<() -> Unit>() }
    var isPaywallVisible by rememberSaveable { mutableStateOf(false) }
    // Sample data is a preview with fictional accounts, so its detail screens stay open to all.
    val isProUnlocked = proState == null || proState.hasPro || isSampleData
    val isProConfirmedLocked = proState != null && !isSampleData &&
        proState.hasCheckedEntitlements && !proState.hasPro
    val proAccess = remember(proState == null, isProUnlocked, isProConfirmedLocked) {
        if (proState == null) {
            ProAccess.Unlocked
        } else {
            ProAccess(isUnlocked = isProUnlocked, isConfirmedLocked = isProConfirmedLocked) { onUnlocked ->
                if (proGate.request(onUnlocked, hasProAccess = false) == null) isPaywallVisible = true
            }
        }
    }

    fun dismissPaywall() {
        isPaywallVisible = false
        val hasProNow = proAccessViewModel?.uiState?.value?.hasPro == true
        proGate.resumeAfterDismiss(hasProAccess = hasProNow)?.invoke()
    }

    LaunchedEffect(isPaywallVisible) {
        if (isPaywallVisible) proAccessViewModel?.loadPlans()
    }

    LaunchedEffect(isPaywallVisible, proState?.hasPro) {
        if (isPaywallVisible && proState?.hasPro == true) dismissPaywall()
    }

    LaunchedEffect(Unit) {
        delay(RESTORE_WAIT_MILLIS)
        restoreWaitExpired = true
    }

    // Existing connected, subscribed, or sample-data users never see the new welcome.
    LaunchedEffect(firstLaunchExperience, hasAnyConnection, hasActiveSubscription, isSampleData) {
        firstLaunchExperience?.migrateIfNeeded(
            hasAnyConnection = hasAnyConnection,
            hasActiveSubscription = hasActiveSubscription,
            isSampleData = isSampleData,
        )
    }

    fun closeProvider() {
        providerId = null
        cloudflareSearchRequestId = 0
    }

    fun selectDestination(selected: MainDestination) {
        closeProvider()
        destinationId = selected.id
        selected.workspace?.let { workspace ->
            lastWorkspaceId = workspace.id
            if (!isSampleData) preferences.edit { putString(LAST_PRIMARY_WORKSPACE, workspace.id) }
        }
    }

    /** Issues the search request owned by an open provider route. */
    fun requestRouteSearch(routeProviderId: String) {
        when {
            routeProviderId == PAGE_SPEED_PROVIDER_ID -> pageSpeedSearchRequestId += 1
            routeProviderId == SEARCH_CONSOLE_PROVIDER_ID -> searchConsoleSearchRequestId += 1
            routeProviderId == NETLIFY_PROVIDER_ID -> netlifySearchRequestId += 1
            routeProviderId == CLOUDFLARE_PROVIDER_ID -> cloudflareSearchRequestId += 1
            routeProviderId in SiteServiceProviderIds -> siteServicesSearchRequestId += 1
            routeProviderId in RegistrarProvider.ids -> registrarRouteSearchRequestId += 1
            routeProviderId in HostingProvider.ids -> hostingProviderSearchRequestId += 1
        }
    }

    /** Registrars and Sites roots search their connected providers, never the add catalog. */
    fun requestRootSearch(target: RootSearchTarget, onHubOrCatalog: () -> Unit) {
        when (target) {
            is RootSearchTarget.Provider -> {
                providerId = target.providerId
                pendingRouteSearchProviderId = target.providerId
            }
            RootSearchTarget.HubSearch, RootSearchTarget.ConnectionCatalog -> onHubOrCatalog()
        }
    }

    fun requestSearch() {
        if (siteServicesViewModel != null && provider != null && provider.id in SiteServiceProviderIds) {
            siteServicesSearchRequestId += 1
            return
        }
        if (registrarViewModel != null && provider != null && provider.id in RegistrarProvider.ids) {
            registrarRouteSearchRequestId += 1
            return
        }
        if (provider?.id == PAGE_SPEED_PROVIDER_ID) {
            pageSpeedSearchRequestId += 1
            return
        }
        if (provider?.id == NETLIFY_PROVIDER_ID && netlifyState.isConnected) {
            netlifySearchRequestId += 1
            return
        }
        if (provider?.id == CLOUDFLARE_PROVIDER_ID && cloudflareState.dashboard != null) {
            cloudflareSearchRequestId += 1
            return
        }
        if (provider?.id == SEARCH_CONSOLE_PROVIDER_ID) {
            searchConsoleSearchRequestId += 1
            return
        }
        if (provider != null && provider.id in connectedHostingProviderIds) {
            hostingProviderSearchRequestId += 1
            return
        }

        val preferredWorkspace = provider?.workspace
            ?: destination.workspace
            ?: Workspace.entries.firstOrNull { it.id == lastWorkspaceId }
            ?: Workspace.HOSTING
        selectDestination(MainDestination.fromWorkspace(preferredWorkspace))
        when (preferredWorkspace) {
            Workspace.HOSTING -> hostingSearchRequestId += 1
            Workspace.REGISTRARS -> if (registrarViewModel == null) {
                registrarSearchRequestId += 1
            } else {
                requestRootSearch(rootSearchTarget(connectedRegistrarIds)) { registrarSearchRequestId += 1 }
            }
            Workspace.SITES -> requestRootSearch(rootSearchTarget(siteHubText.keys)) {
                sitesSearchRequestId += 1
            }
        }
    }

    LaunchedEffect(pendingRouteSearchProviderId, providerId) {
        val pending = pendingRouteSearchProviderId ?: return@LaunchedEffect
        pendingRouteSearchProviderId = null
        // The route has composed with the old request id by now; leaving it first cancels search.
        if (providerId == pending) requestRouteSearch(pending)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        proAccessViewModel?.onForeground()
        hostingRefreshRequestId += 1
        hostingViewModel?.onForeground()
        registrarViewModel?.onForeground()
        siteServicesViewModel?.onForeground()
        netlifyViewModel.onForeground()
        cloudflareViewModel.onForeground()
        searchConsoleViewModel.onForeground()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        hostingViewModel?.onBackground()
        registrarViewModel?.onBackground()
        siteServicesViewModel?.onBackground()
        netlifyViewModel.onBackground()
        cloudflareViewModel.onBackground()
        searchConsoleViewModel.onBackground()
    }

    BackHandler(
        enabled = provider != null &&
            provider.id != PAGE_SPEED_PROVIDER_ID &&
            provider.id != NETLIFY_PROVIDER_ID &&
            provider.id != CLOUDFLARE_PROVIDER_ID &&
            provider.id != SEARCH_CONSOLE_PROVIDER_ID &&
            !(hostingViewModel != null && provider.id in HostingProvider.ids) &&
            !(registrarViewModel != null && provider.id in RegistrarProvider.ids) &&
            !(siteServicesViewModel != null && provider.id in SiteServiceProviderIds),
    ) {
        closeProvider()
    }

    CompositionLocalProvider(LocalProAccess provides proAccess) {
        Box(modifier = modifier.fillMaxSize()) {
            if (presentation != FirstLaunchPresentation.SHELL && provider == null) {
                FirstConnectionFlow(
                    presentation = presentation,
                    onContinue = { firstLaunchExperience?.completeWelcome() },
                    onConnectProvider = { selected ->
                        selectDestination(MainDestination.fromWorkspace(selected.workspace))
                        providerId = selected.id
                    },
                    onPreviewSampleData = onToggleSampleData,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AdaptiveNavigationScaffold(
                    selectedDestination = destination.navigationDestination,
                    onDestinationSelected = { selected ->
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        selectDestination(MainDestination.fromNavigation(selected))
                    },
                    onSearch = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        requestSearch()
                    },
                    // While the first connection is being made, provider routes stay full screen.
                    showsNavigation = presentation == FirstLaunchPresentation.SHELL,
                    showsAboutBadge = aboutState.update.showsUpdateBadge,
                    topBar = {
                        if (isSampleData) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                    .padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Sample data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onToggleSampleData?.invoke() }) { Text("Exit preview", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (provider != null) {
                        when (provider.id) {
                            PAGE_SPEED_PROVIDER_ID -> PageSpeedRoute(
                                viewModel = pageSpeedViewModel,
                                onBack = ::closeProvider,
                                searchRequestId = pageSpeedSearchRequestId,
                                modifier = Modifier.fillMaxSize(),
                            )
                            NETLIFY_PROVIDER_ID -> NetlifyRoute(
                                viewModel = netlifyViewModel,
                                onBack = ::closeProvider,
                                searchRequestId = netlifySearchRequestId,
                                modifier = Modifier.fillMaxSize(),
                            )
                            CLOUDFLARE_PROVIDER_ID -> CloudflareRoute(
                                viewModel = cloudflareViewModel,
                                onBack = ::closeProvider,
                                searchRequestId = cloudflareSearchRequestId,
                                modifier = Modifier.fillMaxSize(),
                            )
                            SEARCH_CONSOLE_PROVIDER_ID -> SearchConsoleRoute(
                                viewModel = searchConsoleViewModel,
                                onBack = ::closeProvider,
                                searchRequestId = searchConsoleSearchRequestId,
                                modifier = Modifier.fillMaxSize(),
                            )
                            in HostingProvider.ids -> if (hostingViewModel != null) {
                                HostingProviderRoute(
                                    viewModel = hostingViewModel,
                                    providerId = provider.id,
                                    onBack = ::closeProvider,
                                    onRequestGoogleSignIn = onRequestGoogleSignIn,
                                    searchRequestId = hostingProviderSearchRequestId,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ProviderDetailScreen(provider = provider, vercelConnectionViewModel = vercelConnectionViewModel, onBack = ::closeProvider, modifier = Modifier.fillMaxSize())
                            }
                            in SiteServiceProviderIds -> if (siteServicesViewModel != null) {
                                SiteServiceRoute(
                                    viewModel = siteServicesViewModel,
                                    providerId = provider.id,
                                    onBack = ::closeProvider,
                                    searchRequestId = siteServicesSearchRequestId,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ProviderDetailScreen(provider = provider, vercelConnectionViewModel = vercelConnectionViewModel, onBack = ::closeProvider, modifier = Modifier.fillMaxSize())
                            }
                            in RegistrarProvider.ids -> if (registrarViewModel != null) {
                                RegistrarRoute(
                                    viewModel = registrarViewModel,
                                    providerId = provider.id,
                                    onBack = ::closeProvider,
                                    searchRequestId = registrarRouteSearchRequestId,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (isSampleData) {
                                SampleRegistrarScreen(initialProviderId = provider.id, onBack = ::closeProvider, modifier = Modifier.fillMaxSize())
                            } else {
                                ProviderDetailScreen(provider = provider, vercelConnectionViewModel = vercelConnectionViewModel, onBack = ::closeProvider, modifier = Modifier.fillMaxSize())
                            }
                            else -> ProviderDetailScreen(
                                provider = provider,
                                vercelConnectionViewModel = vercelConnectionViewModel,
                                onBack = ::closeProvider,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        destinationState.SaveableStateProvider(destination.id) {
                            when (destination) {
                                MainDestination.HOSTING -> VercelWorkspaceScreen(
                                    vercelConnectionViewModel = vercelConnectionViewModel,
                                    onProjectsFirstLoaded = onProjectsFirstLoaded,
                                    searchRequestId = hostingSearchRequestId,
                                    refreshRequestId = hostingRefreshRequestId,
                                    onConnectProvider = { providerId = it.id },
                                    connectedProviderIds = buildSet {
                                        if (cloudflareState.isConnected) add(CLOUDFLARE_PROVIDER_ID)
                                        if (netlifyState.isConnected) add(NETLIFY_PROVIDER_ID)
                                        addAll(connectedHostingProviderIds)
                                    },
                                    connectedProviderContent = if (
                                        netlifyState.isConnected || cloudflareState.isConnected ||
                                        connectedHostingProviderIds.isNotEmpty()
                                    ) {
                                        {
                                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                if (cloudflareState.isConnected) {
                                                    CloudflareConnectionCard(
                                                        state = cloudflareState,
                                                        onClick = { providerId = CLOUDFLARE_PROVIDER_ID },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (netlifyState.isConnected) {
                                                    NetlifyConnectionCard(
                                                        state = netlifyState,
                                                        onClick = { providerId = NETLIFY_PROVIDER_ID },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (hostingState != null && connectedHostingProviderIds.isNotEmpty()) {
                                                    HostingProviderConnectionCards(
                                                        state = hostingState,
                                                        onOpenProvider = { providerId = it },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (connectionState.status != VercelConnectionStatus.DISCONNECTED) {
                                                    if (!cloudflareState.isConnected) {
                                                        ThemedActionButton(
                                                            text = "CONNECT CLOUDFLARE",
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                                providerId = CLOUDFLARE_PROVIDER_ID
                                                            },
                                                            tone = ThemedActionTone.NEUTRAL,
                                                            modifier = Modifier.fillMaxWidth(),
                                                            testTag = "workspace.hosting.connectCloudflare",
                                                        )
                                                    }
                                                    if (!netlifyState.isConnected) {
                                                        ThemedActionButton(
                                                            text = "CONNECT NETLIFY",
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                                                providerId = NETLIFY_PROVIDER_ID
                                                            },
                                                            tone = ThemedActionTone.NEUTRAL,
                                                            modifier = Modifier.fillMaxWidth(),
                                                            testTag = "workspace.hosting.connectNetlify",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )

                                MainDestination.REGISTRARS -> if (isSampleData && registrarViewModel == null) {
                                    SampleRegistrarScreen(searchRequestId = registrarSearchRequestId, modifier = Modifier.fillMaxSize())
                                } else WorkspaceScreen(
                                    workspace = Workspace.REGISTRARS,
                                    onConnectProvider = { providerId = it.id },
                                    onAccountAction = {},
                                    persistenceError = registrarRestoreError,
                                    searchRequestId = registrarSearchRequestId,
                                    connectedProviderIds = connectedRegistrarIds,
                                    connectedContent = if (registrarState != null && connectedRegistrarIds.isNotEmpty()) {
                                        {
                                            RegistrarConnectionCards(
                                                state = registrarState.onlyProviders(registrarHubMatches),
                                                onOpenProvider = { providerId = it },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    hubSearchQuery = registrarHubQuery.takeIf { usesRegistrarHubSearch },
                                    onHubSearchQueryChange = { registrarHubQuery = it },
                                    hubSearchMatchCount = registrarHubMatches.size,
                                    onRefresh = if (registrarViewModel != null && connectedRegistrarIds.isNotEmpty()) {
                                        { connectedRegistrarIds.forEach(registrarViewModel::refresh) }
                                    } else {
                                        null
                                    },
                                    isRefreshing = registrarState?.providers?.values?.any {
                                        it.operation == RegistrarOperation.REFRESHING
                                    } == true,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                MainDestination.SITES -> WorkspaceScreen(
                                    workspace = Workspace.SITES,
                                    onConnectProvider = { providerId = it.id },
                                    onAccountAction = {},
                                    persistenceError = siteServicesRestoreError,
                                    searchRequestId = sitesSearchRequestId,
                                    connectedProviderIds = connectedSiteProviderIds,
                                    modifier = Modifier.fillMaxSize(),
                                    connectedContent = if (
                                        pageSpeedState.isConnected || searchConsoleState.isConnected ||
                                        connectedSiteServiceIds.isNotEmpty()
                                    ) {
                                        {
                                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                if (searchConsoleState.isConnected && SEARCH_CONSOLE_PROVIDER_ID in sitesHubMatches) {
                                                    SearchConsoleConnectionCard(
                                                        state = searchConsoleState,
                                                        onClick = {
                                                            providerId = SEARCH_CONSOLE_PROVIDER_ID
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (pageSpeedState.isConnected && PAGE_SPEED_PROVIDER_ID in sitesHubMatches) {
                                                    PageSpeedConnectionCard(
                                                        state = pageSpeedState,
                                                        onClick = { providerId = PAGE_SPEED_PROVIDER_ID },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (siteServicesState != null && connectedSiteServiceIds.isNotEmpty()) {
                                                    SiteServiceConnectionCards(
                                                        state = siteServicesState.onlyServices(sitesHubMatches),
                                                        onOpenProvider = { providerId = it },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    hubSearchQuery = sitesHubQuery.takeIf { usesSitesHubSearch },
                                    onHubSearchQueryChange = { sitesHubQuery = it },
                                    hubSearchMatchCount = sitesHubMatches.size,
                                    onRefresh = if (siteHubText.isNotEmpty()) {
                                        {
                                            if (searchConsoleState.isConnected) searchConsoleViewModel.refresh()
                                            if (pageSpeedState.isConnected) pageSpeedViewModel.refresh()
                                            siteServicesViewModel?.let { viewModel ->
                                                connectedSiteServiceIds.forEach(viewModel::refresh)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    isRefreshing = searchConsoleState.operation == SearchConsoleOperation.REFRESHING ||
                                        pageSpeedState.operation == PageSpeedOperation.REFRESHING ||
                                        siteServicesState?.services?.values?.any {
                                            it.operation == SiteServiceOperation.REFRESHING
                                        } == true,
                                )

                                MainDestination.ABOUT -> AboutScreen(
                                    state = aboutState,
                                    onAction = onAboutAction,
                                    isSampleData = isSampleData,
                                    onToggleSampleData = onToggleSampleData,
                                    hasPro = proState?.let { it.hasCheckedEntitlements && it.hasPro },
                                    onUnlockPro = { proAccess.requestPro {} },
                                    tipJarContent = if (tipJarViewModel != null && tipJarState != null) {
                                        {
                                            TipJarContent(
                                                state = tipJarState,
                                                onTip = { productId ->
                                                    activity?.let { tipJarViewModel.purchase(it, productId) }
                                                },
                                                onRetry = tipJarViewModel::loadProducts,
                                                onDone = tipJarViewModel::dismissThankYou,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            if (proAccessViewModel != null && proState != null) {
                AnimatedVisibility(
                    visible = isPaywallVisible,
                    enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(),
                ) {
                    PaywallScreen(
                        state = proState,
                        onClose = ::dismissPaywall,
                        onSelectPlan = proAccessViewModel::selectPlan,
                        onPurchase = { activity?.let(proAccessViewModel::purchaseSelectedPlan) },
                        onRestore = proAccessViewModel::restorePurchases,
                        onRetryPlans = proAccessViewModel::loadPlans,
                        onOpenUri = onOpenExternalUri,
                        onDismissAlert = proAccessViewModel::dismissAlert,
                    )
                }
            }
        }
    }
}

// MARK: - Root search routing (pure, unit tested)

/** Where the Search action goes from a Registrars or Sites root, mirroring iOS. */
internal sealed interface RootSearchTarget {
    /** Exactly one provider is connected: open it and focus its own resource search. */
    data class Provider(val providerId: String) : RootSearchTarget

    /** Several providers are connected: focus the hub search across their cards. */
    data object HubSearch : RootSearchTarget

    /** Nothing is connected: fall back to the "Connect an integration" catalog. */
    data object ConnectionCatalog : RootSearchTarget
}

internal fun rootSearchTarget(connectedProviderIds: Collection<String>): RootSearchTarget =
    when (connectedProviderIds.size) {
        0 -> RootSearchTarget.ConnectionCatalog
        1 -> RootSearchTarget.Provider(connectedProviderIds.first())
        else -> RootSearchTarget.HubSearch
    }

/** A root offers hub search once it shows at least two connected provider cards. */
internal fun usesHubSearch(connectedProviderCount: Int): Boolean = connectedProviderCount >= 2

/**
 * Providers whose searchable text contains every query term, in card order. A blank query
 * matches every provider. Matching uses the catalog's accent- and punctuation-insensitive rules.
 */
internal fun hubSearchMatches(searchableText: Map<String, String>, query: String): Set<String> {
    val terms = IntegrationCatalog.normalizeSearchText(query).split(' ').filter(String::isNotEmpty)
    if (terms.isEmpty()) return LinkedHashSet(searchableText.keys)
    return searchableText.entries
        .filter { (_, text) ->
            val normalized = IntegrationCatalog.normalizeSearchText(text)
            terms.all(normalized::contains)
        }
        .mapTo(LinkedHashSet()) { it.key }
}

/** Registrar hub cards are searchable by registrar, account, and every loaded domain name. */
internal fun registrarHubSearchText(state: RegistrarUiState): Map<String, String> =
    state.connectedRegistrarIdsOf.associateWith { id ->
        val providerState = state.provider(id)
        buildList {
            IntegrationCatalog.provider(id)?.let { add(it.displayName); add(it.description) }
            providerState.savedAccount?.displayName?.let(::add)
            providerState.dashboard?.let { dashboard ->
                add(dashboard.account.displayName)
                dashboard.domains.forEach { add(it.name) }
            }
        }.joinToString(" ")
    }

/**
 * Sites hub cards in on-screen order (Search Console, PageSpeed, then site services), searchable by
 * provider, account, and loaded property, site, or resource names.
 */
internal fun siteHubSearchText(
    pageSpeed: PageSpeedUiState,
    searchConsole: SearchConsoleUiState,
    siteServices: SiteServicesUiState?,
): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    if (searchConsole.isConnected) {
        result[SEARCH_CONSOLE_PROVIDER_ID] = buildList {
            IntegrationCatalog.provider(SEARCH_CONSOLE_PROVIDER_ID)?.let { add(it.displayName); add(it.description) }
            searchConsole.savedAccount?.displayName?.let(::add)
            searchConsole.dashboard?.let { dashboard ->
                add(dashboard.account.displayName)
                dashboard.properties.forEach { add(it.displayName); add(it.siteUrl) }
            }
        }.joinToString(" ")
    }
    if (pageSpeed.isConnected) {
        result[PAGE_SPEED_PROVIDER_ID] = buildList {
            IntegrationCatalog.provider(PAGE_SPEED_PROVIDER_ID)?.let { add(it.displayName); add(it.description) }
            pageSpeed.savedSiteUrl?.let(::add)
            pageSpeed.dashboard?.let { add(it.siteName); add(it.siteUrl) }
        }.joinToString(" ")
    }
    siteServices?.connectedSiteServiceIdsOf?.forEach { id ->
        val service = siteServices.service(id)
        result[id] = buildList {
            IntegrationCatalog.provider(id)?.let { add(it.displayName); add(it.description) }
            service.savedAccountName?.let(::add)
            service.dashboard?.let { dashboard ->
                add(dashboard.accountName)
                dashboard.accountDetail?.let(::add)
                dashboard.resources.forEach { resource ->
                    add(resource.name)
                    resource.url?.let(::add)
                }
            }
        }.joinToString(" ")
    }
    return result
}

/** A registrar state whose cards only include [providerIds]; others read as disconnected. */
internal fun RegistrarUiState.onlyProviders(providerIds: Set<String>): RegistrarUiState =
    if (connectedRegistrarIdsOf.all { it in providerIds }) this else copy(providers = providers.filterKeys { it in providerIds })

/** A site services state whose cards only include [providerIds]; others read as disconnected. */
internal fun SiteServicesUiState.onlyServices(providerIds: Set<String>): SiteServicesUiState =
    if (connectedSiteServiceIdsOf.all { it in providerIds }) this else copy(services = services.filterKeys { it in providerIds })

// MARK: - Saved-account banners (pure, unit tested)

/** "Saved registrar accounts need attention" when registrar restore failed as a whole. */
internal fun registrarRestoreFailureMessage(state: RegistrarUiState?): String? =
    state?.restoreError?.takeIf(String::isNotBlank)

/**
 * "Saved site services need attention" when the saved site-service store could not be restored:
 * the view model then marks every service disconnected with an error and no operation in flight.
 */
internal fun siteServicesRestoreFailureMessage(state: SiteServicesUiState?): String? {
    val services = state?.services?.values?.takeIf { it.isNotEmpty() } ?: return null
    val restoreFailed = services.all {
        it.status == SiteServiceConnectionStatus.DISCONNECTED && it.operation == null && !it.error.isNullOrBlank()
    }
    if (!restoreFailed) return null
    return services.mapNotNull { it.error }.distinct().singleOrNull() ?: SITE_SERVICES_RESTORE_FAILED_MESSAGE
}

internal const val SITE_SERVICES_RESTORE_FAILED_MESSAGE =
    "Saved site service connections could not be read on this device. Reconnect to continue."

@Composable
private fun PageSpeedConnectionCard(
    state: PageSpeedUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = remember { checkNotNull(IntegrationCatalog.provider(PAGE_SPEED_PROVIDER_ID)) }
    val accent = Color(provider.accentColor)
    val haptic = LocalHapticFeedback.current
    val subtitle = state.dashboard?.siteUrl
        ?: state.savedSiteUrl
        ?: state.error
        ?: when (state.status) {
            PageSpeedConnectionStatus.DISCONNECTED -> "Not connected"
            PageSpeedConnectionStatus.RESTORING -> "Checking saved connection"
            else -> "Saved connection"
        }
    val status = pageSpeedConnectionCardStatus(state)
    val statusColor = if (status == "Attention") PageSpeedAttention else accent
    val stacked = shouldStackPageSpeedConnectionCard(LocalDensity.current.fontScale)

    OffsetPanel(
        modifier = modifier
            .heightIn(min = 88.dp)
            .semantics { stateDescription = status },
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent,
        shadowColor = accent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.sites.pageSpeedConnection",
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderMark(provider = provider, size = 46.dp)
                    Spacer(Modifier.width(13.dp))
                    PageSpeedConnectionCopy(
                        title = provider.displayName,
                        subtitle = subtitle,
                        subtitleMaxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    StatusPill(text = status, color = statusColor)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderMark(provider = provider, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                PageSpeedConnectionCopy(
                    title = provider.displayName,
                    subtitle = subtitle,
                    subtitleMaxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(text = status, color = statusColor)
            }
        }
    }
}

@Composable
private fun PageSpeedConnectionCopy(
    title: String,
    subtitle: String,
    subtitleMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = if (subtitleMaxLines > 1) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = subtitleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun pageSpeedConnectionCardStatus(state: PageSpeedUiState): String = when (state.status) {
    PageSpeedConnectionStatus.CONNECTED -> if (
        state.error != null ||
        state.dashboard == null ||
        state.dashboard.isPartial ||
        state.dashboard.warnings.isNotEmpty() ||
        state.dashboard.cacheState == PageSpeedCacheState.CACHED_STALE
    ) {
        "Attention"
    } else {
        when (state.dashboard.cacheState) {
            PageSpeedCacheState.LIVE -> "Live"
            PageSpeedCacheState.CACHED_FRESH -> "Saved"
            PageSpeedCacheState.CACHED_STALE -> "Attention"
        }
    }
    PageSpeedConnectionStatus.SAVED_UNAVAILABLE -> "Attention"
    PageSpeedConnectionStatus.RESTORING -> "Restoring"
    PageSpeedConnectionStatus.DISCONNECTED -> "Disconnected"
}

internal fun shouldStackPageSpeedConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

private val PageSpeedAttention = Color(0xFFE29A00)

private fun defaultAboutScreenState() = AboutScreenState(
    version = currentAndroidAppVersion(),
    appearance = AboutAppearance.SYSTEM,
    update = AboutUpdateState.NotConfigured,
)
