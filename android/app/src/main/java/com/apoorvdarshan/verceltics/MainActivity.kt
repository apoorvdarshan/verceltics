package com.apoorvdarshan.verceltics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import com.apoorvdarshan.verceltics.ui.sample.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apoorvdarshan.verceltics.billing.ProAccessViewModel
import com.apoorvdarshan.verceltics.billing.TipJarViewModel
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelticsApp
import com.apoorvdarshan.verceltics.ui.hosting.requiresSecureWindow
import com.apoorvdarshan.verceltics.ui.registrar.RegistrarViewModel
import com.apoorvdarshan.verceltics.ui.registrar.SampleRegistrarUiGateway
import com.apoorvdarshan.verceltics.ui.registrar.requiresSecureWindow as registrarRequiresSecureWindow
import com.apoorvdarshan.verceltics.ui.sites.SampleSiteServicesUiGateway
import com.apoorvdarshan.verceltics.ui.sites.SiteServicesViewModel
import com.apoorvdarshan.verceltics.ui.sites.requiresSecureWindow as siteServicesRequiresSecureWindow
import com.apoorvdarshan.verceltics.ui.hosting.HostingProvidersViewModel
import com.apoorvdarshan.verceltics.ui.hosting.SampleHostingProviderUiGateway
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareViewModel
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyViewModel
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedViewModel
import com.apoorvdarshan.verceltics.ui.screens.about.AboutDestination
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenAction
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenController
import com.apoorvdarshan.verceltics.ui.screens.about.PlayInAppReviewLauncher
import com.apoorvdarshan.verceltics.ui.screens.about.ReviewPrompter
import com.apoorvdarshan.verceltics.ui.screens.about.SharedPreferencesReviewPromptStore
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleViewModel
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vercelGateway
        get() = (application as VercelticsApplication).vercelGateway
    private val vercelConnectionViewModel by viewModels<VercelConnectionViewModel> {
        VercelConnectionViewModel.Factory(vercelGateway)
    }
    private val pageSpeedGateway
        get() = (application as VercelticsApplication).pageSpeedGateway
    private val pageSpeedViewModel by viewModels<PageSpeedViewModel> {
        PageSpeedViewModel.Factory(pageSpeedGateway)
    }
    private val netlifyGateway
        get() = (application as VercelticsApplication).netlifyGateway
    private val netlifyViewModel by viewModels<NetlifyViewModel> {
        NetlifyViewModel.Factory(netlifyGateway)
    }
    private val cloudflareGateway
        get() = (application as VercelticsApplication).cloudflareGateway
    private val cloudflareViewModel by viewModels<CloudflareViewModel> {
        CloudflareViewModel.Factory(cloudflareGateway)
    }
    private val searchConsoleGateway
        get() = (application as VercelticsApplication).searchConsoleGateway
    private val searchConsoleViewModel by viewModels<SearchConsoleViewModel> {
        SearchConsoleViewModel.Factory(searchConsoleGateway)
    }
    private val hostingViewModel by viewModels<HostingProvidersViewModel> {
        HostingProvidersViewModel.Factory((application as VercelticsApplication).hostingGateway)
    }
    private val sampleHostingViewModel by lazy {
        ViewModelProvider(this, HostingProvidersViewModel.Factory(SampleHostingProviderUiGateway))["sample.hosting", HostingProvidersViewModel::class.java]
    }
    private val registrarViewModel by viewModels<RegistrarViewModel> {
        RegistrarViewModel.Factory((application as VercelticsApplication).registrarGateway)
    }
    private val sampleRegistrarViewModel by lazy {
        ViewModelProvider(this, RegistrarViewModel.Factory(SampleRegistrarUiGateway))["sample.registrar", RegistrarViewModel::class.java]
    }
    private val siteServicesViewModel by viewModels<SiteServicesViewModel> {
        SiteServicesViewModel.Factory((application as VercelticsApplication).siteServicesGateway)
    }
    private val sampleSiteServicesViewModel by lazy {
        ViewModelProvider(this, SiteServicesViewModel.Factory(SampleSiteServicesUiGateway))["sample.siteServices", SiteServicesViewModel::class.java]
    }
    private val billingGateway
        get() = (application as VercelticsApplication).billingGateway
    private val proAccessViewModel by viewModels<ProAccessViewModel> {
        ProAccessViewModel.Factory(billingGateway)
    }
    private val tipJarViewModel by viewModels<TipJarViewModel> {
        TipJarViewModel.Factory(billingGateway)
    }
    private val sampleVercelViewModel by lazy {
        ViewModelProvider(this, VercelConnectionViewModel.Factory(SampleVercelGateway))["sample.vercel", VercelConnectionViewModel::class.java]
    }
    private val sampleCloudflareViewModel by lazy {
        ViewModelProvider(this, CloudflareViewModel.Factory(SampleCloudflareGateway))["sample.cloudflare", CloudflareViewModel::class.java]
    }
    private val sampleSearchConsoleViewModel by lazy {
        ViewModelProvider(this, SearchConsoleViewModel.Factory(SampleSearchConsoleGateway))["sample.searchConsole", SearchConsoleViewModel::class.java]
    }
    private val samplePageSpeedViewModel by lazy {
        ViewModelProvider(this, PageSpeedViewModel.Factory(SamplePageSpeedGateway))["sample.pageSpeed", PageSpeedViewModel::class.java]
    }
    private val sampleNetlifyViewModel by lazy {
        ViewModelProvider(this, NetlifyViewModel.Factory(SampleNetlifyGateway))["sample.netlify", NetlifyViewModel::class.java]
    }
    private var ownsProviderSecureFlag = false
    private val app: VercelticsApplication
        get() = application as VercelticsApplication
    private val aboutController: AboutScreenController
        get() = app.aboutController

    private val reviewPrompter by lazy(LazyThreadSafetyMode.NONE) {
        ReviewPrompter(
            store = SharedPreferencesReviewPromptStore(this),
            launcher = PlayInAppReviewLauncher(this),
            scope = lifecycleScope,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeProviderCredentialProtection()
        setContent {
            val aboutState = aboutController.state
            val aboutScope = rememberCoroutineScope()
            val samplePreferences = getSharedPreferences("verceltics.sample", MODE_PRIVATE)
            var showSampleData by rememberSaveable {
                mutableStateOf(samplePreferences.getBoolean("enabled", false))
            }
            VercelticsTheme(appearance = aboutState.appearance) {
                key(showSampleData) {
                    VercelticsApp(
                        vercelConnectionViewModel = if (showSampleData) sampleVercelViewModel else vercelConnectionViewModel,
                        pageSpeedViewModel = if (showSampleData) samplePageSpeedViewModel else pageSpeedViewModel,
                        netlifyViewModel = if (showSampleData) sampleNetlifyViewModel else netlifyViewModel,
                        cloudflareViewModel = if (showSampleData) sampleCloudflareViewModel else cloudflareViewModel,
                        searchConsoleViewModel = if (showSampleData) sampleSearchConsoleViewModel else searchConsoleViewModel,
                        aboutState = aboutState,
                        onAboutAction = { dispatchAboutAction(it, aboutScope) },
                        proAccessViewModel = proAccessViewModel,
                        tipJarViewModel = tipJarViewModel,
                        onOpenExternalUri = ::openAboutUri,
                        hostingViewModel = if (showSampleData) sampleHostingViewModel else hostingViewModel,
                        registrarViewModel = if (showSampleData) sampleRegistrarViewModel else registrarViewModel,
                        siteServicesViewModel = if (showSampleData) sampleSiteServicesViewModel else siteServicesViewModel,
                        onRequestGoogleSignIn = ::signInToGoogleForFirebase,
                        firstLaunchExperience = app.firstLaunchExperience,
                        onProjectsFirstLoaded = {
                            // Sample projects are fictional; only real accounts earn the prompt.
                            if (!showSampleData) reviewPrompter.onProjectsFirstLoaded(this@MainActivity)
                        },
                        isSampleData = showSampleData,
                        onToggleSampleData = {
                            if (!showSampleData) {
                                sampleVercelViewModel.restore()
                                sampleCloudflareViewModel.restore()
                                sampleSearchConsoleViewModel.restore()
                                samplePageSpeedViewModel.restore()
                                sampleHostingViewModel.restore()
                                sampleRegistrarViewModel.restore()
                                sampleSiteServicesViewModel.restore()
                            }
                            showSampleData = !showSampleData
                            samplePreferences.edit().putBoolean("enabled", showSampleData).apply()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Play requires resuming an immediate update the user started before leaving.
            app.playUpdateChecker.resumeInterruptedUpdate(this@MainActivity)
            // Like iOS MainTabView's launch check: automatic and throttled to once an hour.
            aboutController.checkForUpdates(force = false)
        }
    }

    private fun observeProviderCredentialProtection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    netlifyViewModel.uiState.map { it.requiresSecureWindow },
                    cloudflareViewModel.uiState.map { it.requiresSecureWindow },
                    searchConsoleViewModel.uiState.map { it.requiresSecureWindow },
                    hostingViewModel.uiState.map { it.requiresSecureWindow },
                    registrarViewModel.uiState.map { it.registrarRequiresSecureWindow },
                    siteServicesViewModel.uiState.map { it.siteServicesRequiresSecureWindow },
                ) { required -> required.any { it } }
                    .distinctUntilChanged()
                    .collect(::setProviderCredentialProtection)
            }
        }
    }

    private fun setProviderCredentialProtection(required: Boolean) {
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        if (required) {
            if (window.attributes.flags and secureFlag == 0) {
                window.addFlags(secureFlag)
                ownsProviderSecureFlag = true
            }
        } else if (ownsProviderSecureFlag) {
            window.clearFlags(secureFlag)
            ownsProviderSecureFlag = false
        }
    }

    /** Firebase Hosting asks for Google sign-in; the browser flow returns via GoogleOAuthCallbackActivity. */
    private fun signInToGoogleForFirebase(scopes: Set<String>) {
        val session = (application as VercelticsApplication).firebaseGoogleSession
        lifecycleScope.launch {
            runCatching { session.signIn(scopes) }
            hostingViewModel.onGoogleSignInCompleted()
        }
    }

    private fun dispatchAboutAction(action: AboutScreenAction, scope: CoroutineScope) {
        when (action) {
            is AboutScreenAction.SelectAppearance -> aboutController.selectAppearance(action.appearance)
            AboutScreenAction.CheckForUpdates -> scope.launch { aboutController.checkForUpdates() }
            is AboutScreenAction.OpenDestination -> openAboutUri(action.destination.uri)
            is AboutScreenAction.OpenExternalUri -> openAboutUri(action.uri)
            AboutScreenAction.ShareApp -> shareApp()
            is AboutScreenAction.InstallUpdate -> lifecycleScope.launch {
                if (!app.playUpdateChecker.startUpdate(this@MainActivity)) openAboutUri(action.fallbackUri)
            }
            AboutScreenAction.RateApp -> reviewPrompter.requestReviewNow(this) {
                openAboutUri(AboutDestination.RATE_APP.uri)
            }
        }
    }

    private fun openAboutUri(uri: String) {
        val parsedUri = Uri.parse(uri)
        if (parsedUri.scheme !in SUPPORTED_ABOUT_URI_SCHEMES) return
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, parsedUri)) }.isSuccess
        // Devices without the Play Store app cannot open market:// links; use the web listing.
        if (!opened && parsedUri.scheme == "market") {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutDestination.PLAY_STORE_LISTING.uri)))
            }
        }
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.about_share_message))
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.about_share)))
        }
    }

    private companion object {
        val SUPPORTED_ABOUT_URI_SCHEMES = setOf("https", "mailto", "market")
    }
}
