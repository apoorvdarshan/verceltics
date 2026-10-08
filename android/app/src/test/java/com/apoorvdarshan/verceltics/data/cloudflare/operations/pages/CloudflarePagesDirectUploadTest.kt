package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflarePagesDirectUploadTest {
    @Test
    fun blake3MatchesOfficialVectors() {
        assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            CloudflarePagesAssetHasher.blake3Hex(ByteArray(0)),
        )
        assertEquals(
            "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85",
            CloudflarePagesAssetHasher.blake3Hex("abc".toByteArray()),
        )
        assertEquals(
            "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444",
            CloudflarePagesAssetHasher.blake3Hex(officialInput(1_025)),
        )
        assertEquals(
            "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
            CloudflarePagesAssetHasher.blake3Hex(officialInput(1)),
        )
        assertEquals(
            "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7",
            CloudflarePagesAssetHasher.blake3Hex(officialInput(1_024)),
        )
        assertEquals(
            "e776b6028c7cd22a4d0ba182a8bf62205d2ef576467e838ed6f2529b85fba24a",
            CloudflarePagesAssetHasher.blake3Hex(officialInput(2_048)),
        )
    }

    @Test
    fun assetHashUsesBase64ContentAndExtension() {
        assertEquals("f0b3413d4cabb000327fad369003d6a5", CloudflarePagesAssetHasher.assetHash("hello".toByteArray(), "txt"))
        assertEquals(32, CloudflarePagesAssetHasher.assetHash(ByteArray(10_000) { it.toByte() }, "bin").length)
    }

    @Test
    fun uploadTokenClaimsRespectPlanLimitAndSafetyCap() {
        assertEquals(50_000, CloudflarePagesUploadTokenClaims.maximumFileCount(token("{\"max_file_count_allowed\":50000}")))
        assertEquals(100_000, CloudflarePagesUploadTokenClaims.maximumFileCount(token("{\"max_file_count_allowed\":500000}")))
        assertEquals(1, CloudflarePagesUploadTokenClaims.maximumFileCount(token("{\"max_file_count_allowed\":0}")))
        assertEquals(20_000, CloudflarePagesUploadTokenClaims.maximumFileCount(token("{\"other\":1}")))
        assertEquals(20_000, CloudflarePagesUploadTokenClaims.maximumFileCount("invalid"))
        assertEquals(20_000, CloudflarePagesUploadTokenClaims.maximumFileCount("a.!!!.c"))
    }

    @Test
    fun multipartBodyPreservesBinaryFileDataAndSanitizesHeaders() {
        val multipart = CloudflarePagesMultipartBody("test-boundary")
        multipart.appendText("manifest", "{\"/index.html\":\"hash\"}")
        multipart.appendFile("_worker.bundle", "evil\"\r\nname", "application/octet-stream\r\nX: y", byteArrayOf(0x00, 0xFF.toByte(), 0x10))
        val (data, contentType) = multipart.finalized()
        val text = String(data, StandardCharsets.ISO_8859_1)

        assertEquals("multipart/form-data; boundary=test-boundary", contentType)
        assertTrue(text.contains("name=\"manifest\""))
        assertTrue(text.contains("filename=\"evil___name\""))
        assertTrue(text.contains("Content-Type: application/octet-streamX: y\r\n"))
        assertTrue(indexOf(data, byteArrayOf(0x00, 0xFF.toByte(), 0x10)) >= 0)
        assertTrue(text.endsWith("--test-boundary--\r\n"))
    }

    @Test
    fun progressMessagesIncludeLiveCounts() {
        assertEquals(
            "Preparing files · 3 of 9",
            CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.HASHING, 3, 9).message,
        )
        assertEquals(
            "Uploading assets · 6 of 9",
            CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.UPLOADING, 6, 9).message,
        )
        assertEquals(
            "Reading the build folder…",
            CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.HASHING, 0, 0).message,
        )
        assertEquals(1.0, CloudflarePagesDirectUploadProgress(CloudflarePagesDirectUploadProgress.Stage.UPLOADING, 12, 9).fractionCompleted!!, 0.0)
    }

    @Test
    fun optionsDropBlankValues() {
        val options = CloudflarePagesDirectUploadOptions(branch = "  ", commitMessage = " hi ")
        assertEquals(null, options.branch)
        assertEquals("hi", options.commitMessage)
    }

    @Test
    fun prepareAppliesIgnoreRulesSortsAndCollectsSpecialFiles() {
        val folder = InMemoryBuildFolder(
            mapOf(
                "index.html" to "<h1>hi</h1>",
                "assets/app.js" to "console.log(1)",
                "assets/.DS_Store" to "x",
                ".git/HEAD" to "ref",
                "node_modules/a/index.js" to "x",
                "nested/.wrangler/tmp" to "x",
                "_headers" to "/*\n  X-Test: 1",
                "_redirects" to "/a /b 301",
                "_routes.json" to "{}",
                ".well-known/security.txt" to "contact",
            ),
        )
        val progress = mutableListOf<CloudflarePagesDirectUploadProgress>()
        val upload = CloudflarePagesUploadPackage.prepare(folder, fileLimit = 100, progress = { progress += it })

        assertEquals(listOf(".well-known/security.txt", "assets/app.js", "index.html"), upload.assets.map { it.relativePath })
        assertEquals(listOf("_headers", "_redirects"), upload.specialFiles.map { it.fieldName })
        assertEquals("text/javascript", upload.assets[1].contentType)
        assertEquals(CloudflarePagesAssetHasher.assetHash("<h1>hi</h1>".toByteArray(), "html"), upload.assets[2].hash)
        assertEquals(
            "{\"/.well-known/security.txt\":\"${upload.assets[0].hash}\",\"/assets/app.js\":\"${upload.assets[1].hash}\"," +
                "\"/index.html\":\"${upload.assets[2].hash}\"}",
            upload.manifestJson(),
        )
        assertEquals(3, progress.last().completed)
        assertEquals(CloudflarePagesDirectUploadProgress.Stage.HASHING, progress.first().stage)
    }

    @Test
    fun workerBundleBringsRoutingFilesAndAllowsFunctionsFolder() {
        val folder = InMemoryBuildFolder(
            mapOf(
                "index.html" to "a",
                "_worker.bundle" to "bundle",
                "_routes.json" to "{\"version\":1}",
                "functions-filepath-routing-config.json" to "{}",
                "functions/api.js" to "export default {}",
            ),
        )
        val upload = CloudflarePagesUploadPackage.prepare(folder, fileLimit = 100)
        assertEquals(
            listOf("_worker.bundle", "_routes.json", "functions-filepath-routing-config.json"),
            upload.specialFiles.map { it.fieldName },
        )
        assertEquals(listOf("index.html"), upload.assets.map { it.relativePath })
    }

    @Test
    fun prepareRejectsUnsupportedBuildsWithIosCopy() {
        assertMessage(
            "This build contains an _worker.js file. Cloudflare's current direct-upload contract requires it to be bundled into _worker.bundle with Wrangler first.",
        ) { CloudflarePagesUploadPackage.prepare(InMemoryBuildFolder(mapOf("_worker.js" to "x", "index.html" to "a")), 10) }
        assertMessage(
            "This build contains an _worker.js directory. Cloudflare's current direct-upload contract requires it to be bundled into _worker.bundle with Wrangler first.",
        ) { CloudflarePagesUploadPackage.prepare(InMemoryBuildFolder(mapOf("_worker.js/index.js" to "x")), 10) }
        assertMessage(
            "This folder contains Pages Functions source. Build it with Wrangler first and include the generated _worker.bundle before uploading from the app.",
        ) { CloudflarePagesUploadPackage.prepare(InMemoryBuildFolder(mapOf("functions/a.js" to "x", "index.html" to "a")), 10) }
        assertMessage("The selected build folder contains no deployable files.") {
            CloudflarePagesUploadPackage.prepare(InMemoryBuildFolder(mapOf(".git/HEAD" to "x")), 10)
        }
        assertMessage("This Pages plan allows up to 2 files per deployment.") {
            CloudflarePagesUploadPackage.prepare(InMemoryBuildFolder(mapOf("a" to "1", "b" to "2", "c" to "3")), 2)
        }
        val oversized = InMemoryBuildFolder(mapOf("big.bin" to "x"), sizeOverride = mapOf("big.bin" to 30L * 1_024 * 1_024))
        assertMessage("Pages supports files up to 25 MiB. big.bin is 31.5 MB.") {
            CloudflarePagesUploadPackage.prepare(oversized, 10)
        }
    }

    @Test
    fun bucketsRespectByteAndFileLimitsInRoundRobinOrder() {
        val files = listOf(30, 25, 20, 10, 5).mapIndexed { index, size -> asset("f$index", size) }
        val buckets = CloudflarePagesUploadPackage.buckets(files, maximumBucketBytes = 40, maximumBucketFiles = 10)
        assertTrue(buckets.all { bucket -> bucket.sumOf { it.size } <= 40 })
        assertEquals(files.toSet(), buckets.flatten().toSet())
        assertEquals(listOf(listOf("f0", "f3"), listOf("f1", "f4"), listOf("f2")), buckets.map { bucket -> bucket.map { it.relativePath } })

        val many = (0 until 5).map { asset("m$it", 1) }
        val limited = CloudflarePagesUploadPackage.buckets(many, maximumBucketBytes = 1_000, maximumBucketFiles = 2)
        assertTrue(limited.all { it.size <= 2 })
        assertEquals(5, limited.sumOf { it.size })
    }

    @Test
    fun truncatesCommitMessagesOnCharacterBoundaries() {
        assertEquals("abc", CloudflarePagesUploadPackage.truncateUtf8("abc", 384))
        val emoji = "😀".repeat(200)
        val truncated = CloudflarePagesUploadPackage.truncateUtf8(emoji, 384)
        assertEquals(96, truncated.codePointCount(0, truncated.length))
        assertTrue(truncated.toByteArray().size <= 384)
    }

    @Test
    fun extensionsAndIgnoreRulesMatchFoundation() {
        assertEquals("gz", CloudflarePagesUploadPackage.fileExtension("a/b.tar.gz"))
        assertEquals("", CloudflarePagesUploadPackage.fileExtension(".htaccess"))
        assertEquals("", CloudflarePagesUploadPackage.fileExtension("README"))
        assertEquals("", CloudflarePagesUploadPackage.fileExtension("file."))
        assertTrue(CloudflarePagesUploadPackage.shouldIgnore("_redirects"))
        assertFalse(CloudflarePagesUploadPackage.shouldIgnore("docs/_redirects"))
        assertTrue(CloudflarePagesUploadPackage.shouldIgnore("a/node_modules/b.js"))
        assertEquals("application/octet-stream", CloudflarePagesUploadPackage.contentType("data.unknownext"))
    }

    @Test
    fun boundedReaderRefusesOversizedFiles() {
        val data = ByteArray(10) { it.toByte() }
        assertArrayEquals(data, readCloudflarePagesFile(data.inputStream(), "a", 10))
        assertMessage("a exceeds the Pages 25 MiB file limit.") { readCloudflarePagesFile(data.inputStream(), "a", 9) }
    }

    private fun asset(path: String, size: Int) = CloudflarePagesPreparedAsset(
        entry = CloudflarePagesFolderEntry(path, path, false, size.toLong()),
        relativePath = path,
        size = size,
        contentType = "application/octet-stream",
        hash = path,
    )

    private fun officialInput(length: Int) = ByteArray(length) { (it % 251).toByte() }

    private fun token(payload: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "header.$encoded.signature"
    }

    private fun assertMessage(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected a Cloudflare error, got $error", error is CloudflareOperationException)
        assertEquals(expected, (error as CloudflareOperationException).userMessage)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) if (haystack[index + offset] != needle[offset]) continue@outer
            return index
        }
        return -1
    }
}

/** In-memory build folder keyed by relative path; directories are implied by path prefixes. */
class InMemoryBuildFolder(
    private val files: Map<String, String>,
    private val sizeOverride: Map<String, Long> = emptyMap(),
) : CloudflarePagesBuildFolder {
    val reads = mutableListOf<String>()
    override val displayName: String = "dist"

    override fun children(directory: CloudflarePagesFolderEntry?): List<CloudflarePagesFolderEntry> {
        val prefix = directory?.id?.let { "$it/" } ?: ""
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') to it.removePrefix(prefix).contains('/') }
            .distinct()
            .map { (name, isDirectory) ->
                val id = prefix + name
                CloudflarePagesFolderEntry(
                    id = id,
                    name = name,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0 else sizeOverride[id] ?: files.getValue(id).toByteArray().size.toLong(),
                )
            }
    }

    override fun read(entry: CloudflarePagesFolderEntry, maximumBytes: Int): ByteArray {
        reads += entry.id
        return readCloudflarePagesFile(files.getValue(entry.id).toByteArray().inputStream(), entry.name, maximumBytes)
    }
}
