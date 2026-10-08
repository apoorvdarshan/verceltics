package com.apoorvdarshan.verceltics

import android.app.Application
import com.apoorvdarshan.verceltics.billing.BillingGateway
import com.apoorvdarshan.verceltics.billing.RevenueCatBillingGateway
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthSession
import com.apoorvdarshan.verceltics.data.hosting.FirebaseGoogleSlots
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.ui.hosting.NativeHostingProviderUiGateway
import com.apoorvdarshan.verceltics.ui.registrar.NativeRegistrarUiGateway
import com.apoorvdarshan.verceltics.ui.sites.NativeSiteServicesUiGateway
import com.apoorvdarshan.verceltics.ui.NativeVercelUiGateway
import com.apoorvdarshan.verceltics.ui.cloudflare.NativeCloudflareUiGateway
import com.apoorvdarshan.verceltics.ui.netlify.NativeNetlifyUiGateway
import com.apoorvdarshan.verceltics.ui.onboarding.FirstLaunchExperienceStore
import com.apoorvdarshan.verceltics.ui.onboarding.SharedPreferencesFirstLaunchPreferences
import com.apoorvdarshan.verceltics.ui.pagespeed.NativePageSpeedUiGateway
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenController
import com.apoorvdarshan.verceltics.ui.screens.about.PlayInAppUpdateChecker
import com.apoorvdarshan.verceltics.ui.screens.about.SharedPreferencesAppearancePreferenceStore
import com.apoorvdarshan.verceltics.ui.screens.about.currentAndroidAppVersion
import com.apoorvdarshan.verceltics.ui.searchconsole.NativeSearchConsoleUiGateway

class VercelticsApplication : Application() {
    val vercelGateway: NativeVercelUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeVercelUiGateway.create(this)
    }

    val pageSpeedGateway: NativePageSpeedUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativePageSpeedUiGateway.create(this)
    }

    val netlifyGateway: NativeNetlifyUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeNetlifyUiGateway.create(this)
    }

    val cloudflareGateway: NativeCloudflareUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeCloudflareUiGateway.create(this)
    }

    val searchConsoleGateway: NativeSearchConsoleUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeSearchConsoleUiGateway.create(this)
    }

    /**
     * Google sign-in used by Firebase Hosting, kept separate from the GA4 credential. It signs in to
     * a staging slot; each saved Firebase account then keeps its own slot (see FirebaseGoogleSlots).
     */
    val firebaseGoogleSession: GoogleOAuthSession by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoogleOAuthSession.create(this, slot = FirebaseGoogleSlots.SIGN_IN)
    }

    val hostingGateway: NativeHostingProviderUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeHostingProviderUiGateway.create(
            this,
            googleAccessTokenSource = GoogleAccessTokenSource { scopes -> firebaseGoogleSession.accessToken(scopes) },
        )
    }

    val siteServicesGateway: NativeSiteServicesUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeSiteServicesUiGateway.create(this)
    }

    val registrarGateway: NativeRegistrarUiGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeRegistrarUiGateway.create(this)
    }

    /** Google Play In-App Updates; unconfigured (never calls Play) for sideloaded builds. */
    val playUpdateChecker: PlayInAppUpdateChecker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlayInAppUpdateChecker(this)
    }

    /**
     * Process-wide About state so the update result, its hourly throttle, and the navigation
     * badge survive activity recreation.
     */
    val aboutController: AboutScreenController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AboutScreenController(
            appearanceStore = SharedPreferencesAppearancePreferenceStore(this),
            updateChecker = playUpdateChecker,
            version = currentAndroidAppVersion(),
        )
    }

    /** One-time first-launch welcome completion, shared by every activity instance. */
    val firstLaunchExperience: FirstLaunchExperienceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FirstLaunchExperienceStore(SharedPreferencesFirstLaunchPreferences(this))
    }

    /** RevenueCat billing, or an unavailable gateway when this build has no RevenueCat key. */
    val billingGateway: BillingGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RevenueCatBillingGateway.create(
            application = this,
            apiKey = BuildConfig.REVENUECAT_API_KEY,
            debugLogs = BuildConfig.DEBUG,
        )
    }
}
