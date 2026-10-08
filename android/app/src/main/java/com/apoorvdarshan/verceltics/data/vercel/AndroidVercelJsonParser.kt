package com.apoorvdarshan.verceltics.data.vercel

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

interface VercelJsonParser {
    fun parseUser(bytes: ByteArray): VercelUser

    fun parseProjects(bytes: ByteArray): VercelProjectsPage

    fun parseProject(bytes: ByteArray): VercelProject = VercelResourceDecoder.project(bytes)

    fun parseProjectDomains(bytes: ByteArray): List<VercelProjectDomain> =
        VercelResourceDecoder.projectDomains(bytes)

    fun parseDeployments(bytes: ByteArray): List<VercelDeployment> =
        VercelResourceDecoder.deployments(bytes)

    fun parseDeploymentEvents(bytes: ByteArray): List<VercelDeploymentEvent> =
        VercelResourceDecoder.deploymentEvents(bytes)

    fun parseTeams(bytes: ByteArray): VercelTeamsPage

    fun parseAnalyticsOverview(bytes: ByteArray): VercelAnalyticsOverview

    fun parseAnalyticsTimeseries(bytes: ByteArray): VercelAnalyticsTimeseries

    fun parseErrorCode(bytes: ByteArray): String?
}

/**
 * Strict streaming JSON parsing using the Android platform API for account, team and analytics
 * payloads. Project resources go through [VercelResourceDecoder]. Unknown fields are ignored.
 */
