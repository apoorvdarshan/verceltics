package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogSource
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipartField
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiOperation
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiParameter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiParameterLocation
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderApiWorkspaceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Backend(private val catalogFailure: Exception? = null) : ProviderApiBackend {
        val sent: MutableList<ProviderRawRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun loadCatalog(bundled: suspend () -> ProviderApiCatalog, forceRefresh: Boolean): Result<ProviderApiCatalog> =
            catalogFailure?.let { Result.failure(it) } ?: Result.success(bundled())

        override suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse> {
            sent += request
            return Result.success(
                ProviderRawResponse(
                    statusCode = if (request.method == "GET") 200 else 202,
                    headers = listOf("Content-Type" to "application/json", "X-Request-Id" to "req-1"),
                    body = "{\"name\":\"studio\",\"id\":\"site-1\"}",
                ),
            )
        }
    }

    private val source = ProviderApiCatalogSource { id -> catalog(id) }

    private fun show(
        profile: ProviderApiProfile = ProviderApiProfile.hosting(HostingProvider.RENDER),
        backend: Backend = Backend(),
        defaultPath: String = "/services?limit=100",
    ): ProviderApiWorkspaceController {
        val controller = ProviderApiWorkspaceController(
            profile = profile,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            backend = backend,
            savedStateHandle = SavedStateHandle(),
        )
        controller.open(defaultPath)
        composeRule.setContent {
            VercelticsTheme {
                ProviderApiWorkspace(controller = controller, onOpenLink = {}, catalogSource = source)
            }
        }
        return controller
    }

    @Test
    fun catalogShowsSummaryManualRequestAndFilters() {
        show()
        composeRule.onNodeWithTag("providerApi.workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.title").assertTextContains("Complete API")
        composeRule.onNodeWithTag("providerApi.summary").assertIsDisplayed()
        composeRule.onNodeWithText("Official Render OpenAPI definition").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.sourceLink").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.manualExplorer").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.totalCount").assertTextContains("4")

        val list = composeRule.onNodeWithTag("providerApi.catalog")
        list.performScrollToNode(hasTestTag("providerApi.operationCount"))
        composeRule.onNodeWithTag("providerApi.operationCount").assertTextContains("4")

        list.performScrollToNode(hasTestTag("providerApi.access.WRITE"))
        composeRule.onNodeWithTag("providerApi.access.WRITE").performClick()
        composeRule.onNodeWithTag("providerApi.access.WRITE").assertIsSelected()
        list.performScrollToNode(hasTestTag("providerApi.operationCount"))
        composeRule.onNodeWithTag("providerApi.operationCount").assertTextContains("2")

        list.performScrollToNode(hasTestTag("providerApi.tag.Blueprints"))
        composeRule.onNodeWithTag("providerApi.tag.Blueprints").performScrollTo().performClick()
        list.performScrollToNode(hasTestTag("providerApi.operation.validate"))
        composeRule.onNodeWithTag("providerApi.operation.validate").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("providerApi.search"))
        composeRule.onNodeWithTag("providerApi.search").performTextInput("nothing-matches")
        list.performScrollToNode(hasTestTag("providerApi.empty"))
        composeRule.onNodeWithText("No matching operations").assertIsDisplayed()
    }

    @Test
    fun operationDetailRequiresFieldsAndReviewsIntoThePrefilledExplorer() {
        val controller = show()
        val list = composeRule.onNodeWithTag("providerApi.catalog")
        list.performScrollToNode(hasTestTag("providerApi.operation.get-site"))
        composeRule.onNodeWithTag("providerApi.operation.get-site").performClick()

        composeRule.onNodeWithTag("providerApi.operationDetail").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.title").assertTextContains("Get a site")
        composeRule.onNodeWithTag("providerApi.operationPath").assertTextContains("/sites/{site_id}")
        composeRule.onNodeWithText("Attached privately by Verceltics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.review").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("FILL REQUIRED FIELDS").assertIsDisplayed()

        composeRule.onNodeWithTag("providerApi.parameterValue.path:site_id").performScrollTo().performTextInput("abc")
        composeRule.onNodeWithTag("providerApi.review").performScrollTo().assertIsEnabled().performClick()

        composeRule.onNodeWithTag("providerApi.explorer").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.path").assertTextContains("/sites/abc")
        composeRule.onNodeWithTag("providerApi.method.GET").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(ProviderApiDestination.Explorer, controller.state.value.destination)
        }
    }

    @Test
    fun readRequestsSendImmediatelyAndShowTheResponse() {
        val backend = Backend()
        show(backend = backend)
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.title").assertTextContains("Render API")
        composeRule.onNodeWithText("Full official API access").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.path").assertTextContains("/services?limit=100")
        composeRule.onNodeWithTag("providerApi.addBody").assertDoesNotExist()

        composeRule.onNodeWithTag("providerApi.send").performScrollTo().assertTextContains("SEND REQUEST").performClick()
        composeRule.waitUntil(5_000) { backend.sent.size == 1 }
        composeRule.onNodeWithTag("providerApi.response").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HTTP 200").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.responseBody").assertTextContains("\"id\": \"site-1\"", substring = true)
        composeRule.onNodeWithTag("providerApi.copy").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.responseSection.Headers").performScrollTo().performClick()
        composeRule.onNodeWithTag("providerApi.responseBody").assertTextContains("X-Request-Id: req-1", substring = true)
        composeRule.runOnIdle {
            assertEquals("GET", backend.sent.single().method)
            assertEquals("/services?limit=100", backend.sent.single().path)
        }
    }

    @Test
    fun writeRequestsNeedConfirmationBeforeSending() {
        val backend = Backend()
        show(backend = backend)
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.method.DELETE").performScrollTo().performClick()
        composeRule.onNodeWithTag("providerApi.addBody").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().assertTextContains("REVIEW WRITE REQUEST").performClick()

        composeRule.onNodeWithTag("providerApi.confirmDialog").assertIsDisplayed()
        composeRule.onNodeWithText("Send DELETE request?").assertIsDisplayed()
        composeRule.onNodeWithText("This is a real write request to Render. Confirm the path and JSON body first.").assertIsDisplayed()
        composeRule.onNode(hasText("CANCEL") and hasAnyAncestor(hasTestTag("providerApi.confirmDialog"))).performClick()
        composeRule.onNodeWithTag("providerApi.confirmDialog").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(backend.sent.isEmpty()) }

        composeRule.onNodeWithTag("providerApi.method.POST").performScrollTo().performClick()
        composeRule.onNodeWithTag("providerApi.body").performScrollTo().performTextInput("{\"name\":\"x\"}")
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().performClick()
        composeRule.onNode(hasText("SEND POST") and hasAnyAncestor(hasTestTag("providerApi.confirmDialog"))).performClick()
        composeRule.waitUntil(5_000) { backend.sent.size == 1 }
        composeRule.onNodeWithTag("providerApi.response").performScrollTo()
        composeRule.onNodeWithText("HTTP 202").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("POST", backend.sent.single().method)
            assertEquals("{\"name\":\"x\"}", backend.sent.single().body)
        }
    }

    @Test
    fun invalidHeadersAreReportedInline() {
        val backend = Backend()
        show(backend = backend)
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.headers").performScrollTo().performTextInput("not json")
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().performClick()
        composeRule.onNodeWithTag("providerApi.requestError").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Custom headers must be a JSON object whose values are strings.").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(backend.sent.isEmpty()) }
    }

    @Test
    fun registrarExplorersUseRegistrarCopyAndPurchaseConfirmation() {
        val backend = Backend()
        show(profile = ProviderApiProfile.registrar(RegistrarProvider.PORKBUN), backend = backend, defaultPath = "/domain/listAll")
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithText("Full official registrar API").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.method.POST").assertIsSelected()
        composeRule.onNodeWithTag("providerApi.body").performScrollTo().assertTextContains("{}")
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().assertTextContains("REVIEW REQUEST").performClick()
        composeRule.onNodeWithText("Send this registrar request?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This command can change a domain, create a purchase, or affect DNS. Confirm the path and request body first.",
        ).assertIsDisplayed()
    }

    @Test
    fun railwayExplorerAlwaysPostsGraphQl() {
        val backend = Backend()
        show(profile = ProviderApiProfile.hosting(HostingProvider.RAILWAY), backend = backend, defaultPath = "/graphql/v2")
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.method.POST").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("providerApi.method.GET").assertDoesNotExist()
        composeRule.onNodeWithText("Railway’s GraphQL API is always called with POST.").assertIsDisplayed()
        composeRule.onNodeWithText("GraphQL JSON body").performScrollTo().assertIsDisplayed()
        // The starter `me` query is read-only, so it sends without a confirmation.
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().assertTextContains("SEND REQUEST").performClick()
        composeRule.waitUntil(5_000) { backend.sent.size == 1 }
        composeRule.runOnIdle { assertEquals("POST", backend.sent.single().method) }

        composeRule.onNodeWithTag("providerApi.body").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("providerApi.body").performTextInput("{\"query\":\"mutation { x }\"}")
        composeRule.onNodeWithTag("providerApi.send").performScrollTo().assertTextContains("REVIEW WRITE REQUEST")
    }

    @Test
    fun multipartOperationsOfferTheOnDeviceComposer() {
        show()
        val list = composeRule.onNodeWithTag("providerApi.catalog")
        list.performScrollToNode(hasTestTag("providerApi.operation.validate"))
        composeRule.onNodeWithTag("providerApi.operation.validate").performClick()
        composeRule.onNodeWithTag("providerApi.review").performScrollTo().assertTextContains("REVIEW WRITE REQUEST").performClick()
        composeRule.onNodeWithTag("providerApi.buildMultipart").performScrollTo().performClick()

        composeRule.onNodeWithTag("providerApi.multipart").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.title").assertTextContains("Multipart Body")
        composeRule.onNodeWithText("Build the upload on-device").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.multipart.value.0").assertTextContains("tea-1")
        composeRule.onNodeWithTag("providerApi.multipart.file.1").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.multipart.compose").performScrollTo().performClick()
        composeRule.onNodeWithText("Add values for required fields: file.").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.multipart.add").performScrollTo().performClick()
        composeRule.onNodeWithTag("providerApi.multipart.remove.2").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("providerApi.explorer").assertIsDisplayed()
    }

    @Test
    fun backWalksTheStackAndClosesTheWorkspace() {
        val controller = show()
        composeRule.onNodeWithTag("providerApi.manualExplorer").performClick()
        composeRule.onNodeWithTag("providerApi.explorer").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("providerApi.catalog").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.back").performClick()
        composeRule.onNodeWithTag("providerApi.workspace").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(controller.state.value.isOpen) }
    }

    @Test
    fun catalogFailuresOfferRetry() {
        show(backend = Backend(catalogFailure = ProviderApiCatalogException.missingProvider("hosting.render")))
        composeRule.onNodeWithTag("providerApi.error").assertIsDisplayed()
        composeRule.onNodeWithText("Catalog unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("No API definition is bundled for hosting.render.").assertIsDisplayed()
        composeRule.onNodeWithTag("providerApi.retry").assertIsEnabled()
    }

    private fun catalog(id: String) = ProviderApiCatalog(
        id = id,
        title = "Render",
        apiVersion = "1",
        sourceUrl = "https://api-docs.render.com/openapi",
        sourceDescription = "Official Render OpenAPI definition",
        operations = listOf(
            operation("list-sites", "GET", "/sites", "List sites"),
            operation(
                "get-site",
                "GET",
                "/sites/{site_id}",
                "Get a site",
                parameters = listOf(
                    ProviderApiParameter("site_id", ProviderApiParameterLocation.PATH, true, "The site", "string", "", emptyList()),
                    ProviderApiParameter("Authorization", ProviderApiParameterLocation.HEADER, true, "", "string", "", emptyList()),
                ),
            ),
            operation("create-site", "POST", "/sites", "Create a site", body = "{\n  \"name\": \"\"\n}", contentTypes = listOf("application/json")),
            operation(
                "validate",
                "POST",
                "/blueprints/validate",
                "Validate a blueprint",
                tags = listOf("Blueprints"),
                contentTypes = listOf("multipart/form-data"),
                body = "{}",
                multipartFields = listOf(
                    ProviderApiMultipartField("ownerId", true, false, "string", null, null, "tea-1"),
                    ProviderApiMultipartField("file", true, true, "string", "binary", null, ""),
                ),
            ),
        ),
    )

    private fun operation(
        id: String,
        method: String,
        path: String,
        summary: String,
        tags: List<String> = listOf("Sites"),
        parameters: List<ProviderApiParameter> = emptyList(),
        body: String = "",
        contentTypes: List<String> = emptyList(),
        multipartFields: List<ProviderApiMultipartField> = emptyList(),
    ) = ProviderApiOperation(
        id = id,
        method = method,
        path = path,
        summary = summary,
        description = "",
        tags = tags,
        deprecated = false,
        parameters = parameters,
        contentTypes = contentTypes,
        requestBodyRequired = body.isNotEmpty(),
        bodyTemplate = body,
        multipartFields = multipartFields,
    )
}
