package com.apoorvdarshan.verceltics.data.cloudflare.storage

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.PUT
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareR2RulesTest {
    private val transport = FakeCloudflareRestTransport()
    private val api = CloudflareStorageApi(FakeCloudflareRestTransport.client(transport))

    @Test
    fun everyPresetIsAValidRulesDocumentForItsConfiguration() {
        val all = CloudflareR2ConfigurationPresets.cors + CloudflareR2ConfigurationPresets.lifecycle
        all.forEach { preset ->
            val document = ProviderJsonParser.parse(preset.json)
            assertNull(preset.id, CloudflareR2ConfigurationPresets.validate(preset.configuration, document))
            assertEquals(preset.clearsRules, CloudflareR2ConfigurationPresets.ruleCount(document) == 0)
            assertTrue(CloudflareR2ConfigurationPresets.parse(preset.configuration, CloudflareR2ConfigurationPresets.editorText(preset)).isSuccess)
        }
        assertTrue(CloudflareR2ConfigurationPresets.presets(CloudflareR2Configuration.CUSTOM_DOMAINS).isEmpty())
        assertTrue(CloudflareR2Configuration.CORS.isEditable)
        assertTrue(CloudflareR2Configuration.LIFECYCLE.isEditable)
        assertTrue(!CloudflareR2Configuration.CUSTOM_DOMAINS.isEditable)
        assertEquals("media/cors", CloudflareR2Configuration.CORS.resourceId("media"))
        assertEquals("media/lifecycle", CloudflareR2Configuration.LIFECYCLE.resourceId("media"))
    }

    @Test
    fun malformedRulesAreRejectedBeforeAnyRequest() {
        listOf("not json", "[]", "{}", """{"rules":{}}""", """{"rules":[1]}""").forEach { text ->
            assertNotNull(text, CloudflareR2ConfigurationPresets.parse(CloudflareR2Configuration.LIFECYCLE, text).exceptionOrNull())
        }
        assertEquals(
            "Every CORS rule needs an \"allowed\" object with methods and origins.",
            CloudflareR2ConfigurationPresets.parse(CloudflareR2Configuration.CORS, """{"rules":[{"maxAgeSeconds":1}]}""").exceptionOrNull()?.message,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun replacingCorsSendsAConfirmedPutWithTheJurisdictionHeader() = runTest {
        val document = ProviderJsonParser.parse(CloudflareR2ConfigurationPresets.cors.first().json)
        transport.enqueueJson(PUT, "/accounts/acc/r2/buckets/media/cors", envelope("{}"))

        api.replaceR2BucketConfiguration(
            "acc",
            "media",
            "eu",
            CloudflareR2Configuration.CORS,
            document,
            CloudflareMutationConfirmation("media/cors"),
        )

        val request = transport.mutations().single()
        assertEquals(PUT, request.method)
        assertEquals("/accounts/acc/r2/buckets/media/cors", request.path)
        assertEquals("eu", request.request.headers["cf-r2-jurisdiction"])
        assertEquals(document, request.bodyJson)
    }

    @Test
    fun replacingLifecycleRulesRequiresAnExactConfirmation() = runTest {
        val document = ProviderJsonParser.parse("""{"rules":[]}""")
        expectError("Confirm the change to media/lifecycle before continuing.") {
            api.replaceR2BucketConfiguration("acc", "media", null, CloudflareR2Configuration.LIFECYCLE, document, CloudflareMutationConfirmation("media"))
        }
        expectError("Custom domains can’t be replaced from this screen.") {
            api.replaceR2BucketConfiguration(
                "acc", "media", null, CloudflareR2Configuration.CUSTOM_DOMAINS, document, CloudflareMutationConfirmation("media/domains/custom"),
            )
        }
        expectError("Lifecycle rules must be a JSON object with a \"rules\" array.") {
            api.replaceR2BucketConfiguration(
                "acc", "media", null, CloudflareR2Configuration.LIFECYCLE, ProviderJsonValue.Obj(emptyMap()), CloudflareMutationConfirmation("media/lifecycle"),
            )
        }
        assertTrue(transport.requests.isEmpty())
    }

    private suspend fun expectError(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $message")
        } catch (error: CloudflareOperationException) {
            assertEquals(message, error.userMessage)
        }
    }
}
