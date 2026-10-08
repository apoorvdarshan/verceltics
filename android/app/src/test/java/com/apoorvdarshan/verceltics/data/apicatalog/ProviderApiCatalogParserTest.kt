package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.HostingResponseFormatException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderApiCatalogParserTest {
    private val document: String by lazy { assetFile().readText(Charsets.UTF_8) }

    @Test
    fun bundledAssetMatchesTheIosCatalogByteForByte() {
        val ios = File(assetFile().parentFile, "../../../../../ios/verceltics/Resources/ProviderAPICatalog.json").canonicalFile
        assertTrue("iOS catalog not found at $ios", ios.isFile)
        assertArrayEquals(ios.readBytes(), assetFile().readBytes())
    }

    @Test
    fun realAssetListsEveryHostingProviderAndRegistrarInBundleOrder() {
        assertEquals(
            listOf(
                "hosting.netlify", "hosting.render", "hosting.digitalOcean", "hosting.fly", "hosting.firebase",
                "hosting.heroku", "hosting.awsAmplify", "hosting.railway", "registrar.nameDotCom", "registrar.spaceship",
                "registrar.goDaddy", "registrar.porkbun", "registrar.namecheap", "registrar.dynadot", "registrar.nameSilo",
                "registrar.gandi",
            ),
            ProviderApiCatalogParser.providerIds(document),
        )
    }

    @Test
    fun realAssetParsesEveryOperation() {
        val expected = mapOf(
            "hosting.netlify" to 180, "hosting.render" to 207, "hosting.digitalOcean" to 663, "hosting.fly" to 76,
            "hosting.firebase" to 66, "hosting.heroku" to 305, "hosting.awsAmplify" to 37, "hosting.railway" to 1,
            "registrar.nameDotCom" to 72, "registrar.spaceship" to 40, "registrar.goDaddy" to 65, "registrar.porkbun" to 66,
            "registrar.namecheap" to 59, "registrar.dynadot" to 52, "registrar.nameSilo" to 42, "registrar.gandi" to 206,
        )
        expected.forEach { (id, count) ->
            val catalog = checkNotNull(ProviderApiCatalogParser.extract(document, id)) { id }
            assertEquals(id, catalog.id)
            assertEquals(id, count, catalog.operations.size)
            assertTrue(id, catalog.operations.all { it.path.startsWith("/") && it.method in ProviderApiMethods.ALL })
            assertEquals(id, catalog.operations.size, catalog.operations.map { it.id }.toSet().size)
        }
    }

    @Test
    fun lazyExtractionMatchesAFullDecode() {
        val full = ProviderApiCatalogParser.parseAll(document).associateBy { it.id }
        listOf("hosting.render", "registrar.namecheap", "hosting.firebase").forEach { id ->
            assertEquals(full[id], ProviderApiCatalogParser.extract(document, id))
        }
    }

    @Test
    fun providerMetadataAndOperationFieldsAreDecoded() {
        val netlify = checkNotNull(ProviderApiCatalogParser.extract(document, "hosting.netlify"))
        assertEquals("Netlify", netlify.title)
        assertEquals("2.57", netlify.apiVersion)
        assertEquals("https://open-api.netlify.com/swagger.json", netlify.sourceUrl)
        assertEquals("Official Netlify OpenAPI definition", netlify.sourceDescription)
        val exchange = checkNotNull(netlify.operation("exchangeTicket"))
        assertEquals("POST", exchange.method)
        assertEquals("/oauth/tickets/{ticket_id}/exchange", exchange.path)
        assertEquals(listOf("accessToken"), exchange.tags)
        val parameter = exchange.parameters.single()
        assertEquals("path:ticket_id", parameter.id)
        assertEquals(ProviderApiParameterLocation.PATH, parameter.location)
        assertTrue(parameter.required)
        assertEquals("string", parameter.type)
    }

    @Test
    fun reservedExpansionsHeaderParametersAndMultipartFieldsAreDecoded() {
        val firebase = checkNotNull(ProviderApiCatalogParser.extract(document, "hosting.firebase"))
        val populate = checkNotNull(firebase.operation("firebasehosting.projects.sites.versions.populateFiles"))
        assertEquals("/{+parent}:populateFiles", populate.path)
        assertEquals("{}", populate.bodyTemplate)
        assertTrue(populate.requestBodyRequired)

        val porkbun = checkNotNull(ProviderApiCatalogParser.extract(document, "registrar.porkbun"))
        val headers = porkbun.operation("getApiSettings")!!.parameters.filter { it.location == ProviderApiParameterLocation.HEADER }
        assertEquals(listOf("X-API-Key", "X-Secret-API-Key"), headers.map { it.name })

        val render = checkNotNull(ProviderApiCatalogParser.extract(document, "hosting.render"))
        val validate = render.operations.single { it.multipartFields.isNotEmpty() }
        assertEquals("/blueprints/validate", validate.path)
        assertEquals(listOf("multipart/form-data"), validate.contentTypes)
        val (owner, file) = validate.multipartFields
        assertEquals("ownerId", owner.name)
        assertTrue(owner.required)
        assertFalse(owner.isFile)
        assertEquals("tea-cjnxpkdhshc73d12t9i0", owner.suggestedValue)
        assertTrue(file.isFile)
        assertEquals("binary", file.format)
    }

    @Test
    fun unknownProviderIsNull() {
        assertNull(ProviderApiCatalogParser.extract(document, "hosting.vercel"))
    }

    @Test
    fun malformedDocumentsAreRejected() {
        listOf("", "[]", "{\"providers\": [", "{\"providers\": [{\"id\": \"x\"]}").forEach { text ->
            try {
                ProviderApiCatalogParser.providerIds(text)
                fail("Expected $text to be rejected")
            } catch (_: HostingResponseFormatException) {
            }
        }
    }

    @Test
    fun scannerHandlesEscapesAndKeyOrder() {
        val text = """
            {"generatedAt":"x","providers":[
              {"title":"Skip \"me\" {[","operations":[],"id":"hosting.other"},
              {"operations":[{"id":"op1","method":"get","path":"/a","tags":["T"]}],"id":"hosting.target","title":"Target"}
            ],"schemaVersion":1}
        """.trimIndent()
        assertEquals(listOf("hosting.other", "hosting.target"), ProviderApiCatalogParser.providerIds(text))
        val catalog = checkNotNull(ProviderApiCatalogParser.extract(text, "hosting.target"))
        assertEquals("Target", catalog.title)
        val operation = catalog.operations.single()
        assertEquals("op1", operation.id)
        assertEquals("GET", operation.method)
        assertEquals("GET /a", operation.summary)
    }

    @Test
    fun storeLoadsLazilyOnceAndCachesPerProvider() = runTest {
        val opens = AtomicInteger()
        val store = ProviderApiCatalogStore(
            openDocument = {
                opens.incrementAndGet()
                ByteArrayInputStream(assetFile().readBytes())
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(0, opens.get())
        val first = store.catalog("hosting.render")
        val second = store.catalog("hosting.render")
        assertSame(first, second)
        assertEquals(1, opens.get())
        store.catalog("registrar.gandi")
        assertEquals(2, opens.get())
    }

    @Test
    fun storeEvictsTheLeastRecentlyUsedCatalog() = runTest {
        val opens = AtomicInteger()
        val store = ProviderApiCatalogStore(
            openDocument = {
                opens.incrementAndGet()
                ByteArrayInputStream(assetFile().readBytes())
            },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            maximumCachedCatalogs = 1,
        )
        store.catalog("hosting.render")
        store.catalog("hosting.fly")
        store.catalog("hosting.render")
        assertEquals(3, opens.get())
    }

    @Test
    fun storeReportsIosErrorCopy() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val missing = ProviderApiCatalogStore(openDocument = { ByteArrayInputStream(assetFile().readBytes()) }, dispatcher = dispatcher)
        try {
            missing.catalog("hosting.vercel")
            fail("Expected a missing provider")
        } catch (error: ProviderApiCatalogException) {
            assertEquals("No API definition is bundled for hosting.vercel.", error.message)
        }
        val noAsset = ProviderApiCatalogStore(openDocument = { throw IOException("missing") }, dispatcher = dispatcher)
        try {
            noAsset.catalog("hosting.render")
            fail("Expected a missing bundle")
        } catch (error: ProviderApiCatalogException) {
            assertEquals("The complete provider API catalog is missing from this build.", error.message)
        }
        val corrupt = ProviderApiCatalogStore(openDocument = { ByteArrayInputStream("{\"providers\": [".toByteArray()) }, dispatcher = dispatcher)
        try {
            corrupt.catalog("hosting.render")
            fail("Expected an unreadable bundle")
        } catch (error: ProviderApiCatalogException) {
            assertEquals("The bundled API catalog could not be read.", error.message)
        }
    }

    companion object {
        fun assetFile(): File {
            val candidates = listOf(
                File("src/main/assets/ProviderAPICatalog.json"),
                File("app/src/main/assets/ProviderAPICatalog.json"),
                File("android/app/src/main/assets/ProviderAPICatalog.json"),
            )
            return candidates.firstOrNull(File::isFile)?.canonicalFile
                ?: error("ProviderAPICatalog.json asset not found from ${File(".").canonicalPath}")
        }

        val bundledDocument: String by lazy { assetFile().readText(Charsets.UTF_8) }

        fun bundled(id: String): ProviderApiCatalog = checkNotNull(ProviderApiCatalogParser.extract(bundledDocument, id))
    }
}
