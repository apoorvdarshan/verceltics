package com.apoorvdarshan.verceltics.data.hosting

import android.content.Context
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredential
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google OAuth slot names for Firebase Hosting accounts ([GoogleOAuthSession] slots).
 *
 * Every saved Firebase account owns one slot derived from its account id. The account that existed
 * before multi-account support keeps the original `hosting.firebase` slot, so an upgraded install
 * keeps its Google sign-in without copying a credential. "Continue with Google" always signs in to
 * [SIGN_IN] first; a successful connection then adopts that credential into the account's slot, so
 * adding a second account can never overwrite the first account's Google credential.
 */
object FirebaseGoogleSlots {
    /** Where the app shell stores a fresh Google sign-in until a Firebase connection adopts it. */
    const val SIGN_IN: String = "hosting.firebase.signin"

    /** The single pre-multi-account slot; it stays the primary Firebase account's slot. */
    const val LEGACY: String = "hosting.firebase"

    private val SLOT = Regex("[a-z0-9][a-z0-9.-]{0,63}")

    fun forAccount(accountId: String): String {
        AccountVaultLayout.requireAccountId(accountId)
        return if (accountId == AccountVaultLayout.PRIMARY_ACCOUNT_ID) LEGACY else "$LEGACY.$accountId"
    }

    fun isValid(slot: String): Boolean = SLOT.matches(slot)
}

/** Opaque handle that restores a Firebase account slot replaced by [FirebaseGoogleCredentialSlots.copy]. */
class FirebaseGoogleSlotUndo internal constructor(
    val slot: String,
    internal val previous: GoogleOAuthCredential?,
) {
    override fun toString(): String = "FirebaseGoogleSlotUndo(slot=$slot, <redacted>)"
}

/** Moves a fresh Google sign-in into one Firebase account's slot and clears slots on removal. */
interface FirebaseGoogleCredentialSlots {
    /** Copies the credential in [from] into [to]. Returns an undo handle, or null when [from] is empty. */
    suspend fun copy(from: String, to: String): FirebaseGoogleSlotUndo?

    /** Restores the slot captured by [copy] (clearing it when it was empty). */
    suspend fun undo(undo: FirebaseGoogleSlotUndo)

    suspend fun clear(slot: String)

    companion object {
        /** No Google storage (sample data and tests that do not exercise Firebase). */
        val None: FirebaseGoogleCredentialSlots = object : FirebaseGoogleCredentialSlots {
            override suspend fun copy(from: String, to: String): FirebaseGoogleSlotUndo? = null

            override suspend fun undo(undo: FirebaseGoogleSlotUndo) = Unit

            override suspend fun clear(slot: String) = Unit
        }
    }
}

/** Production slots backed by the encrypted [GoogleOAuthSession] credential stores. */
class AndroidFirebaseGoogleCredentialSlots(context: Context) : FirebaseGoogleCredentialSlots {
    private val context = context.applicationContext

    override suspend fun copy(from: String, to: String): FirebaseGoogleSlotUndo? {
        require(FirebaseGoogleSlots.isValid(from) && FirebaseGoogleSlots.isValid(to)) { "Invalid Google slot." }
        if (from == to) return null
        val credential = withContext(Dispatchers.IO) {
            EncryptedGoogleOAuthCredentialStore.create(context, from).load()
        } ?: return null
        val previous = GoogleOAuthSession.create(context, to).save(credential)
        return FirebaseGoogleSlotUndo(to, previous)
    }

    override suspend fun undo(undo: FirebaseGoogleSlotUndo) =
        GoogleOAuthSession.create(context, undo.slot).restore(undo.previous)

    override suspend fun clear(slot: String) = GoogleOAuthSession.create(context, slot).signOut()

    /** Token source that reads each Firebase account's own slot. */
    fun tokenSource(): GoogleAccessTokenSource = object : GoogleAccessTokenSource {
        override suspend fun accessToken(scopes: Set<String>): String? = accessToken(FirebaseGoogleSlots.SIGN_IN, scopes)

        override suspend fun accessToken(slot: String, scopes: Set<String>): String? {
            require(FirebaseGoogleSlots.isValid(slot)) { "Invalid Google slot." }
            return GoogleOAuthSession.create(context, slot).accessToken(scopes)
        }
    }
}
