package com.apoorvdarshan.verceltics.data.hosting

/**
 * Supplies short-lived Google OAuth access tokens for Firebase Hosting.
 *
 * The app's shared Google OAuth module owns sign-in, refresh tokens and their storage. Hosting
 * only asks for a fresh access token for [scopes] right before each Firebase request and never
 * persists it. Return `null` when the user has not granted these scopes (or must sign in again);
 * the Firebase connect screen then asks the app shell to start Google sign-in.
 */
fun interface GoogleAccessTokenSource {
    suspend fun accessToken(scopes: Set<String>): String?

    /**
     * A token from one Google OAuth [slot] (one per saved Firebase account, see
     * [FirebaseGoogleSlots]). Single-slot sources (tests, previews) ignore the slot.
     */
    suspend fun accessToken(slot: String, scopes: Set<String>): String? = accessToken(scopes)

    companion object {
        /** Exactly the scopes iOS requests (`GoogleOAuthService.firebaseHostingScopes`). */
        val FIREBASE_HOSTING_SCOPES: Set<String> = linkedSetOf(
            "openid",
            "email",
            "https://www.googleapis.com/auth/firebase.hosting",
        )

        /** A source that never has a token, for builds without the shared OAuth module. */
        val Unavailable: GoogleAccessTokenSource = GoogleAccessTokenSource { null }
    }
}
