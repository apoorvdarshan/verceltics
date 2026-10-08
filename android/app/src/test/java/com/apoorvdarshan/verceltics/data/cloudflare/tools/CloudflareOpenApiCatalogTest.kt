package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class CloudflareOpenApiCatalogTest {
    companion object {
        private lateinit var realCatalog: CloudflareOpenApiCatalog

        @BeforeClass
        @JvmStatic
        fun loadBundledAsset() {
            realCatalog = CloudflareOpenApiCatalogStore { assetFile(CloudflareOpenApiCatalogStore.ASSET_NAME).inputStream() }.load()
        }

        fun assetFile(name: String): File = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
    }

    @Test
    fun bundledAssetParsesEveryOperation() {
        assertEquals(1, realCatalog.schemaVersion)
        assertEquals("3.0.3", realCatalog.openApiVersion)
        assertEquals("https://github.com/cloudflare/api-schemas", realCatalog.sourceUrl)
        assertEquals(40, realCatalog.sourceCommit.length)
        assertEquals(3_324, realCatalog.operationCount)
        assertEquals(realCatalog.operationCount, realCatalog.operations.size)
        assertEquals(realCatalog.operations.size, realCatalog.operations.map { it.id }.toSet().size)
        assertEquals(536, realCatalog.tagCount)
        assertTrue(realCatalog.pathCount in 1..realCatalog.operationCount)
        assertEquals(
            setOf(CloudflareHttpMethod.GET, CloudflareHttpMethod.POST, CloudflareHttpMethod.PUT, CloudflareHttpMethod.PATCH, CloudflareHttpMethod.DELETE),
            realCatalog.operations.map { it.method }.toSet(),
        )
        assertEquals(1_599, realCatalog.operations.count { it.method == CloudflareHttpMethod.GET })
        assertTrue(realCatalog.operations.all { it.tags.isNotEmpty() })
        assertTrue(realCatalog.operations.any { it.isMultipart && it.multipartFields.any(CloudflareMultipartFieldSpec::isFile) })
    }

    @Test
    fun bundledLicenseShipsWithTheCatalog() {
        val license = assetFile(CloudflareOpenApiCatalogStore.LICENSE_ASSET_NAME).readText()
        assertTrue(license.startsWith("BSD 3-Clause License"))
        assertTrue(license.contains("Copyright (c) 2022, Cloudflare"))
    }

    @Test
    fun realCatalogKnownOperationsKeepPermissionsAndParameters() {
        val members = realCatalog.operation("account-members-list-members")!!
        assertEquals(CloudflareHttpMethod.GET, members.method)
        assertEquals("/accounts/{account_id}/members", members.path)
        assertTrue("Account Settings Read" in members.permissions)
        assertTrue(members.parameters.any { it.location == CloudflareOpenApiParameterLocation.PATH && it.name == "account_id" })
        assertNull(realCatalog.operation("does-not-exist"))
    }

    @Test
    fun everyGeneratedRequestNormalizesOntoTheApiOrigin() {
        realCatalog.operations.forEach { operation ->
            val values = CloudflareOperationRequestBuilder.initialValues(operation, "acc-123", "zone-456")
            val preset = CloudflareOperationRequestBuilder.preset(
                operation,
                values,
                operation.bodyTemplate,
                CloudflareOperationRequestBuilder.initialContentType(operation),
            )
            val path = CloudflareExplorerRequestBuilder.normalizePath(preset.path)
            val uri = CloudflareExplorerRequestBuilder.buildUri(path)
            assertEquals(operation.id, "api.cloudflare.com", uri.host)
            assertFalse(operation.id, preset.path.contains('{'))
        }
    }

    @Test
    fun searchRequiresEveryTermAndRespectsTheReadWriteFilter() {
        val dns = realCatalog.search("dns records", CloudflareOperationFilter.ALL)
        assertTrue(dns.isNotEmpty())
        assertTrue(dns.all { op -> listOf("dns", "records").all { term -> searchable(op).contains(term) } })
        val writes = realCatalog.search("dns records", CloudflareOperationFilter.WRITE)
        assertTrue(writes.isNotEmpty() && writes.all { it.isMutation })
        val reads = realCatalog.search("dns records", CloudflareOperationFilter.READ)
        assertTrue(reads.isNotEmpty() && reads.none { it.isMutation })
        assertEquals(dns.size, writes.size + reads.size)
        assertTrue(realCatalog.search("zzqq-not-a-cloudflare-term", CloudflareOperationFilter.ALL).isEmpty())
        assertTrue(realCatalog.search("Account Settings Read", CloudflareOperationFilter.ALL).any { it.id == "account-members-list-members" })
        assertEquals(realCatalog.operations.size, realCatalog.search("   ", CloudflareOperationFilter.ALL).size)
    }

    @Test
    fun tagDirectoryIsGroupedByPrimaryTagAndSorted() {
        val tags = realCatalog.tagSummaries
        assertEquals(realCatalog.operations.map { it.primaryTag }.toSet().size, tags.size)
        assertEquals(realCatalog.operations.size, tags.sumOf { it.operationCount })
        tags.zipWithNext().forEach { (first, second) ->
            assertTrue(
                first.operationCount > second.operationCount ||
                    (first.operationCount == second.operationCount && first.name.compareTo(second.name, ignoreCase = true) <= 0),
            )
        }
        val top = tags.first()
        assertEquals(top.operationCount, realCatalog.operationsForTag(top.name).size)
        assertEquals(top.writeCount, realCatalog.operationsForTag(top.name).count { it.isMutation })
        assertTrue(realCatalog.visibleTags(CloudflareOperationFilter.WRITE).all { it.writeCount > 0 })
        assertTrue(realCatalog.visibleTags(CloudflareOperationFilter.READ).all { it.operationCount > it.writeCount })
        assertEquals(tags, realCatalog.visibleTags(CloudflareOperationFilter.ALL))
    }

    @Test
    fun syntheticCatalogParsesValuesAndBuildsExplorerPresets() {
        val catalog = CloudflareOpenApiCatalogParser.parse(SAMPLE)
        assertEquals(2, catalog.operations.size)
        val operation = catalog.operation("kv-write")!!
        assertTrue(operation.deprecated)
        assertFalse(operation.supportsGlobalKey)
        assertTrue(operation.isMultipart)
        val parameters = operation.parameters.associateBy { it.key }
        assertEquals("30", parameters.getValue("query:ttl").suggestedValue)
        assertEquals("ex", parameters.getValue("query:mode").suggestedValue)
        assertEquals(listOf("first", "second", "true", "3"), parameters.getValue("header:X-Mode").enumOptions)
        assertEquals("first", parameters.getValue("header:X-Mode").suggestedValue)
        assertEquals("[\"a\",1]", parameters.getValue("query:list").suggestedValue)
        assertEquals("", parameters.getValue("path:key_name").suggestedValue)
        assertEquals("int32", parameters.getValue("query:ttl").typeLabel)
        assertEquals(1.0, parameters.getValue("query:ttl").minimum!!, 0.0)
        assertEquals(listOf("metadata", "value"), operation.multipartFields.map { it.name })
        assertTrue(operation.multipartFields.last().isFile)
        assertEquals("{}", operation.multipartFields.first().suggestedValue)

        val values = CloudflareOperationRequestBuilder.initialValues(operation, "acc", "zone-1")
        assertEquals("acc", values["path:account_id"])
        assertEquals("zone-1", values["path:zone_identifier"])
        assertEquals("multipart/form-data", CloudflareOperationRequestBuilder.initialContentType(operation))

        val preset = CloudflareOperationRequestBuilder.preset(
            operation,
            values + mapOf("path:key_name" to "a/b c%", "query:mode" to "", "header:X-Mode" to "second"),
            body = "",
            contentType = "multipart/form-data",
        )
        assertEquals("/accounts/acc/zones/zone-1/values/a%2Fb%20c%25", preset.path)
        assertEquals("ttl=30\nlist=[\"a\",1]", preset.query)
        assertEquals("X-Mode: second", preset.headers)
        assertEquals(operation.summary, preset.title)
        assertEquals(operation.description, preset.summary)
        assertEquals(CloudflareHttpMethod.PUT, preset.method)
        assertEquals(operation.multipartFields, preset.multipartFields)
        assertEquals(listOf("Workers KV Storage Write"), preset.permissions)
        assertEquals("/accounts/acc/zones/zone-1/values/a%2Fb%20c%25", CloudflareExplorerRequestBuilder.normalizePath(preset.path))

        val empty = CloudflareOperationRequestBuilder.preset(operation, emptyMap(), "", "multipart/form-data")
        assertEquals("/accounts/ACCOUNT_ID/zones/ZONE_IDENTIFIER/values/KEY_NAME", empty.path)

        val read = catalog.operation("list")!!
        assertEquals("/list", read.summary)
        assertEquals("application/json", CloudflareOperationRequestBuilder.initialContentType(read))
        assertEquals("/list", CloudflareOperationRequestBuilder.preset(read, emptyMap(), "", "application/json").summary)
    }

    @Test
    fun catalogTextMatchesIos() {
        assertEquals("x", CloudflareCatalogText.of(ProviderJsonValue.Str("x")))
        assertEquals("1.5", CloudflareCatalogText.of(ProviderJsonValue.Num.parse("1.5")))
        assertEquals("false", CloudflareCatalogText.of(ProviderJsonValue.Bool(false)))
        assertEquals("", CloudflareCatalogText.of(ProviderJsonValue.Null))
        assertEquals("{\"a\":[1]}", CloudflareCatalogText.of(ProviderJsonValue.from(mapOf("a" to listOf(1)))))
    }

    @Test
    fun storeLoadsOnceAndReportsUnreadableCatalogs() {
        var opens = 0
        val store = CloudflareOpenApiCatalogStore {
            opens += 1
            ByteArrayInputStream(SAMPLE.toByteArray())
        }
        assertNull(store.loadedCatalog)
        val first = store.load()
        assertSame(first, store.load())
        assertSame(first, store.loadedCatalog)
        assertEquals(1, opens)

        val broken = CloudflareOpenApiCatalogStore { ByteArrayInputStream("{\"operations\":".toByteArray()) }
        val error = assertThrows(CloudflareToolsException::class.java) { broken.load() }
        assertEquals("The bundled Cloudflare API catalog could not be read.", error.message)
        val notObject = CloudflareOpenApiCatalogStore { ByteArrayInputStream("[]".toByteArray()) }
        assertEquals(
            "The bundled Cloudflare API catalog is not a JSON object.",
            assertThrows(CloudflareToolsException::class.java) { notObject.load() }.message,
        )
    }

    @Test
    fun operationFilterMatchesIos() {
        val catalog = CloudflareOpenApiCatalogParser.parse(SAMPLE)
        val write = catalog.operation("kv-write")!!
        val read = catalog.operation("list")!!
        assertTrue(CloudflareOperationFilter.ALL.includes(write) && CloudflareOperationFilter.ALL.includes(read))
        assertTrue(CloudflareOperationFilter.WRITE.includes(write) && !CloudflareOperationFilter.WRITE.includes(read))
        assertTrue(CloudflareOperationFilter.READ.includes(read) && !CloudflareOperationFilter.READ.includes(write))
        assertTrue(write.matches("kv WRITE values"))
        assertFalse(write.matches("kv delete"))
    }

    private fun searchable(operation: CloudflareOpenApiOperation): String =
        (listOf(operation.summary, operation.description, operation.path, operation.method.name, operation.id) + operation.tags + operation.permissions)
            .joinToString(" ")
            .lowercase()

    private val SAMPLE = """
        {"schemaVersion":1,"openAPIVersion":"3.0.3","apiVersion":"4.0.0","sourceCommit":"abc","sourceURL":"https://example.com","operationCount":3,
         "operations":[
          {"id":"kv-write","method":"PUT","path":"/accounts/{account_id}/zones/{zone_identifier}/values/{key_name}","summary":"Write KV value","description":"Writes values.",
           "tags":["Workers KV"],"deprecated":true,"permissions":["Workers KV Storage Write"],"supportsGlobalKey":false,"supportsAPIToken":true,"supportsUserServiceKey":false,
           "parameters":[
             {"name":"account_id","location":"path","required":true,"description":"Account","type":"string","example":"ignored"},
             {"name":"zone_identifier","location":"path","required":true,"description":"Zone","type":"string"},
             {"name":"key_name","location":"path","required":true,"description":"Key","type":"string"},
             {"name":"ttl","location":"query","required":false,"description":"TTL","type":"integer","format":"int32","default":30,"example":60,"minimum":1},
             {"name":"mode","location":"query","required":false,"description":"","type":"string","example":"ex","enumValues":["a"]},
             {"name":"X-Mode","location":"header","required":false,"description":"","type":"string","enumValues":["first","second",true,3]},
             {"name":"list","location":"query","required":false,"description":"","type":"array","example":["a",1]},
             {"name":"bad","location":"cookie","required":false,"description":""}
           ],
           "contentTypes":["multipart/form-data"],"requestBodyRequired":true,"bodyTemplate":"",
           "multipartFields":[{"name":"metadata","required":true,"isFile":false,"type":"string","example":{}},{"name":"value","required":true,"isFile":true,"type":"string","format":"binary"}]},
          {"id":"list","method":"GET","path":"/list","summary":"","description":"","tags":[],"deprecated":false,"permissions":[],"supportsGlobalKey":true,"supportsAPIToken":true,"supportsUserServiceKey":false,"parameters":[],"contentTypes":[],"requestBodyRequired":false,"bodyTemplate":"","multipartFields":[]},
          {"id":"broken","method":"TRACE","path":"/x"}
         ]}
    """.trimIndent()
}
