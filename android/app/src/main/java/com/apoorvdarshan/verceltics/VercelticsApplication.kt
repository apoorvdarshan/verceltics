package com.apoorvdarshan.verceltics

import android.app.Application
import com.apoorvdarshan.verceltics.billing.BillingGateway
import com.apoorvdarshan.verceltics.billing.RevenueCatBillingGateway
import com.apoorvdarshan.verceltics.ui.NativeVercelUiGateway
import com.apoorvdarshan.verceltics.ui.cloudflare.NativeCloudflareUiGateway
import com.apoorvdarshan.verceltics.ui.netlify.NativeNetlifyUiGateway
import com.apoorvdarshan.verceltics.ui.pagespeed.NativePageSpeedUiGateway
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

    /** RevenueCat billing, or an unavailable gateway when this build has no RevenueCat key. */
    val billingGateway: BillingGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RevenueCatBillingGateway.create(
            application = this,
            apiKey = BuildConfig.REVENUECAT_API_KEY,
            debugLogs = BuildConfig.DEBUG,
        )
    }
}
