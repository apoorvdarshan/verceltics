package com.apoorvdarshan.verceltics.data.cloudflare.storage

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.DELETE
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.GET
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.POST
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.PUT
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareStorageApiTest {
    private val transport = FakeCloudflareRestTransport()
    private val api = CloudflareStorageApi(FakeCloudflareRestTransport.client(transport))
    private val account = "acc"

    // MARK: D1

    @Test
    fun d1ListFollowsTotalCountWithIosPageSize() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/d1/database", envelope("[{\"uuid\":\"a\",\"name\":\"A\"}]", "{\"total_count\":2}"))
        transport.enqueueJson(
            GET,
            "/accounts/acc/d1/database",
            envelope("[{\"uuid\":\"b\",\"name\":\"B\",\"file_size\":2048,\"num_tables\":3,\"read_replication\":{\"mode\":\"auto\"}}]", "{\"total_count\":2}"),
        )
        val databases = api.fetchD1Databases(account)
        assertEquals(listOf("a", "b"), databases.map { it.uuid })
        assertEquals(2048L, databases[1].fileSize)
        assertEquals(3, databases[1].numberOfTables)
        assertEquals("auto", databases[1].readReplicationMode)
        assertEquals(listOf("1", "2"), transport.requests.map { it.query.first { q -> q.first == "page" }.second })
        assertTrue(transport.requests.all { it.query.contains("per_page" to "1000") })
    }

    @Test
    fun createD1SendsIosBodyOnlyAfterMatchingConfirmation() = runTest {
        val input = CloudflareD1CreateInput(" app-db ", jurisdiction = "eu", primaryLocationHint = null, readReplicationMode = "disabled")
        expectError("Confirm the change to app-db before continuing.") {
            api.createD1Database(account, input, CloudflareMutationConfirmation("other"))
        }
        expectError("Enter a D1 database name.") {
            api.createD1Database(account, CloudflareD1CreateInput("  "), CloudflareMutationConfirmation(""))
        }
        assertTrue(transport.requests.isEmpty())

        transport.enqueueJson(POST, "/accounts/acc/d1/database", envelope("{\"uuid\":\"new\",\"name\":\"app-db\"}"))
        val created = api.createD1Database(account, input, CloudflareMutationConfirmation("app-db"))
        assertEquals("new", created.uuid)
        val body = transport.requests.single().bodyJson
        assertEquals("app-db", body.str("name"))
        assertEquals("eu", body.str("jurisdiction"))
        assertEquals("disabled", body.obj("read_replication").str("mode"))
        assertNull(body?.get("primary_location_hint"))
    }

    @Test
    fun d1QueryAndDeleteTargetTheDatabase() = runTest {
        transport.enqueueJson(
            POST,
            "/accounts/acc/d1/database/db-1/query",
            envelope(
                "[{\"success\":true,\"results\":[{\"name\":\"users\",\"n\":1},{\"name\":\"logs\"}]," +
                    "\"meta\":{\"changed_db\":false,\"rows_read\":2,\"timings\":{\"sql_duration_ms\":0.5},\"served_by_region\":\"WEUR\"}}]",
            ),
        )
        transport.enqueueJson(DELETE, "/accounts/acc/d1/database/db-1", "")

        expectError("Enter a SQL statement.") { api.queryD1Database(account, "db-1", "  ", confirmation = CloudflareMutationConfirmation("db-1")) }
        expectError("Confirm the change to db-1 before continuing.") {
            api.queryD1Database(account, "db-1", "SELECT 1", confirmation = CloudflareMutationConfirmation("db-2"))
        }
        val results = api.queryD1Database(account, "db-1", " SELECT * FROM sqlite_schema; ", confirmation = CloudflareMutationConfirmation("db-1"))
        assertEquals("SELECT * FROM sqlite_schema;", transport.requests[0].bodyJson.str("sql"))
        assertEquals(ProviderJsonValue.Arr(emptyList()), transport.requests[0].bodyJson?.get("params"))
        assertEquals(listOf("n", "name"), results.single().columns)
        assertEquals("0.50 ms · 2 rows read · WEUR", results.single().meta?.summary)
        assertEquals("NULL", cloudflareStorageDisplayValue(results.single().rows[1]["n"]))

        api.deleteD1Database(account, "db-1", CloudflareMutationConfirmation("db-1"))
        assertEquals(DELETE, transport.requests.last().method)
    }

    // MARK: KV

    @Test
    fun kvNamespaceLifecycleUsesIosVerbsAndBodies() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/storage/kv/namespaces", envelope("[{\"id\":\"ns\",\"title\":\"CACHE\"}]"))
        transport.enqueueJson(POST, "/accounts/acc/storage/kv/namespaces", envelope("{\"id\":\"ns2\",\"title\":\"NEW\"}"))
        transport.enqueueJson(PUT, "/accounts/acc/storage/kv/namespaces/ns", envelope("{\"id\":\"ns\",\"title\":\"RENAMED\"}"))
        transport.enqueueJson(DELETE, "/accounts/acc/storage/kv/namespaces/ns", envelope("null"))

        assertEquals(listOf("CACHE"), api.fetchKVNamespaces(account).map { it.title })
        val listQuery = transport.requests[0].query
        assertTrue(listQuery.containsAll(listOf("order" to "title", "direction" to "asc", "per_page" to "1000")))

        assertEquals("NEW", api.createKVNamespace(account, " NEW ", CloudflareMutationConfirmation("NEW")).title)
        assertEquals("{\"title\":\"NEW\"}", transport.requests[1].bodyText)

        assertEquals("RENAMED", api.renameKVNamespace(account, "ns", "RENAMED", CloudflareMutationConfirmation("ns")).title)
        assertEquals("{\"title\":\"RENAMED\"}", transport.requests[2].bodyText)

        api.deleteKVNamespace(account, "ns", CloudflareMutationConfirmation("ns"))
        assertEquals("{}", transport.requests[3].bodyText)
        expectError("Enter a KV namespace title.") { api.renameKVNamespace(account, "ns", " ", CloudflareMutationConfirmation("ns")) }
    }

    @Test
    fun kvKeysFollowCursorsAndStopOnRepeatedCursor() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/storage/kv/namespaces/ns/keys", envelope("[{\"name\":\"a\",\"expiration\":1700000000}]", "{\"cursor\":\"c1\"}"))
        transport.enqueueJson(GET, "/accounts/acc/storage/kv/namespaces/ns/keys", envelope("[{\"name\":\"b\",\"metadata\":{\"v\":1}}]", "{\"cursor\":\"\"}"))
        val keys = api.fetchKVKeys(account, "ns")
        assertEquals(listOf("a", "b"), keys.map { it.name })
        assertEquals(1_700_000_000_000L, keys[0].expirationDate?.toEpochMilli())
        assertEquals("{\"v\":1}", cloudflareStorageDisplayValue(keys[1].metadata))
        assertEquals(listOf(null, "c1"), transport.requests.map { r -> r.query.firstOrNull { it.first == "cursor" }?.second })

        transport.enqueueJson(GET, "/accounts/acc/storage/kv/namespaces/loop/keys", envelope("[{\"name\":\"x\"}]", "{\"cursor\":\"same\"}"))
        transport.enqueueJson(GET, "/accounts/acc/storage/kv/namespaces/loop/keys", envelope("[{\"name\":\"y\"}]", "{\"cursor\":\"same\"}"))
        expectError("Cloudflare repeated a KV cursor, so loading stopped safely.") { api.fetchKVKeys(account, "loop") }

        transport.alwaysJson(GET, "/accounts/acc/storage/kv/namespaces/echo/keys", envelope("[{\"name\":\"x\"}]", "{\"cursor\":\"c\"}"))
        expectError("Cloudflare repeated a results page, so loading stopped safely.") { api.fetchKVKeys(account, "echo") }
    }

    @Test
    fun kvValuesAreEncodedRawWithContentTypeAndTtl() = runTest {
        val key = "settings/theme dark"
        val path = "/accounts/acc/storage/kv/namespaces/ns/values/settings%2Ftheme%20dark"
        transport.enqueue(
            GET,
            path,
            FakeCloudflareRestTransport.raw(byteArrayOf(0xFF.toByte(), 0x00, 0x01), headers = mapOf("Content-Type" to listOf("application/octet-stream"))),
        )
        transport.enqueueJson(PUT, path, envelope("null"))
        transport.enqueueJson(DELETE, path, "")

        val value = api.readKVValue(account, "ns", key)
        assertNull(value.utf8Text)
        assertEquals("/wAB", value.base64Text)
        assertEquals("application/octet-stream", value.contentType)

        expectError("KV expiration TTL must be at least 60 seconds.") {
            api.writeKVValue(account, "ns", key, byteArrayOf(1), expirationTtl = 59, confirmation = CloudflareMutationConfirmation(key))
        }
        expectError("Enter a KV key.") { api.writeKVValue(account, "ns", "  ", byteArrayOf(1), confirmation = CloudflareMutationConfirmation("")) }
        expectError("Confirm the change to $key before continuing.") {
            api.writeKVValue(account, "ns", key, byteArrayOf(1), confirmation = CloudflareMutationConfirmation("ns"))
        }

        api.writeKVValue(account, "ns", key, "hello".toByteArray(), "text/plain\r\nX: y", 120, CloudflareMutationConfirmation(key))
        val write = transport.requests.single { it.method == PUT }
        assertEquals("hello", write.bodyText)
        assertEquals("text/plainX: y", write.request.contentType)
        assertEquals(listOf("expiration_ttl" to "120"), write.query)

        api.deleteKVValue(account, "ns", key, CloudflareMutationConfirmation(key))
        assertEquals("{}", transport.requests.last().bodyText)
    }

    // MARK: R2

    @Test
    fun r2BucketListingScansJurisdictionsAndSkipsUnavailableScopes() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets", envelope("{\"buckets\":[{\"name\":\"assets\",\"location\":\"weur\"}]}"))
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets", envelope("{\"buckets\":[{\"name\":\"assets\"}]}"))
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets", failure("Jurisdiction not enabled"), status = 403)

        val buckets = api.fetchR2Buckets(account)
        assertEquals(setOf("default|assets", "eu|assets"), buckets.map { it.id }.toSet())
        assertEquals(listOf(null, "eu", "fedramp"), transport.requests.map { it.request.headers["cf-r2-jurisdiction"] })
        assertTrue(transport.requests.all { it.query.containsAll(listOf("order" to "name", "direction" to "asc")) })
    }

    @Test
    fun r2BucketFailuresThatAreNotOptionalStillSurface() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets", envelope("{\"buckets\":[]}"))
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets", "{}", status = 500)
        expectError("Cloudflare request failed (500).") { api.fetchR2Buckets(account) }
    }

    @Test
    fun r2BucketCreateValidatesNamesAndSendsJurisdictionAsHeader() = runTest {
        listOf("ab", "-abc", "abc-", "ABC", "a_b", "x".repeat(64)).forEach { name ->
            assertFalse(name, CloudflareR2Jurisdictions.isValidBucketName(name))
            expectError("R2 bucket names must be 3–63 lowercase letters, numbers, or hyphens, and cannot start or end with a hyphen.") {
                api.createR2Bucket(account, CloudflareR2CreateInput(name), CloudflareMutationConfirmation(name))
            }
        }
        transport.enqueueJson(POST, "/accounts/acc/r2/buckets", envelope("{\"name\":\"media-1\"}"))
        val bucket = api.createR2Bucket(
            account,
            CloudflareR2CreateInput("media-1", jurisdiction = "eu", locationHint = null, storageClass = "InfrequentAccess"),
            CloudflareMutationConfirmation("media-1"),
        )
        assertEquals("eu", bucket.jurisdiction)
        val request = transport.requests.single()
        assertEquals("eu", request.request.headers["cf-r2-jurisdiction"])
        assertEquals("{\"name\":\"media-1\",\"storageClass\":\"InfrequentAccess\"}", request.bodyText)
    }

    @Test
    fun r2BucketDetailDeleteAndConfiguration() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets/media", envelope("{\"name\":\"media\",\"storage_class\":\"Standard\"}"))
        transport.enqueueJson(DELETE, "/accounts/acc/r2/buckets/media", "")
        transport.enqueueJson(GET, "/accounts/acc/r2/buckets/media/domains/custom", envelope("{\"domains\":[]}"))

        assertEquals("fedramp", api.fetchR2Bucket(account, "media", "fedramp").jurisdiction)
        expectError("Confirm the change to media before continuing.") {
            api.deleteR2Bucket(account, "media", "fedramp", CloudflareMutationConfirmation("other"))
        }
        api.deleteR2Bucket(account, "media", "fedramp", CloudflareMutationConfirmation("media"))
        assertEquals("fedramp", transport.requests[1].request.headers["cf-r2-jurisdiction"])
        val config = api.fetchR2BucketConfiguration(account, "media", null, CloudflareR2Configuration.CUSTOM_DOMAINS)
        assertEquals("{\n  \"domains\": []\n}", CloudflareStorageApi.prettyJson(config))
    }

    @Test
    fun r2ObjectsKeepKeyHierarchyAndRequireConfirmation() = runTest {
        transport.enqueueJson(
            GET,
            "/accounts/acc/r2/buckets/media/objects",
            envelope(
                "[{\"key\":\"photos/a.png\",\"size\":1500,\"etag\":\"e1\",\"last_modified\":\"2026-01-02T03:04:05Z\"," +
                    "\"http_metadata\":{\"contentType\":\"image/png\"}}]",
                "{\"cursor\":\"next\",\"delimited\":[\"photos/2026/\"]}",
            ),
        )
        val listing = api.listR2Objects(account, "media", null, prefix = "photos/")
        assertEquals("photos/a.png", listing.objects.single().key)
        assertEquals("image/png", listing.objects.single().contentType)
        assertEquals("next", listing.nextCursor)
        assertTrue(transport.requests.single().query.containsAll(listOf("prefix" to "photos/", "delimiter" to "/")))

        val objectPath = "/accounts/acc/r2/buckets/media/objects/photos/a%20b.png"
        transport.enqueue(GET, objectPath, FakeCloudflareRestTransport.raw(byteArrayOf(1, 2, 3), headers = mapOf("Content-Type" to listOf("image/png"))))
        transport.enqueueJson(PUT, objectPath, "")
        transport.enqueueJson(DELETE, objectPath, envelope("{}"))

        assertArrayEquals(byteArrayOf(1, 2, 3), api.downloadR2Object(account, "media", "eu", "photos/a b.png").data())
        assertEquals("eu", transport.requests.last().request.headers["cf-r2-jurisdiction"])

        expectError("Confirm the change to photos/a b.png before continuing.") {
            api.uploadR2Object(account, "media", null, "photos/a b.png", byteArrayOf(9), "image/png", CloudflareMutationConfirmation("x"))
        }
        api.uploadR2Object(account, "media", null, "photos/a b.png", byteArrayOf(9), "image/png", CloudflareMutationConfirmation("photos/a b.png"))
        val upload = transport.requests.last()
        assertEquals(PUT, upload.method)
        assertEquals("image/png", upload.request.contentType)
        assertArrayEquals(byteArrayOf(9), upload.request.bodyCopy())

        api.deleteR2Object(account, "media", null, "photos/a b.png", CloudflareMutationConfirmation("photos/a b.png"))
        assertEquals(DELETE, transport.requests.last().method)

        expectError("This object key cannot be used through the Cloudflare API. Use keys without empty, “.” or “..” path parts.") {
            api.uploadR2Object(account, "media", null, "a//b", byteArrayOf(1), null, CloudflareMutationConfirmation("a//b"))
        }
        expectError("R2 uploads from this app must be 25 MB or smaller.") {
            api.uploadR2Object(
                account,
                "media",
                null,
                "big.bin",
                ByteArray(CloudflareStorageApi.MAXIMUM_R2_UPLOAD_BYTES + 1),
                null,
                CloudflareMutationConfirmation("big.bin"),
            )
        }
    }

    // MARK: Response validation

    @Test
    fun storageEnvelopesUseIosStorageCopy() = runTest {
        transport.enqueueJson(GET, "/accounts/acc/d1/database/x", "{\"success\":false,\"errors\":[]}")
        transport.enqueueJson(DELETE, "/accounts/acc/d1/database/x", "{\"success\":false,\"errors\":[]}")
        transport.enqueueJson(DELETE, "/accounts/acc/d1/database/y", failure("Database in use", 7500))
        transport.enqueueJson(DELETE, "/accounts/acc/d1/database/z", failure("Not allowed"), status = 403)

        expectError("Cloudflare request failed (200): Cloudflare reported an unsuccessful storage request.") { api.fetchD1Database(account, "x") }
        expectError("Cloudflare request failed (200): Cloudflare reported an unsuccessful storage change.") {
            api.deleteD1Database(account, "x", CloudflareMutationConfirmation("x"))
        }
        expectError("Database in use [code 7500]") { api.deleteD1Database(account, "y", CloudflareMutationConfirmation("y")) }
        expectError("Not allowed") { api.deleteD1Database(account, "z", CloudflareMutationConfirmation("z")) }
    }

    @Test
    fun modelsDecodeLeniently() {
        val value = CloudflareKVValue("héllo".toByteArray(StandardCharsets.UTF_8), "text/plain", null)
        assertEquals("héllo", value.utf8Text)
        assertEquals(76, CloudflareKVValue(ByteArray(120), null, null).base64Text.lines().first().length)
        assertEquals("Infrequent Access", CloudflareR2Jurisdictions.storageClassLabel("InfrequentAccess"))
        assertEquals("Standard", CloudflareR2Jurisdictions.storageClassLabel(null))
        val database = CloudflareD1Database.from(ProviderJsonValue.from(mapOf("name" to 5)))
        assertEquals("", database.uuid)
        assertEquals("5", database.name)
        assertEquals("Statement completed.", CloudflareD1QueryResult.Meta.from(ProviderJsonValue.from(emptyMap<String, Any>())).summary)
        assertEquals(false, ProviderJsonValue.from(mapOf("success" to false)).bool("success"))
    }

    private suspend fun expectError(message: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected Cloudflare error but got $error", error is CloudflareOperationException)
        assertEquals(message, (error as CloudflareOperationException).userMessage)
    }
}
