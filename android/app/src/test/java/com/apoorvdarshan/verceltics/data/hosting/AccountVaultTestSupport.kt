package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore

/**
 * In-memory "noBackupFilesDir": each primary path is backed by the given store (so tests can keep
 * inspecting the pre-multi-account record file); every other path gets its own [SimpleMemoryStore].
 */
internal class MemoryFiles(
    private val primaries: Map<String, AtomicBytesStore> = emptyMap(),
) : (String) -> AtomicBytesStore {
    val extra = linkedMapOf<String, SimpleMemoryStore>()

    @Synchronized
    override fun invoke(path: String): AtomicBytesStore = primaries[path] ?: extra.getOrPut(path) { SimpleMemoryStore() }

    /** Bytes currently stored at [path], whichever store backs it. */
    fun bytes(path: String): ByteArray? = (primaries[path] ?: extra[path])?.read()

    /** Paths that currently hold bytes. */
    fun occupiedPaths(): Set<String> =
        (primaries.filterValues { it.read() != null }.keys + extra.filterValues { it.bytes != null }.keys).toSet()
}

/** [MemoryFiles] where [primary] backs the legacy single-account [primaryPath]. */
internal fun primaryFiles(primaryPath: String, primary: AtomicBytesStore): MemoryFiles =
    MemoryFiles(mapOf(primaryPath to primary))

internal class SimpleMemoryStore : AtomicBytesStore {
    @Volatile
    var bytes: ByteArray? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }

    override fun delete() {
        bytes = null
    }
}
