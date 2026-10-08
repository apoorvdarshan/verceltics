package com.apoorvdarshan.verceltics.data.apicatalog

import android.content.Context
import com.apoorvdarshan.verceltics.data.hosting.HostingResponseFormatException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where screens get the build's bundled operations from. */
fun interface ProviderApiCatalogSource {
    /** Loads (off the main thread) the bundled catalog for `hosting.<id>` / `registrar.<id>`. */
    suspend fun catalog(catalogId: String): ProviderApiCatalog
}

/**
 * Lazily reads `assets/ProviderAPICatalog.json` on [dispatcher] and keeps the few most recently
 * opened provider catalogs in memory. Nothing is parsed until a Complete API screen asks for it.
 */
class ProviderApiCatalogStore(
    private val openDocument: () -> InputStream,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maximumCachedCatalogs: Int = DEFAULT_CACHED_CATALOGS,
) : ProviderApiCatalogSource {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, ProviderApiCatalog>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProviderApiCatalog>?): Boolean =
            size > maximumCachedCatalogs
    }

    init {
        require(maximumCachedCatalogs in 1..32) { "Invalid catalog cache size." }
    }

    override suspend fun catalog(catalogId: String): ProviderApiCatalog = mutex.withLock {
        cache[catalogId] ?: withContext(dispatcher) { load(catalogId) }.also { cache[catalogId] = it }
    }

    private fun load(catalogId: String): ProviderApiCatalog {
        val document = try {
            decode(openDocument().use(::readBounded))
        } catch (_: IOException) {
            throw ProviderApiCatalogException.missingBundle()
        }
        return try {
            ProviderApiCatalogParser.extract(document, catalogId)
        } catch (_: HostingResponseFormatException) {
            throw ProviderApiCatalogException("The bundled API catalog could not be read.")
        } ?: throw ProviderApiCatalogException.missingProvider(catalogId)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(2 * 1_024 * 1_024)
        val buffer = ByteArray(64 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAXIMUM_DOCUMENT_BYTES) throw ProviderApiCatalogException("The bundled API catalog is too large.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw ProviderApiCatalogException("The bundled API catalog is not UTF-8.")
    }

    companion object {
        const val ASSET_NAME: String = "ProviderAPICatalog.json"
        const val DEFAULT_CACHED_CATALOGS: Int = 4
        private const val MAXIMUM_DOCUMENT_BYTES = 16 * 1_024 * 1_024

        @Volatile
        private var shared: ProviderApiCatalogStore? = null

        /** One process-wide store backed by the APK's assets. */
        fun shared(context: Context): ProviderApiCatalogStore = shared ?: synchronized(this) {
            shared ?: run {
                val assets = context.applicationContext.assets
                ProviderApiCatalogStore(openDocument = { assets.open(ASSET_NAME) }).also { shared = it }
            }
        }
    }
}
