package com.apoorvdarshan.verceltics.data.googleoauth

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.runOnProviderExecutor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence boundary for one Google credential slot. */
interface GoogleOAuthCredentialStore {
    fun load(): GoogleOAuthCredential?

    fun save(credential: GoogleOAuthCredential)

    fun delete()
}

/**
 * AES-GCM encrypted credential slot in `noBackupFilesDir`, using the shared account envelope.
 * The slot name is bound into the authenticated data so records cannot be swapped between slots.
 */
class EncryptedGoogleOAuthCredentialStore(
    private val slot: String,
    private val store: AtomicBytesStore,
    private val cipher: AccountCipher,
) : GoogleOAuthCredentialStore {
    init {
        require(SLOT.matches(slot)) { "Invalid Google credential slot." }
    }

    @Synchronized
    override fun load(): GoogleOAuthCredential? {
        val envelope = store.read() ?: return null
        val associatedData = associatedData()
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            GoogleOAuthCredentialCodec.decode(plaintext, slot)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    @Synchronized
    override fun save(credential: GoogleOAuthCredential) {
        val plaintext = GoogleOAuthCredentialCodec.encode(credential, slot)
        val associatedData = associatedData()
        var envelope: ByteArray? = null
        try {
            envelope = AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
            store.write(envelope)
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
            envelope?.fill(0)
        }
    }

    @Synchronized
    override fun delete() = store.delete()

    private fun associatedData(): ByteArray =
        "$ASSOCIATED_DATA_PREFIX$slot".toByteArray(StandardCharsets.UTF_8)

    companion object {
        internal const val ASSOCIATED_DATA_PREFIX = "verceltics.account-envelope.v1:google-oauth:"
        internal const val KEY_ALIAS = "verceltics.account-storage.google-oauth.v1"
        private val SLOT = Regex("[a-z0-9][a-z0-9.-]{0,63}")

        fun create(context: Context, slot: String): EncryptedGoogleOAuthCredentialStore =
            EncryptedGoogleOAuthCredentialStore(
                slot = slot,
                store = NoBackupAtomicFileStore(context, "accounts/google-oauth/$slot.credential"),
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
    }
}

internal object GoogleOAuthCredentialCodec {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 4_096
    private const val MAX_TOKEN_BYTES = 16_384

    fun encode(credential: GoogleOAuthCredential, slot: String): ByteArray {
        val bytes = WipingOutput()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            writeText(output, slot)
            writeSecret(output, credential.accessToken)
            output.writeBoolean(credential.refreshToken != null)
            credential.refreshToken?.let { writeSecret(output, it) }
            writeText(output, credential.tokenType)
            output.writeInt(credential.scopes.size)
            credential.scopes.forEach { writeText(output, it) }
            output.writeLong(credential.expiresAtMillis)
            writeNullableText(output, credential.subject)
            writeNullableText(output, credential.email)
            output.flush()
            return bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray, expectedSlot: String): GoogleOAuthCredential =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported Google credential version." }
            require(readText(input) == expectedSlot) { "The Google credential belongs to another slot." }
            val accessToken = readSecret(input)
            val refreshToken = if (input.readBoolean()) readSecret(input) else null
            val tokenType = readText(input)
            val scopeCount = input.readInt()
            require(scopeCount in 0..GoogleOAuthScopes.MAX_SCOPES) { "Invalid Google scope count." }
            val scopes = List(scopeCount) { readText(input) }
            val expiresAt = input.readLong()
            val subject = readNullableText(input)
            val email = readNullableText(input)
            require(input.available() == 0) { "Unexpected trailing Google credential data." }
            GoogleOAuthCredential(accessToken, refreshToken, tokenType, scopes, expiresAt, subject, email)
        }

    private fun writeSecret(output: DataOutputStream, secret: SecretValue) {
        val bytes = secret.utf8Bytes()
        try {
            require(bytes.size <= MAX_TOKEN_BYTES) { "The Google token is too large." }
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readSecret(input: DataInputStream): SecretValue {
        val length = input.readInt()
        require(length in 1..MAX_TOKEN_BYTES && length <= input.available()) { "Invalid Google token length." }
        val bytes = ByteArray(length).also(input::readFully)
        return try {
            SecretValue.of(String(bytes, StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "A Google credential field is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_TEXT_BYTES && length <= input.available()) { "Invalid Google credential field." }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    private fun writeNullableText(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        value?.let { writeText(output, it) }
    }

    private fun readNullableText(input: DataInputStream): String? = if (input.readBoolean()) readText(input) else null

    private class WipingOutput : ByteArrayOutputStream() {
        override fun close() {
            buf.fill(0)
            reset()
            super.close()
        }
    }
}

/**
 * Reusable Google account session for one consumer slot (for example `site.google-analytics` or
 * `hosting.firebase`). It runs the PKCE browser flow for any scope set, persists the credential
 * encrypted, refreshes it transparently, and backs the shell's `GoogleAccessTokenSource`:
 *
 * ```
 * val tokens = GoogleAccessTokenSource(GoogleOAuthSession.create(context, "hosting.firebase")::accessToken)
 * ```
 *
 * Refreshes and saves are serialized per slot. The browser flow is never run while holding the
 * lock, so token reads stay responsive during sign-in.
 */
class GoogleOAuthSession(
    val slot: String,
    private val store: GoogleOAuthCredentialStore,
    private val authorizer: GoogleOAuthAuthorizer,
    private val storageExecutor: Executor,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** False in builds without `VERCELTICS_GOOGLE_OAUTH_CLIENT_ID`; show a configuration-needed state. */
    val isConfigured: Boolean get() = authorizer.configuration != null

    /** Runs browser sign-in for [scopes] and returns the credential without saving it. */
    suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential = authorizer.authorize(scopes)

    /** Runs browser sign-in for [scopes] and saves the credential in this slot. */
    suspend fun signIn(scopes: Set<String>): GoogleAccountIdentity {
        val credential = authorize(scopes)
        save(credential)
        return credential.identity ?: throw GoogleOAuthException("Google returned an invalid token response.")
    }

    /** Replaces this slot's credential and returns the previous one (for compensation). */
    suspend fun save(credential: GoogleOAuthCredential): GoogleOAuthCredential? = mutex.withLock {
        runOnProviderExecutor(storageExecutor) {
            val previous = runCatching { store.load() }.getOrNull()
            store.save(credential)
            previous
        }
    }

    /** Restores a credential captured by [save], or clears the slot when it was empty. */
    suspend fun restore(previous: GoogleOAuthCredential?) = mutex.withLock {
        runOnProviderExecutor(storageExecutor) {
            if (previous == null) store.delete() else store.save(previous)
        }
    }

    suspend fun identity(): GoogleAccountIdentity? = mutex.withLock {
        runOnProviderExecutor(storageExecutor) { store.load()?.identity }
    }

    suspend fun hasCredential(scopes: Set<String>): Boolean = mutex.withLock {
        runOnProviderExecutor(storageExecutor) { store.load()?.covers(scopes) == true }
    }

    /**
     * Returns a currently valid access token for a saved credential that covers [scopes], or null
     * when this slot has no such credential or Google revoked it (the caller should ask the user
     * to sign in again). Network failures during refresh are thrown.
     */
    suspend fun accessToken(scopes: Set<String>): String? = accessTokenSecret(scopes)?.use { it }

    /** [accessToken] without materializing the token as a free-standing string. */
    suspend fun accessTokenSecret(scopes: Set<String>, forceRefresh: Boolean = false): SecretValue? =
        mutex.withLock {
            val saved = runOnProviderExecutor(storageExecutor) { store.load() } ?: return@withLock null
            if (!saved.covers(scopes)) return@withLock null
            if (!forceRefresh && !saved.needsRefresh(nowMillis())) return@withLock saved.accessToken
            val refreshed = try {
                authorizer.refresh(saved)
            } catch (error: GoogleOAuthException) {
                if (error.isRevoked) return@withLock null
                throw error
            }
            runOnProviderExecutor(storageExecutor) { store.save(refreshed) }
            refreshed.accessToken
        }

    /**
     * Like [accessTokenSecret], but returns the whole valid credential (expiry, granted scopes and
     * identity) for consumers whose API layer validates more than the bearer token. Null when this
     * slot has no credential covering [scopes] or Google revoked it; network failures are thrown.
     */
    suspend fun validCredential(scopes: Set<String>, forceRefresh: Boolean = false): GoogleOAuthCredential? =
        mutex.withLock {
            val saved = runOnProviderExecutor(storageExecutor) { store.load() } ?: return@withLock null
            if (!saved.covers(scopes)) return@withLock null
            if (!forceRefresh && !saved.needsRefresh(nowMillis())) return@withLock saved
            val refreshed = try {
                authorizer.refresh(saved)
            } catch (error: GoogleOAuthException) {
                if (error.isRevoked) return@withLock null
                throw error
            }
            runOnProviderExecutor(storageExecutor) { store.save(refreshed) }
            refreshed
        }

    /** True when this slot holds any saved credential (no refresh, no scope check). */
    suspend fun hasSavedCredential(): Boolean = mutex.withLock {
        runOnProviderExecutor(storageExecutor) { runCatching { store.load() }.getOrNull() != null }
    }

    suspend fun signOut() = mutex.withLock {
        runOnProviderExecutor(storageExecutor) { store.delete() }
    }

    override fun toString(): String = "GoogleOAuthSession(slot=$slot)"

    companion object {
        private val sessions = ConcurrentHashMap<String, GoogleOAuthSession>()
        private val sharedStorageExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "verceltics-google-oauth-storage").apply { isDaemon = true }
            }
        }
        private val sharedAuthorizers = ConcurrentHashMap<String, GoogleOAuthAuthorizer>()

        /** Process-wide session per slot, so refreshes for one slot are always serialized. */
        fun create(context: Context, slot: String): GoogleOAuthSession {
            val applicationContext = context.applicationContext
            return sessions.getOrPut(slot) {
                GoogleOAuthSession(
                    slot = slot,
                    store = EncryptedGoogleOAuthCredentialStore.create(applicationContext, slot),
                    authorizer = sharedAuthorizers.getOrPut("native") {
                        NativeGoogleOAuthAuthorizer(AndroidGoogleOAuthBrowser(applicationContext))
                    },
                    storageExecutor = sharedStorageExecutor,
                )
            }
        }
    }
}
