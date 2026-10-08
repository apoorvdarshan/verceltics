package com.apoorvdarshan.verceltics.data.vercel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelResourceDecoderTest {
    @Test
    fun projectsPageKeepsDomainsDeploymentsRepositoryAndCursor() {
        val page = VercelResourceDecoder.projectsPage(PROJECTS_FIXTURE.encodeToByteArray())

        assertEquals("1712000000000", page.nextCursor)
        assertEquals(3, page.projects.size)

        val web = page.projects[0]
        assertEquals("prj_web", web.id)
        assertEquals("studio-web", web.name)
        assertEquals("nextjs", web.framework)
        assertEquals("team_studio", web.teamId)
        assertEquals("team_studio", web.accountId)
        assertEquals(1_711_900_000_000L, web.updatedAtMillis)
        assertEquals("acme/studio-web", web.link?.fullName)
        assertEquals(2, web.latestDeployments.size)
        assertEquals("Ship pricing refresh", web.lastDeployment?.commitMessage)
        assertEquals(1_711_999_000_000L, web.lastDeployment?.createdAtMillis)
        assertEquals(listOf("studio-web.vercel.app", "studio.example"), web.productionAliases)
        assertEquals("studio.example", web.primaryDomain)
        assertFalse(web.needsPrimaryDomainRefresh)

        val docs = page.projects[1]
        assertNull("A personal account id is not a team scope.", docs.teamId)
        assertEquals("user_123", docs.accountId)
        assertEquals("docs-a1b2.vercel.app", docs.primaryDomain)
        assertTrue(docs.needsPrimaryDomainRefresh)
        assertNull(docs.link?.fullName)

        val bare = page.projects[2]
        assertNull(bare.primaryDomain)
        assertTrue(bare.needsPrimaryDomainRefresh)
        assertNull(bare.lastDeployment)
    }

    @Test
    fun paginationWithoutNextEndsTheListing() {
        val page = VercelResourceDecoder.projectsPage(
            """{"projects":[{"id":"prj_1","name":"app","accountId":"team_123"}],"pagination":{"next":null}}"""
                .encodeToByteArray(),
        )

        assertNull(page.nextCursor)
        assertEquals("team_123", page.projects.single().teamId)
    }

    @Test
    fun primaryDomainPrefersCustomDomainsThenShortestVercelAlias() {
        val project = VercelResourceDecoder.project(
            """
            {
              "id":"prj_1","name":"app",
              "latestDeployments":[{"createdAt":1,"alias":["app-git-main-acme.vercel.app","APP.vercel.app"]}],
              "alias":[{"domain":"app.vercel.app"},"shop.example"],
              "customEnvironments":[{"type":"production","domains":[
                {"name":"old.example","redirect":"shop.example"},
                {"name":"www.shop.example","redirect":null}
              ],"currentDeploymentAliases":["app-prod.vercel.app"]}]
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals("shop.example", project.primaryDomain)
        assertEquals(listOf("www.shop.example", "old.example"), project.customEnvironments.single().preferredDomains)

        val vercelOnly = project.copy(aliasDomains = emptyList(), customEnvironments = emptyList())
        assertEquals("Shortest alias wins and case duplicates collapse.", "APP.vercel.app", vercelOnly.primaryDomain)
        assertTrue(vercelOnly.needsPrimaryDomainRefresh)
        assertEquals("custom.example", vercelOnly.withAdditionalDomains(listOf("custom.example")).primaryDomain)
    }

    @Test
    fun projectDetailAndDomainsDecode() {
        val detail = VercelResourceDecoder.project(
            """{"id":"prj_web","name":"studio-web","framework":"astro","link":{"type":"github","org":"acme","repo":"web"},"env":[{"key":"SECRET","value":"x"}]}"""
                .encodeToByteArray(),
        )
        assertEquals("astro", detail.framework)
        assertEquals("acme/web", detail.link?.fullName)

        val domains = VercelResourceDecoder.projectDomains(DOMAINS_FIXTURE.encodeToByteArray())
        assertEquals(listOf("studio.example", "www.studio.example", "unverified.example", "studio-web.vercel.app"), domains.map { it.name })
        assertEquals(
            listOf("studio.example", "studio-web.vercel.app"),
            domains.filter(VercelProjectDomain::isServing).map(VercelProjectDomain::name),
        )
    }

    @Test
    fun deploymentsDecodeMetadataStatesAndTimestamps() {
        val deployments = VercelResourceDecoder.deployments(DEPLOYMENTS_FIXTURE.encodeToByteArray())

        assertEquals(3, deployments.size)
        val ready = deployments[0]
        assertEquals("dpl_ready", ready.stableId)
        assertEquals("dpl_ready", ready.eventsIdentifier)
        assertEquals("READY", ready.displayState)
        assertEquals("Production", ready.displayTarget)
        assertEquals(1_712_000_000_000L, ready.createdAtMillis)
        assertEquals("Ship pricing refresh", ready.meta.commitMessage)
        assertEquals("main", ready.meta.commitRef)
        assertEquals("acme/studio-web", ready.meta.repository)
        assertEquals("apoorv", ready.creatorUsername)
        assertEquals("https://vercel.com/acme/studio-web/abc", ready.inspectorUrl)

        val building = deployments[1]
        assertEquals("Falls back to readyState.", "BUILDING", building.displayState)
        assertEquals("A null target is a preview.", "Preview", building.displayTarget)
        assertEquals("Seconds are normalized to milliseconds.", 1_712_000_100_000L, building.createdAtMillis)
        assertEquals("studio-web-git-feature.vercel.app", building.eventsIdentifier)
        assertNull(building.meta.repository)

        val unknown = deployments[2]
        assertEquals("UNKNOWN", unknown.displayState)
        assertNull(unknown.eventsIdentifier)
        assertEquals("deployment-0", unknown.stableId)
    }

    @Test
    fun deploymentEventsFollowIosMessagePrecedence() {
        val events = VercelResourceDecoder.deploymentEvents(EVENTS_FIXTURE.encodeToByteArray())

        assertEquals(6, events.size)
        assertEquals("stdout", events[0].type)
        assertEquals("Text is trimmed.", "Running build in Washington, D.C.", events[0].message)
        assertEquals(1_712_000_001_000L, events[0].createdAtMillis)
        assertEquals("Info fields join in order.", "build bld_123 .", events[1].message)
        assertEquals("READY", events[2].message)
        assertEquals("HTTP 502", events[3].message)
        assertEquals("502", events[3].statusCode)
        assertEquals("Missing type and payload fall back.", "event", events[4].message)
        assertEquals(0L, events[4].createdAtMillis)
        assertEquals("Seconds are normalized.", 1_712_000_002_000L, events[5].createdAtMillis)
        assertEquals("command", events[5].message)
    }

    @Test
    fun deploymentEventsAcceptNullAndEnvelopeAndRejectGarbage() {
        assertTrue(VercelResourceDecoder.deploymentEvents("null".encodeToByteArray()).isEmpty())
        assertEquals(
            "hi",
            VercelResourceDecoder.deploymentEvents(
                """{"events":[{"type":"stdout","created":1,"payload":{"text":"hi"}}]}""".encodeToByteArray(),
            ).single().message,
        )
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.deploymentEvents("\"nope\"".encodeToByteArray())
        }
    }

    @Test
    fun requiredIdentifiersAndCollectionsAreValidated() {
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.projectsPage("""{"pagination":{}}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.projectsPage("""{"projects":[{"name":"no-id"}]}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.project("""{"id":"prj"}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.projectDomains("""{}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.deployments("""{"deployments":{}}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.projectsPage("""{"projects":[}""".encodeToByteArray())
        }
        assertThrows(VercelResponseFormatException::class.java) {
            VercelResourceDecoder.projectsPage("""{"projects":[]} trailing""".encodeToByteArray())
        }
    }

    @Test
    fun unexpectedFieldShapesAreIgnoredInsteadOfFailingTheList() {
        val page = VercelResourceDecoder.projectsPage(
            """
            {"projects":[{"id":"prj","name":"app","latestDeployments":"oops","alias":{"domain":"x"},
              "targets":[],"link":"github","customEnvironments":[1,{"type":"production","domains":"x"}]}]}
            """.trimIndent().encodeToByteArray(),
        )

        val project = page.projects.single()
        assertTrue(project.latestDeployments.isEmpty())
        assertTrue(project.aliasDomains.isEmpty())
        assertNull(project.link)
        assertEquals(1, project.customEnvironments.size)
        assertNull(project.primaryDomain)
    }

    companion object {
        val PROJECTS_FIXTURE = """
            {
              "projects": [
                {
                  "accountId": "team_studio",
                  "id": "prj_web",
                  "name": "studio-web",
                  "framework": "nextjs",
                  "createdAt": 1690000000000,
                  "updatedAt": 1711900000000,
                  "nodeVersion": "20.x",
                  "link": {"type": "github", "org": "acme", "repo": "studio-web", "repoId": 123456, "productionBranch": "main"},
                  "latestDeployments": [
                    {
                      "id": "dpl_1",
                      "alias": ["studio-web-acme.vercel.app"],
                      "createdAt": 1711999000000,
                      "readyState": "READY",
                      "target": "production",
                      "meta": {"githubCommitMessage": "Ship pricing refresh", "githubCommitSha": "abc123"}
                    },
                    {"id": "dpl_0", "createdAt": 1711000000000, "alias": []}
                  ],
                  "targets": {"production": {"id": "dpl_1", "alias": ["studio-web.vercel.app", "studio.example"]}},
                  "env": [{"key": "API_KEY", "type": "encrypted", "value": "redacted"}]
                },
                {
                  "accountId": "user_123",
                  "id": "prj_docs",
                  "name": "docs",
                  "framework": "astro",
                  "updatedAt": 1711800000000,
                  "link": {"type": "gitlab", "repo": "docs"},
                  "latestDeployments": [{"createdAt": 1711700000000, "alias": ["docs-a1b2.vercel.app", "docs-git-main-apoorv.vercel.app"]}]
                },
                {"id": "prj_bare", "name": "bare", "framework": null}
              ],
              "pagination": {"count": 3, "next": 1712000000000, "prev": null}
            }
        """.trimIndent()

        val DOMAINS_FIXTURE = """
            {
              "domains": [
                {"name": "studio.example", "apexName": "studio.example", "projectId": "prj_web", "redirect": null, "redirectStatusCode": null, "verified": true},
                {"name": "www.studio.example", "redirect": "studio.example", "redirectStatusCode": 308, "verified": true},
                {"name": "unverified.example", "redirect": null, "verified": false},
                {"name": "studio-web.vercel.app"},
                {"apexName": "missing-name.example"}
              ],
              "pagination": {"count": 4, "next": null}
            }
        """.trimIndent()

        val DEPLOYMENTS_FIXTURE = """
            {
              "deployments": [
                {
                  "uid": "dpl_ready",
                  "name": "studio-web",
                  "url": "studio-web-abc.vercel.app",
                  "created": 1712000000000,
                  "createdAt": 1712000000000,
                  "state": "READY",
                  "readyState": "READY",
                  "target": "production",
                  "inspectorUrl": "https://vercel.com/acme/studio-web/abc",
                  "creator": {"uid": "user_123", "username": "apoorv", "email": "apoorv@example.com"},
                  "meta": {
                    "githubCommitMessage": "Ship pricing refresh",
                    "githubCommitRef": "main",
                    "githubCommitSha": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c",
                    "githubCommitAuthorName": "Apoorv",
                    "githubOrg": "acme",
                    "githubRepo": "studio-web"
                  }
                },
                {
                  "name": "studio-web",
                  "url": "studio-web-git-feature.vercel.app",
                  "createdAt": 1712000100,
                  "readyState": "BUILDING",
                  "target": null,
                  "meta": {"githubCommitRef": "feature"}
                },
                {"name": null}
              ],
              "pagination": {"count": 3, "next": 1711000000000}
            }
        """.trimIndent()

        val EVENTS_FIXTURE = """
            [
              {"type": "stdout", "created": 1712000001000, "payload": {"deploymentId": "dpl_ready", "text": "  Running build in Washington, D.C.\n", "id": "evt_1"}},
              {"type": "delimiter", "created": 1712000001500, "payload": {"info": {"type": "build", "name": "bld_123", "entrypoint": "."}}},
              {"type": "deployment-state", "created": 1712000001600, "payload": {"info": {"readyState": "READY"}, "text": ""}},
              {"type": "request", "created": 1712000001700, "payload": {"statusCode": 502}},
              {},
              {"type": "command", "created": 1712000002, "payload": null}
            ]
        """.trimIndent()
    }
}
