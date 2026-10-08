package com.apoorvdarshan.verceltics.data.apicatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiCatalogTest {
    // region iOS ProviderAPIRequestEncodingTests parity

    @Test
    fun pathParameterEncodesSeparatorsAndUnicode() {
        assertEquals("folder%2Fa%20b%2F%E2%9C%93", ProviderApiRequestEncoding.pathParameter("folder/a b/✓", allowReserved = false))
    }

    @Test
    fun reservedPathExpansionKeepsPathSeparatorsButBlocksQueryAndFragment() {
        assertEquals("folder/a%20b%3Ftoken=x%23part", ProviderApiRequestEncoding.pathParameter("folder/a b?token=x#part", allowReserved = true))
    }

    @Test
    fun awsQueryEncodingUsesOnlyRfc3986UnreservedCharacters() {
        assertEquals("a%2Bb%20%2F%3F%3D%26~", ProviderApiRequestEncoding.awsQueryComponent("a+b /?=&~"))
    }

    // endregion

    @Test
    fun mutationClassificationFollowsIos() {
        assertFalse(operation("GET", "/sites").isMutation)
        assertTrue(operation("POST", "/sites").isMutation)
        assertTrue(operation("DELETE", "/sites/{id}").isMutation)
        assertFalse(operation("HEAD", "/sites").isMutation)
        assertFalse(operation("OPTIONS", "/sites").isMutation)
        // GET commands that write (Namecheap, Dynadot, NameSilo).
        assertTrue(operation("GET", "/xml.response?Command=namecheap.domains.create").isMutation)
        assertTrue(operation("GET", "/api3.json?command=set_ns").isMutation)
        assertTrue(operation("GET", "/api/registerDomain").isMutation)
        assertFalse(operation("GET", "/xml.response?Command=namecheap.domains.getList").isMutation)
        // GraphQL operations are classified by Queries/Mutations tags.
        assertTrue(operation("POST", "/graphql/v2", id = "railway.Mutation.deploy", tags = listOf("Mutations")).isMutation)
        assertFalse(operation("POST", "/graphql/v2", id = "railway.Query.me", tags = listOf("Queries")).isMutation)
        assertFalse(operation("POST", "/graphql/v2", id = "railway:post:/graphql/v2:schema", tags = listOf("GraphQL")).isMutation)
        // Porkbun's POST-only API still has documented reads.
        assertFalse(operation("POST", "/ping", id = "porkbun:post:/ping").isMutation)
        assertTrue(operation("POST", "/domain/create", id = "porkbun:post:/domain/create").isMutation)
    }

    @Test
    fun searchRequiresEveryTermAcrossIdMethodPathSummaryDescriptionAndTags() {
        val operation = operation("GET", "/v2/apps/{id}/deployments", id = "apps_list_deployments", summary = "List deployments", tags = listOf("Apps"))
        assertTrue(operation.matches(""))
        assertTrue(operation.matches("   "))
        assertTrue(operation.matches("DEPLOYMENTS apps"))
        assertTrue(operation.matches("get list"))
        assertTrue(operation.matches("apps_list"))
        assertFalse(operation.matches("deployments delete"))
    }

    @Test
    fun filteringCombinesSearchTagAndAccess() {
        val catalog = catalog(
            operation("GET", "/apps", id = "list", tags = listOf("Apps")),
            operation("POST", "/apps", id = "create", tags = listOf("Apps")),
            operation("DELETE", "/domains/{id}", id = "delete", tags = listOf("domains", "Apps")),
            operation("GET", "/domains", id = "domains", tags = listOf("domains")),
        )
        assertEquals(listOf("Apps", "domains"), catalog.sortedTags)
        assertEquals(2, catalog.tagCount)
        assertEquals(listOf("list", "create", "delete", "domains"), catalog.filteredOperations("", null, ProviderApiAccessFilter.ALL).map { it.id })
        assertEquals(listOf("list", "domains"), catalog.filteredOperations("", null, ProviderApiAccessFilter.READ).map { it.id })
        assertEquals(listOf("create", "delete"), catalog.filteredOperations("", null, ProviderApiAccessFilter.WRITE).map { it.id })
        assertEquals(listOf("delete", "domains"), catalog.filteredOperations("", "domains", ProviderApiAccessFilter.ALL).map { it.id })
        assertEquals(listOf("delete"), catalog.filteredOperations("domains", "Apps", ProviderApiAccessFilter.WRITE).map { it.id })
        assertEquals(emptyList<String>(), catalog.filteredOperations("nothing", null, ProviderApiAccessFilter.ALL).map { it.id })
    }

    @Test
    fun groupingUsesThePrimaryTag() {
        val grouped = listOf(
            operation("GET", "/b", id = "b", tags = listOf("zeta")),
            operation("GET", "/a", id = "a", tags = listOf("Alpha")),
            operation("GET", "/c", id = "c", tags = emptyList()),
        ).groupedByPrimaryTag()
        assertEquals(listOf("Alpha", "Other", "zeta"), grouped.keys.toList())
    }

    @Test
    fun presetEncodesPathParametersAndAppendsQueryAndHeaders() {
        val operation = ProviderApiOperation(
            id = "x",
            method = "GET",
            path = "/sites/{site_id}/files/{+path}",
            summary = "Get a file",
            description = "",
            tags = emptyList(),
            deprecated = false,
            parameters = listOf(
                parameter("site_id", ProviderApiParameterLocation.PATH, required = true),
                parameter("path", ProviderApiParameterLocation.PATH, required = true),
                parameter("per_page", ProviderApiParameterLocation.QUERY),
                parameter("filter", ProviderApiParameterLocation.QUERY),
                parameter("X-Request-Id", ProviderApiParameterLocation.HEADER),
                parameter("X-API-Key", ProviderApiParameterLocation.HEADER, required = true),
                parameter("Content-Type", ProviderApiParameterLocation.HEADER, required = true),
            ),
            contentTypes = emptyList(),
            requestBodyRequired = false,
            bodyTemplate = "",
            multipartFields = emptyList(),
        )
        val values = mapOf(
            "path:site_id" to " my site ",
            "path:path" to "assets/app.js?v=1",
            "query:per_page" to "10",
            "query:filter" to "a&b=c",
            "header:X-Request-Id" to "trace-1",
            "header:X-API-Key" to "typed-key",
            "header:Content-Type" to "application/json",
        )
        val managed = setOf("x-api-key")
        assertFalse(operation.hasMissingRequiredParameters(values, managed))
        assertTrue(operation.hasMissingRequiredParameters(values - "path:site_id", managed))
        // Managed credential headers never block "Review request".
        assertFalse(operation.hasMissingRequiredParameters(values - "header:X-API-Key" - "header:Content-Type", managed))
        assertTrue(operation.hasMissingRequiredParameters(values - "header:X-API-Key", emptySet()))

        val preset = operation.buildPreset(values, bodyText = "", contentType = "application/json", managedHeaders = managed)
        assertEquals("/sites/my%20site/files/assets/app.js%3Fv=1?per_page=10&filter=a%26b%3Dc", preset.path)
        assertEquals(mapOf("X-Request-Id" to "trace-1"), preset.headers)
        assertEquals("application/json", preset.contentType)
        assertEquals("Get a file", preset.title)
        assertEquals("GET", preset.method)
    }

    @Test
    fun presetAppendsToAnExistingQueryAndCarriesTheBody() {
        val operation = ProviderApiOperation(
            id = "namecheap.domains.getInfo",
            method = "GET",
            path = "/xml.response?Command=namecheap.domains.getInfo",
            summary = "namecheap.domains.getInfo",
            description = "",
            tags = listOf("Domains"),
            deprecated = false,
            parameters = listOf(parameter("DomainName", ProviderApiParameterLocation.QUERY, required = true)),
            contentTypes = listOf("application/json", "text/xml"),
            requestBodyRequired = false,
            bodyTemplate = "{}",
            multipartFields = emptyList(),
        )
        val preset = operation.buildPreset(mapOf("query:DomainName" to "example.com"), "{\"a\":1}", "text/xml")
        assertEquals("/xml.response?Command=namecheap.domains.getInfo&DomainName=example.com", preset.path)
        assertEquals("{\"a\":1}", preset.body)
        assertEquals("text/xml", preset.contentType)
    }

    @Test
    fun initialValuesPreferExamplesThenTheFirstEnumValue() {
        val operation = operation("GET", "/x").copy(
            parameters = listOf(
                parameter("a", ProviderApiParameterLocation.QUERY, example = "ex"),
                parameter("b", ProviderApiParameterLocation.QUERY, enumValues = listOf("one", "two")),
                parameter("c", ProviderApiParameterLocation.QUERY),
            ),
        )
        assertEquals(mapOf("query:a" to "ex", "query:b" to "one", "query:c" to ""), operation.initialParameterValues())
    }

    @Test
    fun realCatalogPresetsResolveIntoExplorerPaths() {
        val digitalOcean = ProviderApiCatalogParserTest.bundled("hosting.digitalOcean")
        val listApps = digitalOcean.operations.first { it.method == "GET" && it.path == "/v2/apps" }
        val preset = listApps.buildPreset(listApps.initialParameterValues(), listApps.bodyTemplate, "application/json")
        assertTrue(preset.path.startsWith("/v2/apps"))
        assertFalse(listApps.isMutation)

        val firebase = ProviderApiCatalogParserTest.bundled("hosting.firebase")
        val populate = firebase.operation("firebasehosting.projects.sites.versions.populateFiles")!!
        val firebasePreset = populate.buildPreset(mapOf("path:parent" to "sites/demo/versions/v 1"), populate.bodyTemplate, "application/json")
        assertEquals("/sites/demo/versions/v%201:populateFiles", firebasePreset.path)
        assertEquals("{}", firebasePreset.body)
        assertTrue(populate.isMutation)
    }

    @Test
    fun catalogLookupAndIds() {
        assertEquals("hosting.render", ProviderApiCatalogIds.hosting("render"))
        assertEquals("registrar.goDaddy", ProviderApiCatalogIds.registrar("goDaddy"))
        val catalog = catalog(operation("GET", "/a", id = "a"))
        assertEquals("a", catalog.operation("a")?.id)
        assertNull(catalog.operation("missing"))
    }

    private fun catalog(vararg operations: ProviderApiOperation) =
        ProviderApiCatalog("hosting.test", "Test", "1", "https://example.com", "Test API", operations.toList())

    private fun operation(
        method: String,
        path: String,
        id: String = "$method $path",
        summary: String = "$method $path",
        tags: List<String> = listOf("Tag"),
    ) = ProviderApiOperation(
        id = id,
        method = method,
        path = path,
        summary = summary,
        description = "",
        tags = tags,
        deprecated = false,
        parameters = emptyList(),
        contentTypes = emptyList(),
        requestBodyRequired = false,
        bodyTemplate = "",
        multipartFields = emptyList(),
    )

    private fun parameter(
        name: String,
        location: ProviderApiParameterLocation,
        required: Boolean = false,
        example: String = "",
        enumValues: List<String> = emptyList(),
    ) = ProviderApiParameter(name, location, required, "", "string", example, enumValues)
}