class AndroidVercelJsonParser : VercelJsonParser {
    override fun parseUser(bytes: ByteArray): VercelUser = parse(bytes) { reader ->
        var user: VercelUser? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "user" -> user = readUser(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        user ?: throw VercelResponseFormatException("Vercel returned no user record.")
    }

    /** Rich project listings (domains, deployments, repository) use the shared resource decoder. */
    override fun parseProjects(bytes: ByteArray): VercelProjectsPage =
        VercelResourceDecoder.projectsPage(bytes)

    override fun parseTeams(bytes: ByteArray): VercelTeamsPage = parse(bytes) { reader ->
        val teams = mutableListOf<VercelTeam>()
        var nextCursor: String? = null
        var sawTeams = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "teams" -> {
                    sawTeams = true
                    reader.beginArray()
                    while (reader.hasNext()) teams += readTeam(reader)
                    reader.endArray()
                }

                "pagination" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "next" -> nextCursor = readStringOrNumber(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!sawTeams) throw VercelResponseFormatException("Vercel returned no teams collection.")
        VercelTeamsPage(teams = teams, nextCursor = nextCursor)
    }

    override fun parseAnalyticsOverview(bytes: ByteArray): VercelAnalyticsOverview = parse(bytes) { reader ->
        var pageViews: Long? = null
        var visitors: Long? = null
        var bounceRate: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "total" -> pageViews = readOptionalLong(reader)
                "devices" -> visitors = readOptionalLong(reader)
                "bounceRate" -> bounceRate = readOptionalDouble(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        VercelAnalyticsOverview(
            pageViews = pageViews
                ?: throw VercelResponseFormatException("Vercel analytics page views are missing."),
            visitors = visitors
                ?: throw VercelResponseFormatException("Vercel analytics visitors are missing."),
            bounceRate = bounceRate,
        )
    }

    override fun parseAnalyticsTimeseries(bytes: ByteArray): VercelAnalyticsTimeseries = parse(bytes) { reader ->
        val groups = linkedMapOf<String, List<VercelAnalyticsPoint>>()
        var sawData = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "data" -> {
                    sawData = true
                    readAnalyticsData(reader, groups)
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!sawData) {
            throw VercelResponseFormatException("Vercel returned no analytics data object.")
        }
        VercelAnalyticsTimeseries(groups)
    }

    override fun parseErrorCode(bytes: ByteArray): String? = try {
        parse(bytes) { reader ->
            var errorCode: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "error" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "code" -> errorCode = readOptionalString(reader)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }

                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            errorCode?.take(MAX_ERROR_CODE_CHARACTERS)
        }
    } catch (_: VercelResponseFormatException) {
        null
    }

    private fun readUser(reader: JsonReader): VercelUser {
        var id: String? = null
        var username: String? = null
        var email: String? = null
        var name: String? = null
        var avatarUrl: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readOptionalString(reader)
                "username" -> username = readOptionalString(reader)
                "email" -> email = readOptionalString(reader)
                "name" -> name = readOptionalString(reader)
                "avatar" -> avatarUrl = readOptionalString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val requiredId = id?.takeIf(String::isNotBlank)
            ?: throw VercelResponseFormatException("Vercel user id is missing.")
        val requiredUsername = username?.takeIf(String::isNotBlank)
            ?: email?.substringBefore('@')?.takeIf(String::isNotBlank)
            ?: throw VercelResponseFormatException("Vercel username is missing.")
        return VercelUser(
            id = requiredId,
            username = requiredUsername,
            email = email,
            name = name,
            avatarUrl = avatarUrl,
        )
    }

    private fun readTeam(reader: JsonReader): VercelTeam {
        var id: String? = null
        var slug: String? = null
        var name: String? = null
        var membershipConfirmed: Boolean? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readOptionalString(reader)
                "slug" -> slug = readOptionalString(reader)
                "name" -> name = readOptionalString(reader)
                "membership" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "confirmed" -> membershipConfirmed = readOptionalBoolean(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return VercelTeam(
            id = id?.takeIf(String::isNotBlank)
                ?: throw VercelResponseFormatException("Vercel team id is missing."),
            slug = slug?.takeIf(String::isNotBlank)
                ?: throw VercelResponseFormatException("Vercel team slug is missing."),
            name = name,
            membershipConfirmed = membershipConfirmed,
        )
    }

    private fun readAnalyticsData(
        reader: JsonReader,
        output: MutableMap<String, List<VercelAnalyticsPoint>>,
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            throw VercelResponseFormatException("Vercel analytics data is malformed.")
        }
        reader.beginObject()
        var sawGroups = false
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "groups" -> {
                    sawGroups = true
                    readAnalyticsGroups(reader, output)
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!sawGroups) {
            throw VercelResponseFormatException("Vercel returned no analytics groups.")
        }
    }

    private fun readAnalyticsGroups(
        reader: JsonReader,
        output: MutableMap<String, List<VercelAnalyticsPoint>>,
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            throw VercelResponseFormatException("Vercel analytics groups are malformed.")
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val group = reader.nextName()
            val points = mutableListOf<VercelAnalyticsPoint>()
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                while (reader.hasNext()) points += readAnalyticsPoint(reader)
                reader.endArray()
            } else {
                reader.skipValue()
            }
            output[group] = points
        }
        reader.endObject()
    }

    private fun readAnalyticsPoint(reader: JsonReader): VercelAnalyticsPoint {
        var key: String? = null
        var pageViews: Long? = null
        var visitors: Long? = null
        var bounceRate: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "key" -> key = readOptionalString(reader)
                "total" -> pageViews = readOptionalLong(reader)
                "devices" -> visitors = readOptionalLong(reader)
                "bounceRate" -> bounceRate = readOptionalDouble(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return VercelAnalyticsPoint(
            key = key?.takeIf(String::isNotBlank)
                ?: throw VercelResponseFormatException("Vercel analytics point key is missing."),
            pageViews = pageViews
                ?: throw VercelResponseFormatException("Vercel analytics point page views are missing."),
            visitors = visitors
                ?: throw VercelResponseFormatException("Vercel analytics point visitors are missing."),
            bounceRate = bounceRate,
        )
    }

    private fun readOptionalString(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readStringOrNumber(reader: JsonReader): String? = readOptionalString(reader)

    private fun readOptionalLong(reader: JsonReader): Long? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        JsonToken.NUMBER, JsonToken.STRING -> reader.nextString().toLongOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readOptionalDouble(reader: JsonReader): Double? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        JsonToken.NUMBER, JsonToken.STRING -> reader.nextString().toDoubleOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readOptionalBoolean(reader: JsonReader): Boolean? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.STRING -> reader.nextString().toBooleanStrictOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun <T> parse(bytes: ByteArray, block: (JsonReader) -> T): T = try {
        ByteArrayInputStream(bytes).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { text ->
                JsonReader(text).use { reader ->
                    reader.isLenient = false
                    val result = block(reader)
                    if (reader.peek() != JsonToken.END_DOCUMENT) {
                        throw VercelResponseFormatException("Vercel returned trailing JSON data.")
                    }
                    result
                }
            }
        }
    } catch (error: VercelResponseFormatException) {
        throw error
    } catch (error: Exception) {
        throw VercelResponseFormatException("Vercel returned malformed JSON.", error)
    }

    companion object {
        private const val MAX_ERROR_CODE_CHARACTERS = 128
    }
}
