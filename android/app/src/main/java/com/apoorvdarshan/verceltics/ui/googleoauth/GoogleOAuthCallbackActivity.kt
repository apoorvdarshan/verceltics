package com.apoorvdarshan.verceltics.ui.googleoauth

import android.app.Activity
import android.os.Bundle
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCallbackBroker
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthClientConfiguration
import com.apoorvdarshan.verceltics.data.googleoauth.matchesGoogleOAuthRedirect
import java.net.URI

/**
 * Receives only the one-use `<reverse-client-id>:/oauth2redirect` Google redirect and hands it
 * to the pending PKCE coroutine in [GoogleOAuthCallbackBroker]. It renders nothing and finishes
 * immediately; the browser returns to the app task that started sign-in.
 */
class GoogleOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expected = GoogleOAuthClientConfiguration.current()?.redirectUri?.let(::URI)
        intent?.data?.toString()?.let { raw ->
            runCatching { URI(raw) }.getOrNull()
                ?.takeIf { callback -> expected != null && callback.matchesGoogleOAuthRedirect(expected) }
                ?.let(GoogleOAuthCallbackBroker::deliver)
        }
        finish()
    }
}
