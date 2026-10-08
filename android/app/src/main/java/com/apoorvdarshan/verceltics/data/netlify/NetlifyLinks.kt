package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.hosting.AwsSigV4Signer

/** iOS `HostingProviderAPI.dashboardURL` / `AccountProvider.credentialPageURL` for Netlify. */
object NetlifyLinks {
    /** Netlify team dashboard (iOS dashboard "Dashboard" action). */
    const val DASHBOARD_URL: String = "https://app.netlify.com/"

    /** Where personal access tokens are created (iOS "Open Netlify credentials"). */
    const val CREDENTIALS_URL: String = "https://app.netlify.com/user/applications#personal-access-tokens"

    /** A site's overview in the Netlify app; the site name is one percent-encoded path segment. */
    fun siteDashboardUrl(siteName: String): String {
        val name = siteName.trim()
        if (name.isEmpty()) return DASHBOARD_URL
        return "https://app.netlify.com/sites/${AwsSigV4Signer.encode(name)}/overview"
    }
}
