package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.DELETE
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.GET
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.PATCH
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.POST
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.InMemoryBuildFolder
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflarePagesViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeCloudflareRestTransport()
    private val project = "/accounts/acc/pages/projects/site"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun api() = CloudflarePagesApi(
        client = FakeCloudflareRestTransport.client(transport),
        ioDispatcher = dispatcher,
        sleep = {},
        boundaryFactory = { "b" },
    )

    private fun scriptProject() {
        transport.alwaysJson(GET, project, envelope(PROJECT))
        transport.alwaysJson(GET, "$project/deployments", envelope("[${deployment("dep-1")},${deployment("dep-2")}]", "{\"total_pages\":1}"))
        transport.alwaysJson(GET, "$project/domains", envelope("[${domain("www.example.com")}]", "{\"total_pages\":1}"))
    }

    @Test
    fun detailLoadsAndDeletesOnlyAfterConfirmation() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesProjectDetailViewModel(api(), "acc", "site")
        advanceUntilIdle()
        assertEquals("site", model.state.value.project?.name)
        assertEquals(listOf("dep-1", "dep-2"), model.state.value.deployments.map { it.id })

        val target = model.state.value.deployments.first()
        model.requestDelete(target)
        advanceUntilIdle()
        val prompt = model.confirmation.value!!
        assertEquals("Delete this deployment?", prompt.title)
        assertEquals("Deployment dep-1 of site will be permanently removed from Cloudflare Pages.", prompt.message)
        assertEquals("Delete Deployment", prompt.confirmLabel)
        assertTrue(prompt.destructive)
        assertTrue(transport.mutations().isEmpty())

        model.dismissPendingMutation()
        advanceUntilIdle()
        assertTrue(transport.mutations().isEmpty())

        transport.enqueueJson(DELETE, "$project/deployments/dep-1", envelope("null"))
        model.requestDelete(target)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("DELETE $project/deployments/dep-1"), transport.mutations().map { it.toString() })
        assertEquals(CloudflareActionBanner("Deployment deleted.", isError = false), model.banner.value)
    }

    @Test
    fun rollbackIsDestructiveAndRetryIsNot() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesProjectDetailViewModel(api(), "acc", "site")
        advanceUntilIdle()
        val target = model.state.value.deployments.first()

        model.requestRollback(target)
        assertEquals("Roll production back?", model.confirmation.value?.title)
        assertEquals("Cloudflare will make deployment dep-1 the active production version of site.", model.confirmation.value?.message)
        assertTrue(model.confirmation.value!!.destructive)
        model.dismissPendingMutation()

        model.requestRetry(target)
        assertFalse(model.confirmation.value!!.destructive)
        transport.enqueueJson(POST, "$project/deployments/dep-1/retry", envelope(deployment("dep-3")))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("POST $project/deployments/dep-1/retry"), transport.mutations().map { it.toString() })
        assertEquals("Deployment retry started.", model.banner.value?.message)
    }

    @Test
    fun failedMutationsSurfaceProviderErrors() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesProjectDetailViewModel(api(), "acc", "site")
        advanceUntilIdle()
        transport.enqueueJson(POST, "$project/deployments/dep-1/rollback", failure("Deployment is not production"), status = 400)
        model.requestRollback(model.state.value.deployments.first())
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(
            CloudflareActionBanner("Cloudflare request failed (400): Deployment is not production", isError = true),
            model.banner.value,
        )
    }

    @Test
    fun environmentFilterReloadsDeploymentsWithEnvQuery() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesProjectDetailViewModel(api(), "acc", "site")
        advanceUntilIdle()
        model.selectEnvironment(CloudflarePagesEnvironment.PREVIEW)
        advanceUntilIdle()
        assertTrue(transport.requests.last { it.path == "$project/deployments" }.query.contains("env" to "preview"))
        assertEquals(CloudflarePagesEnvironment.PREVIEW, model.state.value.environmentFilter)
    }

    @Test
    fun operationsDeleteProjectRequiresConfirmationAndFlagsDeletion() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        assertEquals(listOf("www.example.com"), model.state.value.domains.map { it.name })

        model.requestDeleteProject()
        val prompt = model.confirmation.value!!
        assertEquals("Delete this Pages project?", prompt.title)
        assertEquals("site, its deployments and Pages configuration will be permanently removed. This cannot be undone.", prompt.message)
        assertTrue(prompt.destructive)
        assertEquals(project, prompt.resourceId)
        assertTrue(transport.mutations().isEmpty())

        transport.enqueueJson(DELETE, project, envelope("null"))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("DELETE $project"), transport.mutations().map { it.toString() })
        assertTrue(model.state.value.didDeleteProject)
    }

    @Test
    fun addDomainValidatesBeforePromptingAndClosesSheetAfterSuccess() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        model.openAddDomain()
        model.requestAddDomain("bad host")
        assertEquals("Enter a valid hostname such as www.example.com.", model.state.value.addDomainError)
        assertNull(model.confirmation.value)

        model.requestAddDomain("Docs.Example.com")
        assertEquals("Add docs.example.com?", model.confirmation.value?.title)
        assertEquals("$project/domains", model.confirmation.value?.resourceId)
        transport.enqueueJson(POST, "$project/domains", envelope(domain("docs.example.com")))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals("{\"name\":\"docs.example.com\"}", transport.mutations().single().bodyText)
        assertFalse(model.state.value.isAddingDomain)
        assertEquals("Domain added. Cloudflare is validating it.", model.banner.value?.message)
    }

    @Test
    fun domainRetryAndRemovalAreConfirmedWithDomainCopy() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        val domain = model.state.value.domains.single()

        model.requestDeleteDomain(domain)
        assertEquals("Remove www.example.com?", model.confirmation.value?.title)
        assertEquals("Cloudflare will detach www.example.com and stop serving it from this project.", model.confirmation.value?.message)
        assertTrue(model.confirmation.value!!.destructive)
        transport.enqueueJson(DELETE, "$project/domains/www.example.com", envelope("null"))
        model.confirmPendingMutation()
        advanceUntilIdle()

        model.requestRetryValidation(domain)
        transport.enqueueJson(PATCH, "$project/domains/www.example.com", envelope(domain("www.example.com")))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(
            listOf("DELETE $project/domains/www.example.com", "PATCH $project/domains/www.example.com"),
            transport.mutations().map { it.toString() },
        )
    }

    @Test
    fun settingsSaveSendsPatchOnlyAfterConfirmationAndClosesEditor() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        model.openEditor()
        val draft = model.state.value.editorDraft!!
        model.updateDraft(draft.copy(productionBranch = ""))
        model.requestSave()
        assertEquals("Production branch cannot be empty.", model.state.value.editorError)
        assertNull(model.confirmation.value)

        model.updateDraft(draft.copy(productionBranch = "release"))
        model.requestSave()
        val prompt = model.confirmation.value!!
        assertEquals("Save project settings?", prompt.title)
        assertTrue(prompt.message.contains("production branch “release”"))
        assertTrue(prompt.message.contains("Secret variables and Analytics credentials are not changed."))
        assertTrue(transport.mutations().isEmpty())

        transport.enqueueJson(PATCH, project, envelope(PROJECT))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals("PATCH $project", transport.mutations().single().toString())
        assertNull(model.state.value.editorDraft)
        assertEquals("Project settings saved.", model.banner.value?.message)
    }

    @Test
    fun purgeAndRedeployUseIosResources() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        model.requestPurgeBuildCache()
        assertEquals("$project/purge_build_cache", model.confirmation.value?.resourceId)
        assertFalse(model.confirmation.value!!.destructive)
        transport.enqueueJson(POST, "$project/purge_build_cache", envelope("null"))
        model.confirmPendingMutation()
        advanceUntilIdle()

        model.requestRedeploy()
        assertEquals("latest-1", model.confirmation.value?.resourceId)
        transport.enqueueJson(POST, "$project/deployments/latest-1/retry", envelope(deployment("dep-9")))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(
            listOf("POST $project/purge_build_cache", "POST $project/deployments/latest-1/retry"),
            transport.mutations().map { it.toString() },
        )
    }

    @Test
    fun directUploadAsksFirstThenRunsThePipelineWithProgress() = runTest(dispatcher) {
        scriptProject()
        val model = CloudflarePagesOperationsViewModel(api(), "acc", "site")
        advanceUntilIdle()
        model.openUpload()
        model.updateUploadDraft(model.state.value.uploadDraft!!.copy(environment = CloudflarePagesEnvironment.PREVIEW, previewBranch = " "))
        assertFalse(model.validateUploadDraft())
        assertEquals("Enter a branch name for the preview deployment.", model.state.value.uploadDraftError)

        model.updateUploadDraft(model.state.value.uploadDraft!!.copy(previewBranch = "feature"))
        assertTrue(model.validateUploadDraft())
        val folder = InMemoryBuildFolder(mapOf("index.html" to "hello"))
        model.requestUpload(folder, "dist")
        val prompt = model.confirmation.value!!
        assertEquals("Deploy this folder to preview?", prompt.title)
        assertEquals(
            "Verceltics will upload the prebuilt folder “dist” and create a new preview deployment of site on branch “feature”. " +
                "Only assets Cloudflare does not already have are uploaded.",
            prompt.message,
        )
        assertEquals("$project/deployments", prompt.resourceId)
        assertNull(model.state.value.uploadDraft)
        assertTrue(transport.requests.none { it.path.contains("upload-token") })
        assertTrue(folder.reads.isEmpty())

        val jwt = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()) + ".s"
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"$jwt\"}"))
        transport.enqueueJson(POST, "/pages/assets/check-missing", envelope("[]"))
        transport.enqueueJson(POST, "/pages/assets/upsert-hashes", envelope("true"))
        transport.enqueueJson(POST, "$project/deployments", envelope(deployment("deployment-new")))
        model.confirmPendingMutation()
        advanceUntilIdle()

        assertEquals("Deployment deploym created with 1 files · 1 already on Cloudflare.", model.banner.value?.message)
        assertNull(model.state.value.uploadProgress)
        val deploy = transport.requests.last { it.path == "$project/deployments" && it.method == POST }
        assertTrue(String(deploy.request.bodyCopy()!!).contains("name=\"branch\"\r\n\r\nfeature\r\n"))
    }

    @Test
    fun deploymentDetailDeletesAfterConfirmationAndMarksDeleted() = runTest(dispatcher) {
        transport.alwaysJson(GET, "$project/deployments/dep-1", envelope(deployment("dep-1")))
        transport.alwaysJson(GET, "$project/deployments/dep-1/history/logs", envelope("{\"data\":[{\"line\":\"ok\",\"ts\":\"2026-01-01T00:00:00Z\"}]}"))
        val model = CloudflarePagesDeploymentDetailViewModel(api(), "acc", "site", "dep-1")
        advanceUntilIdle()
        assertEquals(listOf("ok"), model.state.value.logs.map { it.line })

        model.requestDelete()
        assertTrue(model.confirmation.value!!.destructive)
        assertTrue(transport.mutations().isEmpty())
        transport.enqueueJson(DELETE, "$project/deployments/dep-1", envelope("null"))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertTrue(model.state.value.deleted)
        assertEquals(listOf("DELETE $project/deployments/dep-1"), transport.mutations().map { it.toString() })
    }

    private fun domain(name: String) = "{\"id\":\"id-$name\",\"name\":\"$name\",\"status\":\"active\"}"

    private fun deployment(id: String) =
        "{\"id\":\"$id\",\"short_id\":\"${id.take(7)}\",\"environment\":\"preview\",\"latest_stage\":{\"status\":\"success\"}}"

    companion object {
        private val PROJECT = """
            {"id":"p1","name":"site","subdomain":"site.pages.dev","domains":[],"production_branch":"main",
             "latest_deployment":{"id":"latest-1","short_id":"latest","environment":"production","latest_stage":{"status":"success"}},
             "build_config":{"build_command":"npm run build","destination_dir":"dist","root_dir":"","build_caching":false}}
        """.trimIndent()
    }
}
