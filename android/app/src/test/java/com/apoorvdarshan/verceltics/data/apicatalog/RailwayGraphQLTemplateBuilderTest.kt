package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.HostingJson
import com.apoorvdarshan.verceltics.data.hosting.JsonArray
import com.apoorvdarshan.verceltics.data.hosting.JsonNumber
import com.apoorvdarshan.verceltics.data.hosting.JsonObject
import com.apoorvdarshan.verceltics.data.hosting.JsonString
import com.apoorvdarshan.verceltics.data.hosting.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RailwayGraphQLTemplateBuilderTest {
    /** Port of iOS `testRailwayTemplatesExpandRequiredInputsAndSelectUsefulFields`. */
    @Test
    fun templatesExpandRequiredInputsAndSelectUsefulFields() {
        val operations = RailwayGraphQLTemplateBuilder.operations(schema(IOS_SCHEMA))

        val deploy = operations.first { it.summary == "deploy" }
        val deployBody = HostingJson.parse(deploy.bodyTemplate).asObject()
        val deployQuery = (deployBody["query"] as JsonString).value
        val deployVariables = deployBody["variables"].asObject()
        val input = deployVariables["input"].asObject()
        val config = input["config"].asObject()

        assertEquals(JsonString("PRODUCTION"), input["environment"])
        assertEquals("1", (config["replicas"] as JsonNumber).raw)
        assertNull(input["note"])
        assertNull(deployVariables["dryRun"])
        assertTrue(deployQuery.contains("deploy(input: \$input)"))
        assertTrue(deployQuery.contains("id"))
        assertTrue(deployQuery.contains("status"))
        assertFalse(deployQuery.contains("{ __typename }"))
        assertEquals(
            "mutation Verceltics_deploy(\$input: DeployInput!) { deploy(input: \$input) { id status createdAt } }",
            deployQuery,
        )

        val project = operations.first { it.summary == "project" }
        assertTrue(project.bodyTemplate.contains("REPLACE_ME"))
        assertTrue(project.description.contains("includeServices: Boolean"))
        assertTrue(project.description.endsWith("Replace every REPLACE_ME value before sending."))
    }

    @Test
    fun operationsAreTaggedSortedAndReadyForTheExplorer() {
        val operations = RailwayGraphQLTemplateBuilder.operations(schema(IOS_SCHEMA))
        assertEquals(listOf("Mutations" to "deploy", "Queries" to "project"), operations.map { it.primaryTag to it.summary })
        val deploy = operations.first()
        assertEquals("railway.Mutation.deploy", deploy.id)
        assertEquals("POST", deploy.method)
        assertEquals("/graphql/v2", deploy.path)
        assertEquals(listOf("application/json"), deploy.contentTypes)
        assertTrue(deploy.requestBodyRequired)
        assertTrue(deploy.isMutation)
        assertFalse(operations.last().isMutation)
        // The starter request is valid JSON with sorted keys, so the classifier can read it.
        assertTrue(deploy.bodyTemplate.indexOf("\"query\"") < deploy.bodyTemplate.indexOf("\"variables\""))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery(deploy.bodyTemplate))
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery(operations.last().bodyTemplate))
    }

    @Test
    fun unionsListsScalarsAndRecursiveInputsAreHandled() {
        val schema = schema(
            """
            {"queryType":{"name":"Query"},"mutationType":null,"types":[
              {"kind":"OBJECT","name":"Query","fields":[
                {"name":"search","description":"Find things","isDeprecated":false,"deprecationReason":null,
                 "type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"LIST","name":null,"ofType":{"kind":"UNION","name":"Result","ofType":null}}},
                 "args":[
                   {"name":"filter","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"INPUT_OBJECT","name":"Filter","ofType":null}}},
                   {"name":"limit","defaultValue":"10","type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"Int","ofType":null}}}
                 ]},
                {"name":"legacy","description":null,"isDeprecated":true,"deprecationReason":"Use search",
                 "type":{"kind":"SCALAR","name":"String","ofType":null},"args":[]}
              ]},
              {"kind":"UNION","name":"Result","possibleTypes":[{"name":"Project"},{"name":"Team"},{"name":"User"}]},
              {"kind":"OBJECT","name":"Project","fields":[{"name":"id","isDeprecated":false,"args":[],"type":{"kind":"SCALAR","name":"ID"}}]},
              {"kind":"OBJECT","name":"Team","fields":[]},
              {"kind":"INPUT_OBJECT","name":"Filter","inputFields":[
                {"name":"ids","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"LIST","name":null,"ofType":{"kind":"SCALAR","name":"ID"}}}},
                {"name":"when","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"DateTime"}}},
                {"name":"site","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"URL"}}},
                {"name":"ratio","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"Float"}}},
                {"name":"meta","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"SCALAR","name":"JSON"}}},
                {"name":"nested","defaultValue":null,"type":{"kind":"NON_NULL","name":null,"ofType":{"kind":"INPUT_OBJECT","name":"Filter"}}}
              ]},
              {"kind":"SCALAR","name":"ID"},{"kind":"SCALAR","name":"Int"},{"kind":"SCALAR","name":"String"},
              {"kind":"SCALAR","name":"DateTime"},{"kind":"SCALAR","name":"URL"},{"kind":"SCALAR","name":"Float"},{"kind":"SCALAR","name":"JSON"}
            ]}
            """.trimIndent(),
        )
        val operations = RailwayGraphQLTemplateBuilder.operations(schema)
        val search = operations.first { it.summary == "search" }
        val body = HostingJson.parse(search.bodyTemplate).asObject()
        val query = (body["query"] as JsonString).value
        assertEquals(
            "query Verceltics_search(\$filter: Filter!) { search(filter: \$filter) { __typename ... on Project { id } ... on Team { __typename } } }",
            query,
        )
        val filter = body["variables"].asObject()["filter"].asObject()
        assertEquals(JsonArray(emptyList()), filter["ids"])
        assertEquals(JsonString("2026-01-01T00:00:00.000Z"), filter["when"])
        assertEquals(JsonString("https://example.com"), filter["site"])
        assertEquals("1.0", (filter["ratio"] as JsonNumber).raw)
        assertEquals(JsonObject.EMPTY, filter["meta"])
        // The recursive input stops after one level instead of looping forever.
        assertEquals(JsonObject.EMPTY, filter["nested"])
        assertEquals("Find things\n\nOptional arguments omitted from the safe starter request: limit: Int!.", search.description)

        val legacy = operations.first { it.summary == "legacy" }
        assertTrue(legacy.deprecated)
        assertEquals("Use search", legacy.description)
        assertEquals("query Verceltics_legacy { legacy }", (HostingJson.parse(legacy.bodyTemplate).asObject()["query"] as JsonString).value)
    }

    @Test
    fun liveAndFallbackCatalogsUseIosCopy() {
        val live = RailwayGraphQLTemplateBuilder.liveCatalog(schema(IOS_SCHEMA))
        assertEquals("hosting.railway", live.id)
        assertEquals("Railway", live.title)
        assertEquals("Live GraphQL v2", live.apiVersion)
        assertEquals("https://docs.railway.com/integrations/api", live.sourceUrl)
        assertEquals("Every query and mutation discovered live from the authenticated Railway GraphQL schema", live.sourceDescription)
        assertEquals(2, live.operations.size)

        val bundled = ProviderApiCatalogParserTest.bundled("hosting.railway")
        val fallback = RailwayGraphQLTemplateBuilder.fallbackCatalog(bundled, "Railway rejected these credentials.")
        assertEquals("GraphQL v2 · Bundled fallback", fallback.apiVersion)
        assertEquals(
            "Live schema discovery was unavailable. The bundled manual GraphQL request remains usable. Railway rejected these credentials.",
            fallback.sourceDescription,
        )
        assertEquals(bundled.operations, fallback.operations)
    }

    @Test
    fun schemasWithoutTypesAreRejected() {
        try {
            RailwayGraphQLTemplateBuilder.operations(JsonObject.EMPTY)
            fail("Expected rejection")
        } catch (error: ProviderApiCatalogException) {
            assertEquals("Railway did not return a usable GraphQL schema.", error.message)
        }
    }

    @Test
    fun introspectionQueryMatchesIos() {
        val query = RailwayGraphQLTemplateBuilder.INTROSPECTION_QUERY
        assertTrue(query.startsWith("query VercelticsIntrospection {"))
        assertTrue(query.contains("enumValues(includeDeprecated: true) { name }"))
        assertTrue(query.contains("fields(includeDeprecated: true) {"))
        assertEquals(query.count { it == '{' }, query.count { it == '}' })
    }

    private fun schema(text: String): JsonObject = HostingJson.parse(text).asObject()

    private companion object {
        private fun nonNull(nested: String) = """{"kind":"NON_NULL","name":null,"ofType":$nested}"""
        private fun scalar(name: String) = """{"kind":"SCALAR","name":"$name","ofType":null}"""
        private fun named(kind: String, name: String) = """{"kind":"$kind","name":"$name","ofType":null}"""
        private fun argument(name: String, type: String) = """{"name":"$name","type":$type,"defaultValue":null}"""
        private fun field(name: String, type: String, arguments: List<String>) =
            """{"name":"$name","description":"","isDeprecated":false,"deprecationReason":null,"type":$type,"args":[${arguments.joinToString(",")}]}"""

        val IOS_SCHEMA = """
            {"queryType":{"name":"Query"},"mutationType":{"name":"Mutation"},"types":[
              {"kind":"OBJECT","name":"Query","fields":[${field("project", named("OBJECT", "Project"), listOf(argument("id", nonNull(scalar("ID"))), argument("includeServices", scalar("Boolean"))))}]},
              {"kind":"OBJECT","name":"Mutation","fields":[${field("deploy", named("OBJECT", "Deployment"), listOf(argument("input", nonNull(named("INPUT_OBJECT", "DeployInput"))), argument("dryRun", scalar("Boolean"))))}]},
              {"kind":"INPUT_OBJECT","name":"DeployInput","inputFields":[${argument("environment", nonNull(named("ENUM", "Environment")))},${argument("config", nonNull(named("INPUT_OBJECT", "DeployConfigInput")))},${argument("note", scalar("String"))}]},
              {"kind":"INPUT_OBJECT","name":"DeployConfigInput","inputFields":[${argument("replicas", nonNull(scalar("Int")))},${argument("region", scalar("String"))}]},
              {"kind":"ENUM","name":"Environment","enumValues":[{"name":"PRODUCTION"},{"name":"STAGING"}]},
              {"kind":"OBJECT","name":"Project","fields":[${field("id", scalar("ID"), emptyList())},${field("name", scalar("String"), emptyList())},${field("updatedAt", scalar("DateTime"), emptyList())}]},
              {"kind":"OBJECT","name":"Deployment","fields":[${field("id", scalar("ID"), emptyList())},${field("status", scalar("String"), emptyList())},${field("createdAt", scalar("DateTime"), emptyList())}]},
              {"kind":"SCALAR","name":"ID"},{"kind":"SCALAR","name":"String"},{"kind":"SCALAR","name":"Boolean"},{"kind":"SCALAR","name":"Int"},{"kind":"SCALAR","name":"DateTime"}
            ]}
        """.trimIndent()
    }
}
