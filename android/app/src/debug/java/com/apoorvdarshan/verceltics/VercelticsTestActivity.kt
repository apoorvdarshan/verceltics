package com.apoorvdarshan.verceltics

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apoorvdarshan.verceltics.ui.DebugVercelGatewayController
import com.apoorvdarshan.verceltics.ui.DebugVercelScenario
import com.apoorvdarshan.verceltics.ui.DebugVercelUiGateway
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelticsApp
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareGatewayController
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareScenario
import com.apoorvdarshan.verceltics.ui.cloudflare.DebugCloudflareUiGateway
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyGatewayController
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyScenario
import com.apoorvdarshan.verceltics.ui.netlify.DebugNetlifyUiGateway
import com.apoorvdarshan.verceltics.ui.netlify.NetlifyViewModel
import com.apoorvdarshan.verceltics.ui.onboarding.FirstLaunchExperienceStore
import com.apoorvdarshan.verceltics.ui.onboarding.FirstLaunchPreferences
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedGatewayController
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedScenario
import com.apoorvdarshan.verceltics.ui.pagespeed.DebugPageSpeedUiGateway
import com.apoorvdarshan.verceltics.ui.pagespeed.PageSpeedViewModel
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleGatewayController
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleScenario
import com.apoorvdarshan.verceltics.ui.searchconsole.DebugSearchConsoleUiGateway
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsoleViewModel
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Isolated debug-only host used by instrumented UI tests. */
class VercelticsTestActivity : ComponentActivity() {
    private val vercelConnectionViewModel by viewModels<VercelConnectionViewModel> {
        VercelConnectionViewModel.Factory(DebugVercelUiGateway())
    }
    private val pageSpeedViewModel by viewModels<PageSpeedViewModel> {
        PageSpeedViewModel.Factory(DebugPageSpeedUiGateway())
    }
    private val netlifyViewModel by viewModels<NetlifyViewModel> {
        NetlifyViewModel.Factory(DebugNetlifyUiGateway())
    }
    private val cloudflareViewModel by viewModels<CloudflareViewModel> {
        CloudflareViewModel.Factory(DebugCloudflareUiGateway())
    }
    private val searchConsoleViewModel by viewModels<SearchConsoleViewModel> {
        SearchConsoleViewModel.Factory(DebugSearchConsoleUiGateway())
    }
    private val firstLaunchHolder by viewModels<FirstLaunchHolder>()
    private var ownsProviderSecureFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.getStringExtra(EXTRA_VERCEL_SCENARIO)
            ?.let { runCatching { DebugVercelScenario.valueOf(it) }.getOrNull() }
            ?.let { DebugVercelGatewayController.configure(it) }
        intent.getStringExtra(EXTRA_NETLIFY_SCENARIO)
            ?.let { runCatching { DebugNetlifyScenario.valueOf(it) }.getOrNull() }
            ?.let { DebugNetlifyGatewayController.configure(it) }
        intent.getStringExtra(EXTRA_PAGE_SPEED_SCENARIO)
            ?.let { runCatching { DebugPageSpeedScenario.valueOf(it) }.getOrNull() }
            ?.let { DebugPageSpeedGatewayController.configure(it) }
        intent.getStringExtra(EXTRA_CLOUDFLARE_SCENARIO)
            ?.let { runCatching { DebugCloudflareScenario.valueOf(it) }.getOrNull() }
            ?.let { DebugCloudflareGatewayController.configure(it) }
        intent.getStringExtra(EXTRA_SEARCH_CONSOLE_SCENARIO)
            ?.let { runCatching { DebugSearchConsoleScenario.valueOf(it) }.getOrNull() }
            ?.let { DebugSearchConsoleGatewayController.configure(it) }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    netlifyViewModel.uiState.map { it.requiresSecureWindow },
                    cloudflareViewModel.uiState.map { it.requiresSecureWindow },
                    searchConsoleViewModel.uiState.map { it.requiresSecureWindow },
                ) { netlifyRequired, cloudflareRequired, searchConsoleRequired ->
                    netlifyRequired || cloudflareRequired || searchConsoleRequired
                }
                    .distinctUntilChanged()
                    .collect { required ->
                        val flag = WindowManager.LayoutParams.FLAG_SECURE
                        if (required && window.attributes.flags and flag == 0) {
                            window.addFlags(flag)
                            ownsProviderSecureFlag = true
                        } else if (!required && ownsProviderSecureFlag) {
                            window.clearFlags(flag)
                            ownsProviderSecureFlag = false
                        }
                    }
            }
        }
        // Shell tests reach the tab shell directly; first-launch tests opt in with an extra.
        if (intent.getBooleanExtra(EXTRA_FIRST_LAUNCH, false) && firstLaunchHolder.store == null) {
            firstLaunchHolder.store = FirstLaunchExperienceStore(
                InMemoryFirstLaunchPreferences(
                    completed = intent.getBooleanExtra(EXTRA_FIRST_LAUNCH_COMPLETED, false),
                ),
            )
        }
        setContent {
            VercelticsTheme {
                VercelticsApp(
                    vercelConnectionViewModel = vercelConnectionViewModel,
                    pageSpeedViewModel = pageSpeedViewModel,
                    netlifyViewModel = netlifyViewModel,
                    cloudflareViewModel = cloudflareViewModel,
                    searchConsoleViewModel = searchConsoleViewModel,
                    firstLaunchExperience = firstLaunchHolder.store,
                )
            }
        }
    }

    /** First-launch completion for this test activity, kept across recreation. */
    val firstLaunchExperience: FirstLaunchExperienceStore?
        get() = firstLaunchHolder.store

    fun configureGateway(
        scenario: DebugVercelScenario,
        blockConnect: Boolean = false,
    ) {
        DebugVercelGatewayController.configure(
            scenario = scenario,
            blockConnect = blockConnect,
        )
        vercelConnectionViewModel.restore()
    }

    fun releaseConnect() {
        DebugVercelGatewayController.releaseConnect()
    }

    fun configurePageSpeedGateway(scenario: DebugPageSpeedScenario) {
        DebugPageSpeedGatewayController.configure(scenario)
        pageSpeedViewModel.restore()
    }

    fun configureNetlifyGateway(
        scenario: DebugNetlifyScenario,
        blockConnect: Boolean = false,
    ) {
        DebugNetlifyGatewayController.configure(scenario, blockConnect)
        netlifyViewModel.restore()
    }

    fun releaseNetlifyConnect() {
        DebugNetlifyGatewayController.releaseConnect()
    }

    fun configureCloudflareGateway(
        scenario: DebugCloudflareScenario,
        blockConnect: Boolean = false,
    ) {
        DebugCloudflareGatewayController.configure(scenario, blockConnect)
        cloudflareViewModel.restore()
    }

    fun releaseCloudflareConnect() {
        DebugCloudflareGatewayController.releaseConnect()
    }

    fun configureSearchConsoleGateway(
        scenario: DebugSearchConsoleScenario,
        blockConnect: Boolean = false,
    ) {
        DebugSearchConsoleGatewayController.configure(scenario, blockConnect)
        searchConsoleViewModel.restore()
    }

    fun releaseSearchConsoleConnect() {
        DebugSearchConsoleGatewayController.releaseConnect()
    }

    class FirstLaunchHolder : ViewModel() {
        var store: FirstLaunchExperienceStore? = null
    }

    private class InMemoryFirstLaunchPreferences(private var completed: Boolean) : FirstLaunchPreferences {
        override fun isWelcomeCompleted(): Boolean = completed

        override fun markWelcomeCompleted() {
            completed = true
        }
    }

    companion object {
        /** Enables the first-launch gate with in-memory completion state. */
        const val EXTRA_FIRST_LAUNCH = "firstLaunch"

        /** With [EXTRA_FIRST_LAUNCH], starts as if the welcome was already completed. */
        const val EXTRA_FIRST_LAUNCH_COMPLETED = "firstLaunchCompleted"
        const val EXTRA_VERCEL_SCENARIO = "vercelScenario"
        const val EXTRA_NETLIFY_SCENARIO = "netlifyScenario"
        const val EXTRA_PAGE_SPEED_SCENARIO = "pageSpeedScenario"
        const val EXTRA_CLOUDFLARE_SCENARIO = "cloudflareScenario"
        const val EXTRA_SEARCH_CONSOLE_SCENARIO = "searchConsoleScenario"
    }
}
