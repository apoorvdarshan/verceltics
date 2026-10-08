package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** iOS `CloudflarePagesDirectUploadOptions`: blank values are dropped. */
class CloudflarePagesDirectUploadOptions(branch: String? = null, commitMessage: String? = null) {
    val branch: String? = branch?.trim()?.takeIf(String::isNotEmpty)
    val commitMessage: String? = commitMessage?.trim()?.takeIf(String::isNotEmpty)

    override fun equals(other: Any?): Boolean =
        other is CloudflarePagesDirectUploadOptions && other.branch == branch && other.commitMessage == commitMessage

    override fun hashCode(): Int = (branch?.hashCode() ?: 0) * 31 + (commitMessage?.hashCode() ?: 0)

    override fun toString(): String = "CloudflarePagesDirectUploadOptions(branch=$branch, commitMessage=$commitMessage)"
}

/** iOS `CloudflarePagesDirectUploadResult`. */
data class CloudflarePagesDirectUploadResult(
    val deployment: CloudflarePagesDeployment,
    val assetCount: Int,
    val uploadedAssetCount: Int,
    val reusedAssetCount: Int,
)

/** iOS `CloudflarePagesDirectUploadProgress` with the same copy. */
data class CloudflarePagesDirectUploadProgress(
    val stage: Stage,
    val completed: Int,
    val total: Int,
) {
    enum class Stage { AUTHORIZING, HASHING, CHECKING, UPLOADING, DEPLOYING }

    val fractionCompleted: Double?
        get() = if (total > 0) (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else null

    val message: String
        get() = when (stage) {
            Stage.AUTHORIZING -> "Authorizing the Pages upload…"
            Stage.HASHING -> if (total > 0) "Preparing files · $completed of $total" else "Reading the build folder…"
            Stage.CHECKING -> "Checking which assets Cloudflare already has…"
            Stage.UPLOADING -> "Uploading assets · $completed of $total"
            Stage.DEPLOYING -> "Creating the Pages deployment…"
        }
}

/** iOS `CloudflarePagesDirectUploadPreparation`: the documented multipart contract shown in the UI. */
object CloudflarePagesDirectUploadContract {
    const val CONTENT_TYPE: String = "multipart/form-data"
    val requiredParts: List<String> = listOf("manifest", "one file part for each manifest hash")
    val optionalParts: List<String> = listOf(
        "branch", "commit_hash", "commit_message", "commit_dirty", "pages_build_output_dir",
        "_headers", "_redirects", "_routes.json", "_worker.js", "_worker.bundle",
        "functions-filepath-routing-config.json", "wrangler_config_hash",
    )
}

/** iOS `CloudflarePagesUploadTokenClaims`: the plan's file-count limit read from the upload JWT. */
object CloudflarePagesUploadTokenClaims {
    const val DEFAULT_MAXIMUM_FILE_COUNT: Int = 20_000
    const val ABSOLUTE_MAXIMUM_FILE_COUNT: Int = 100_000

    fun maximumFileCount(token: String): Int {
        val parts = token.split('.')
        if (parts.size < 2) return DEFAULT_MAXIMUM_FILE_COUNT
        val payload = runCatching {
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            ProviderJsonParser.parse(Base64.getDecoder().decode(padded))
        }.getOrNull() ?: return DEFAULT_MAXIMUM_FILE_COUNT
        val raw = (payload["max_file_count_allowed"] as? ProviderJsonValue.Num)?.decimal ?: return DEFAULT_MAXIMUM_FILE_COUNT
        val value = runCatching { raw.toLong() }.getOrDefault(DEFAULT_MAXIMUM_FILE_COUNT.toLong())
        return value.coerceIn(1L, ABSOLUTE_MAXIMUM_FILE_COUNT.toLong()).toInt()
    }
}

/** iOS `CloudflarePagesAssetHasher`: Wrangler's asset key is BLAKE3(base64(content) + extension), 16 bytes. */
object CloudflarePagesAssetHasher {
    fun blake3Hex(data: ByteArray): String = CloudflareBlake3.hash(data).toHex()

    fun assetHash(data: ByteArray, fileExtension: String): String {
        val encoded = Base64.getEncoder().encode(data)
        val extension = fileExtension.toByteArray(StandardCharsets.UTF_8)
        val input = ByteArray(encoded.size + extension.size)
        System.arraycopy(encoded, 0, input, 0, encoded.size)
        System.arraycopy(extension, 0, input, encoded.size, extension.size)
        return CloudflareBlake3.hash(input).copyOf(16).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
}

/** Pure-Kotlin BLAKE3 (32-byte default output), ported line for line from the iOS implementation. */
object CloudflareBlake3 {
    private const val CHUNK_LENGTH = 1_024
    private const val BLOCK_LENGTH = 64
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8
    private val IV = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val MESSAGE_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    fun hash(data: ByteArray): ByteArray {
        val chunkCount = maxOf(1, (data.size + CHUNK_LENGTH - 1) / CHUNK_LENGTH)
        val stack = ArrayList<IntArray>()
        for (chunkIndex in 0 until chunkCount - 1) {
            val start = chunkIndex * CHUNK_LENGTH
            val end = minOf(data.size, start + CHUNK_LENGTH)
            val output = chunkOutput(data, start, end, chunkIndex.toLong())
            addChunkChainingValue(output.chainingValue(), (chunkIndex + 1).toLong(), stack)
        }
        val finalStart = (chunkCount - 1) * CHUNK_LENGTH
        val finalEnd = minOf(data.size, finalStart + CHUNK_LENGTH)
        var output = chunkOutput(data, finalStart, finalEnd, (chunkCount - 1).toLong())
        while (stack.isNotEmpty()) {
            val left = stack.removeAt(stack.lastIndex)
            output = parentOutput(left, output.chainingValue())
        }
        return output.rootBytes(32)
    }

    private fun addChunkChainingValue(chunkValue: IntArray, totalChunks: Long, stack: ArrayList<IntArray>) {
        var value = chunkValue
        var chunks = totalChunks
        while (chunks and 1L == 0L) {
            if (stack.isEmpty()) break
            val left = stack.removeAt(stack.lastIndex)
            value = parentOutput(left, value).chainingValue()
            chunks = chunks shr 1
        }
        stack.add(value)
    }

    private fun chunkOutput(data: ByteArray, chunkStart: Int, chunkEnd: Int, counter: Long): Output {
        val length = chunkEnd - chunkStart
        val blockCount = maxOf(1, (length + BLOCK_LENGTH - 1) / BLOCK_LENGTH)
        var chainingValue = IV.copyOf()
        for (blockIndex in 0 until blockCount) {
            val start = chunkStart + blockIndex * BLOCK_LENGTH
            val end = minOf(chunkEnd, start + BLOCK_LENGTH)
            var flags = if (blockIndex == 0) CHUNK_START else 0
            if (blockIndex == blockCount - 1) flags = flags or CHUNK_END
            val output = Output(chainingValue, words(data, start, end), counter, end - start, flags)
            if (blockIndex == blockCount - 1) return output
            chainingValue = output.chainingValue()
        }
        error("BLAKE3 chunks always contain a final block")
    }

    private fun parentOutput(left: IntArray, right: IntArray): Output =
        Output(IV.copyOf(), left + right, 0L, BLOCK_LENGTH, PARENT)

    private fun words(data: ByteArray, start: Int, end: Int): IntArray {
        val result = IntArray(16)
        for (index in 0 until (end - start)) {
            val byte = data[start + index].toInt() and 0xFF
            result[index / 4] = result[index / 4] or (byte shl ((index % 4) * 8))
        }
        return result
    }

    private fun compress(chainingValue: IntArray, blockWords: IntArray, counter: Long, blockLength: Int, flags: Int): IntArray {
        val state = IntArray(16)
        System.arraycopy(chainingValue, 0, state, 0, 8)
        System.arraycopy(IV, 0, state, 8, 4)
        state[12] = counter.toInt()
        state[13] = (counter ushr 32).toInt()
        state[14] = blockLength
        state[15] = flags
        var message = blockWords.copyOf()
        for (roundIndex in 0 until 7) {
            round(state, message)
            if (roundIndex < 6) message = IntArray(16) { message[MESSAGE_PERMUTATION[it]] }
        }
        val output = IntArray(16)
        for (index in 0 until 8) {
            output[index] = state[index] xor state[index + 8]
            output[index + 8] = state[index + 8] xor chainingValue[index]
        }
        return output
    }

    private fun round(state: IntArray, m: IntArray) {
        mix(state, 0, 4, 8, 12, m[0], m[1])
        mix(state, 1, 5, 9, 13, m[2], m[3])
        mix(state, 2, 6, 10, 14, m[4], m[5])
        mix(state, 3, 7, 11, 15, m[6], m[7])
        mix(state, 0, 5, 10, 15, m[8], m[9])
        mix(state, 1, 6, 11, 12, m[10], m[11])
        mix(state, 2, 7, 8, 13, m[12], m[13])
        mix(state, 3, 4, 9, 14, m[14], m[15])
    }

    private fun mix(s: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        s[a] = s[a] + s[b] + x
        s[d] = Integer.rotateRight(s[d] xor s[a], 16)
        s[c] = s[c] + s[d]
        s[b] = Integer.rotateRight(s[b] xor s[c], 12)
        s[a] = s[a] + s[b] + y
        s[d] = Integer.rotateRight(s[d] xor s[a], 8)
        s[c] = s[c] + s[d]
        s[b] = Integer.rotateRight(s[b] xor s[c], 7)
    }

    private class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLength: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray = compress(inputChainingValue, blockWords, counter, blockLength, flags).copyOf(8)

        fun rootBytes(count: Int): ByteArray {
            val bytes = ByteArrayOutputStream(count + 64)
            var outputBlockCounter = 0L
            while (bytes.size() < count) {
                val words = compress(inputChainingValue, blockWords, outputBlockCounter, blockLength, flags or ROOT)
                words.forEach { word ->
                    bytes.write(word and 0xFF)
                    bytes.write((word ushr 8) and 0xFF)
                    bytes.write((word ushr 16) and 0xFF)
                    bytes.write((word ushr 24) and 0xFF)
                }
                outputBlockCounter += 1
            }
            return bytes.toByteArray().copyOf(count)
        }
    }
}

/** iOS `CloudflarePagesMultipartBody`: binary-safe multipart/form-data with sanitized headers. */
class CloudflarePagesMultipartBody(private val boundary: String) {
    private val data = ByteArrayOutputStream()

    init {
        require(boundary.isNotEmpty() && boundary.none { it == '\r' || it == '\n' || it == '"' }) { "Invalid multipart boundary." }
    }

    fun appendText(name: String, value: String) {
        appendBoundary()
        appendUtf8("Content-Disposition: form-data; name=\"${safe(name)}\"\r\n\r\n")
        appendUtf8(value)
        appendUtf8("\r\n")
    }

    fun appendFile(name: String, fileName: String, contentType: String, data: ByteArray) {
        appendBoundary()
        appendUtf8("Content-Disposition: form-data; name=\"${safe(name)}\"; filename=\"${safe(fileName)}\"\r\n")
        appendUtf8("Content-Type: ${safeContentType(contentType)}\r\n\r\n")
        this.data.write(data)
        appendUtf8("\r\n")
    }

    /** Returns the body bytes and the matching `Content-Type` header value. */
    fun finalized(): Pair<ByteArray, String> {
        appendUtf8("--$boundary--\r\n")
        return data.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    private fun appendBoundary() = appendUtf8("--$boundary\r\n")

    private fun appendUtf8(value: String) = data.write(value.toByteArray(StandardCharsets.UTF_8))

    private fun safe(value: String): String = value.replace('"', '_').replace('\r', '_').replace('\n', '_')

    private fun safeContentType(value: String): String =
        value.replace("\r", "").replace("\n", "").ifEmpty { "application/octet-stream" }
}

/** One entry of a build folder chosen through the Storage Access Framework (or an in-memory tree in tests). */
data class CloudflarePagesFolderEntry(
    /** Opaque identifier the folder implementation uses to read or list this entry. */
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val isSymbolicLink: Boolean = false,
)

/** A read-only build folder. Implementations must not follow links outside the chosen tree. */
interface CloudflarePagesBuildFolder {
    val displayName: String

    /** Direct children of [directory], or of the folder root when [directory] is null. */
    fun children(directory: CloudflarePagesFolderEntry?): List<CloudflarePagesFolderEntry>

    /** Reads at most [maximumBytes]; throws [CloudflareOperationException] when the file is larger. */
    fun read(entry: CloudflarePagesFolderEntry, maximumBytes: Int): ByteArray
}

/** Reads [input] completely, refusing anything larger than [maximumBytes] (iOS `readFile`). */
fun readCloudflarePagesFile(input: InputStream, name: String, maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1_024).coerceAtLeast(16))
    val buffer = ByteArray(16 * 1_024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) {
            throw CloudflareOperationException.invalidRequest("$name exceeds the Pages 25 MiB file limit.")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** A prepared asset; bytes are re-read from [entry] when uploading so memory stays bounded. */
data class CloudflarePagesPreparedAsset(
    val entry: CloudflarePagesFolderEntry,
    val relativePath: String,
    val size: Int,
    val contentType: String,
    val hash: String,
)

/** `_headers`, `_redirects`, `_worker.bundle` and routing files sent as multipart parts. */
class CloudflarePagesPreparedSpecialFile(
    val fieldName: String,
    val fileName: String,
    val contentType: String,
    val data: ByteArray,
)

/** iOS `CloudflarePagesUploadPackage`: folder walk, ignore rules, limits, hashing and bucketing. */
class CloudflarePagesUploadPackage(
    val assets: List<CloudflarePagesPreparedAsset>,
    val specialFiles: List<CloudflarePagesPreparedSpecialFile>,
) {
    val uniqueAssets: List<CloudflarePagesPreparedAsset>
        get() {
            val seen = HashSet<String>()
            return assets.filter { seen.add(it.hash) }
        }

    /** iOS manifest: `{"/relative/path": "hash"}` with sorted keys and unescaped slashes. */
    fun manifestJson(): String =
        ProviderJsonWriter.write(ProviderJsonValue.from(assets.associate { "/" + it.relativePath to it.hash }.toSortedMap()))

    companion object {
        const val MAXIMUM_ASSET_BYTES: Int = 25 * 1_024 * 1_024

        /** Wrangler's and iOS's bucket ceiling. */
        const val IOS_MAXIMUM_BUCKET_BYTES: Int = 40 * 1_024 * 1_024

        /**
         * Android caps each upload request lower than iOS so the base64 JSON body and its transport copies
         * stay well inside a typical 256 MB app heap. Cloudflare accepts any bucket at or below 40 MiB.
         */
        const val ANDROID_MAXIMUM_BUCKET_BYTES: Int = 16 * 1_024 * 1_024
        const val MAXIMUM_BUCKET_FILES: Int = 2_000

        private val ROOT_SPECIAL_NAMES = listOf("_headers", "_redirects", "_worker.bundle")
        private val WORKER_ROUTING_NAMES = listOf("_routes.json", "functions-filepath-routing-config.json")
        private val IGNORED_ROOT_NAMES = setOf(
            "_headers", "_redirects", "_routes.json", "_worker.js", "_worker.bundle",
            "functions-filepath-routing-config.json", "functions",
        )

        fun prepare(
            folder: CloudflarePagesBuildFolder,
            fileLimit: Int,
            progress: (CloudflarePagesDirectUploadProgress) -> Unit = {},
            checkCancelled: () -> Unit = {},
        ): CloudflarePagesUploadPackage {
            val root = folder.children(null)
            root.firstOrNull { it.name == "_worker.js" }?.let { worker ->
                val kind = if (worker.isDirectory) "directory" else "file"
                throw CloudflareOperationException.invalidRequest(
                    "This build contains an _worker.js $kind. Cloudflare's current direct-upload contract requires it to be bundled into _worker.bundle with Wrangler first.",
                )
            }

            val specialFiles = mutableListOf<CloudflarePagesPreparedSpecialFile>()
            fun addSpecial(name: String) {
                val entry = root.firstOrNull { it.name == name } ?: return
                if (entry.isDirectory || entry.isSymbolicLink) {
                    throw CloudflareOperationException.invalidRequest("$name is not a regular file.")
                }
                specialFiles += CloudflarePagesPreparedSpecialFile(
                    fieldName = name,
                    fileName = name,
                    contentType = contentType(name),
                    data = readAsset(folder, entry, name),
                )
            }
            ROOT_SPECIAL_NAMES.forEach(::addSpecial)
            val hasWorkerArtifact = specialFiles.any { it.fieldName == "_worker.bundle" }
            if (hasWorkerArtifact) WORKER_ROUTING_NAMES.forEach(::addSpecial)

            val hasFunctionsDirectory = root.any { it.name == "functions" && it.isDirectory }
            if (hasFunctionsDirectory && !hasWorkerArtifact) {
                throw CloudflareOperationException.invalidRequest(
                    "This folder contains Pages Functions source. Build it with Wrangler first and include the generated _worker.bundle before uploading from the app.",
                )
            }

            val candidates = mutableListOf<Pair<CloudflarePagesFolderEntry, String>>()
            val pending = ArrayDeque<Pair<String, List<CloudflarePagesFolderEntry>>>()
            pending.addLast("" to root)
            while (pending.isNotEmpty()) {
                checkCancelled()
                val (parentPath, children) = pending.removeFirst()
                for (entry in children) {
                    checkCancelled()
                    val relativePath = if (parentPath.isEmpty()) entry.name else "$parentPath/${entry.name}"
                    if (relativePath.isEmpty() || entry.name.isEmpty() || entry.name == "." || entry.name == ".." || '/' in entry.name) continue
                    if (shouldIgnore(relativePath)) continue
                    if (entry.isSymbolicLink) continue
                    if (entry.isDirectory) {
                        pending.addLast(relativePath to folder.children(entry))
                        continue
                    }
                    if (entry.size > MAXIMUM_ASSET_BYTES) {
                        throw CloudflareOperationException.invalidRequest(
                            "Pages supports files up to 25 MiB. $relativePath is ${CloudflareFormat.bytes(entry.size)}.",
                        )
                    }
                    candidates += entry to relativePath
                    if (candidates.size > fileLimit) {
                        throw CloudflareOperationException.invalidRequest(
                            "This Pages plan allows up to ${CloudflareFormat.grouped(fileLimit.toLong())} files per deployment.",
                        )
                    }
                }
            }
            candidates.sortBy { it.second }
            if (candidates.isEmpty() && specialFiles.isEmpty()) {
                throw CloudflareOperationException.invalidRequest("The selected build folder contains no deployable files.")
            }

            progress(CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.HASHING, 0, candidates.size))
            val assets = candidates.mapIndexed { index, (entry, relativePath) ->
                checkCancelled()
                val data = readAsset(folder, entry, relativePath)
                CloudflarePagesPreparedAsset(
                    entry = entry,
                    relativePath = relativePath,
                    size = data.size,
                    contentType = contentType(relativePath),
                    hash = CloudflarePagesAssetHasher.assetHash(data, fileExtension(relativePath)),
                ).also {
                    data.fill(0)
                    progress(CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.HASHING, index + 1, candidates.size))
                }
            }
            return CloudflarePagesUploadPackage(assets, specialFiles)
        }

        fun readAsset(folder: CloudflarePagesBuildFolder, entry: CloudflarePagesFolderEntry, name: String): ByteArray {
            if (entry.isDirectory || entry.isSymbolicLink) {
                throw CloudflareOperationException.invalidRequest("${entry.name} is not a regular file.")
            }
            if (entry.size > MAXIMUM_ASSET_BYTES) {
                throw CloudflareOperationException.invalidRequest("${entry.name} exceeds the Pages 25 MiB file limit.")
            }
            val data = try {
                folder.read(entry, MAXIMUM_ASSET_BYTES)
            } catch (error: CloudflareOperationException) {
                throw error
            } catch (error: Exception) {
                throw CloudflareOperationException.invalidRequest("$name could not be read from the selected folder.")
            }
            if (data.size > MAXIMUM_ASSET_BYTES) {
                throw CloudflareOperationException.invalidRequest("${entry.name} exceeds the Pages 25 MiB file limit.")
            }
            return data
        }

        /** iOS bucket packing: round-robin first fit by remaining bytes, at most 2,000 files per bucket. */
        fun buckets(
            files: List<CloudflarePagesPreparedAsset>,
            maximumBucketBytes: Int = IOS_MAXIMUM_BUCKET_BYTES,
            maximumBucketFiles: Int = MAXIMUM_BUCKET_FILES,
        ): List<List<CloudflarePagesPreparedAsset>> {
            val buckets = mutableListOf<MutableList<CloudflarePagesPreparedAsset>>()
            val remainingBytes = mutableListOf<Long>()
            var offset = 0
            for (file in files) {
                var inserted = false
                for (indexOffset in 0 until buckets.size) {
                    val index = (indexOffset + offset) % buckets.size
                    if (remainingBytes[index] >= file.size && buckets[index].size < maximumBucketFiles) {
                        buckets[index].add(file)
                        remainingBytes[index] -= file.size.toLong()
                        inserted = true
                        break
                    }
                }
                if (!inserted) {
                    buckets.add(mutableListOf(file))
                    remainingBytes.add(maximumBucketBytes.toLong() - file.size)
                }
                offset += 1
            }
            return buckets
        }

        /** iOS `truncateUTF8`: keeps whole characters within [maximumBytes] UTF-8 bytes. */
        fun truncateUtf8(value: String, maximumBytes: Int): String {
            if (value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes) return value
            val result = StringBuilder()
            var bytes = 0
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val text = String(Character.toChars(codePoint))
                val count = text.toByteArray(StandardCharsets.UTF_8).size
                if (bytes + count > maximumBytes) break
                result.append(text)
                bytes += count
                index += Character.charCount(codePoint)
            }
            return result.toString()
        }

        /** iOS `shouldIgnore`: VCS, tooling, dependency and OS metadata plus root-level special files. */
        fun shouldIgnore(relativePath: String): Boolean {
            val components = relativePath.split('/').filter(String::isNotEmpty)
            if (components.isEmpty()) return true
            if (components.any { it == ".git" || it == ".wrangler" || it == "node_modules" }) return true
            if (components.last() == ".DS_Store") return true
            if (components.size == 1) return components[0] in IGNORED_ROOT_NAMES
            return false
        }

        /** Foundation `pathExtension`: the text after the last dot, empty for dotfiles without one. */
        fun fileExtension(path: String): String {
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1)
        }

        fun contentType(path: String): String =
            CONTENT_TYPES[fileExtension(path).lowercase(Locale.ROOT)] ?: "application/octet-stream"

        private val CONTENT_TYPES = mapOf(
            "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
            "mjs" to "text/javascript", "cjs" to "text/javascript", "json" to "application/json", "map" to "application/json",
            "webmanifest" to "application/manifest+json", "txt" to "text/plain", "md" to "text/markdown",
            "csv" to "text/csv", "xml" to "application/xml", "rss" to "application/rss+xml", "atom" to "application/atom+xml",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/vnd.microsoft.icon",
            "bmp" to "image/bmp", "tif" to "image/tiff", "tiff" to "image/tiff", "heic" to "image/heic",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "eot" to "application/vnd.ms-fontobject", "wasm" to "application/wasm", "pdf" to "application/pdf",
            "zip" to "application/zip", "gz" to "application/gzip", "mp4" to "video/mp4", "webm" to "video/webm",
            "mov" to "video/quicktime", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
            "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "yaml" to "application/yaml",
            "yml" to "application/yaml", "toml" to "application/toml",
        )
    }
}
