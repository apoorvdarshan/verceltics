package com.apoorvdarshan.verceltics.ui

import com.apoorvdarshan.verceltics.data.vercel.VercelDeploymentEvent
import com.apoorvdarshan.verceltics.data.vercel.VercelResourceDecoder
import com.apoorvdarshan.verceltics.data.vercel.VercelResourceDecoderTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeVercelUiGatewayMappingTest {
    @Test
    fun projectsMapToCardFields() {
        val projects = VercelResourceDecoder.projectsPage(VercelResourceDecoderTest.PROJECTS_FIXTURE.encodeToByteArray())
            .projects

        val web = projects[0].withSourceScope(
            com.apoorvdarshan.verceltics.data.vercel.VercelProjectScope("team_studio", "Studio", "studio", isTeam = true),
        ).toUi()
        assertEquals("studio.example", web.primaryDomain)
        assertEquals("acme/studio-web", web.repository)
        assertEquals(VercelProjectScopeUi("Studio", "studio", isTeam = true), web.scope)
        assertEquals(VercelProjectDeploymentUi("Ship pricing refresh", 1_711_999_000_000L), web.lastDeployment)
        assertEquals("team_studio", web.teamId)
        assertEquals(1_711_999_000_000L, web.latestActivityMillis)

        val bare = projects[2].toUi()
        assertNull(bare.primaryDomain)
        assertNull(bare.scope)
        assertEquals(0L, bare.latestActivityMillis)
    }

    @Test
    fun deploymentsMapToDetailFields() {
        val deployments = VercelResourceDecoder.deployments(VercelResourceDecoderTest.DEPLOYMENTS_FIXTURE.encodeToByteArray())
            .map { it.toUi() }

        val ready = deployments[0]
        assertEquals("dpl_ready", ready.id)
        assertEquals("dpl_ready", ready.eventsIdentifier)
        assertEquals("READY", ready.state)
        assertEquals("Production", ready.target)
        assertEquals("Ship pricing refresh", ready.title)
        assertEquals("main", ready.branch)
        assertEquals("acme/studio-web", ready.repository)
        assertEquals("apoorv", ready.creator)
        assertEquals("https://vercel.com/acme/studio-web/abc", ready.inspectorUrl)

        assertEquals("Without a commit message the name is the title.", "studio-web", deployments[1].title)
        assertEquals("Deployment", deployments[2].title)
    }

    @Test
    fun eventIdsStayUniqueEvenForIdenticalEvents() {
        val duplicate = VercelDeploymentEvent("stdout", 1L, "same line", null)

        assertNotEquals(duplicate.toUi(0).id, duplicate.toUi(1).id)
        assertEquals("same line", duplicate.toUi(0).message)
    }
}
