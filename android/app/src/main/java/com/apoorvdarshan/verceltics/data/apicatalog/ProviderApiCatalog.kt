package com.apoorvdarshan.verceltics.data.apicatalog

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Android port of iOS `ProviderAPICatalog`: every documented operation for one hosting provider or
 * domain registrar, bundled in `assets/ProviderAPICatalog.json` (or, for Railway, discovered live).
 */
data class ProviderApiCatalog(
    /** `hosting.<providerId>` or `registrar.<providerId>`, exactly as on iOS. */
    val id: String,
    val title: String,
    val apiVersion: String,
    val sourceUrl: String,
    val sourceDescription: String,
    val operations: List<ProviderApiOperation>,
) {
    /** iOS `tagCount`. */
    val tagCount: Int
        get() = operations.flatMapTo(HashSet()) { it.tags }.size

    /** Distinct tags sorted case-insensitively (the catalog's tag chips after "All"). */
    val sortedTags: List<String>
        get() = operations.flatMapTo(LinkedHashSet()) { it.tags }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun operation(id: String): ProviderApiOperation? = operations.firstOrNull { it.id == id }

    override fun toString(): String = "ProviderApiCatalog(id=$id, operations=${operations.size})"
}

data class ProviderApiOperation(
    val id: String,
    val method: String,
    val path: String,
    val summary: String,
    val description: String,
    val tags: List<String>,
    val deprecated: Boolean,
    val parameters: List<ProviderApiParameter>,
    val contentTypes: List<String>,
    val requestBodyRequired: Boolean,
    val bodyTemplate: String,
    val multipartFields: List<ProviderApiMultipartField>,
) {
    val primaryTag: String
        get() = tags.firstOrNull() ?: "Other"

    /** Lower-cased search text: id, method, path, summary, description and tags (iOS `matches`). */
    private val searchHaystack: String by lazy {
        (listOf(id, method, path, summary, description) + tags).joinToString(" ").lowercase(Locale.ROOT)
    }

    /** iOS `isMutation`: the catalog's read/write classification for badges and the access filter. */
    val isMutation: Boolean by lazy { classifyMutation() }

    /** Every whitespace-separated term must appear somewhere in the operation (iOS `matches`). */
    fun matches(query: String): Boolean {
        val terms = query.split(WHITESPACE).filter(String::isNotEmpty).map { it.lowercase(Locale.ROOT) }
        if (terms.isEmpty()) return true
        return terms.all { it in searchHaystack }
    }

    private fun classifyMutation(): Boolean {
        val value = "$path $id $primaryTag".lowercase(Locale.ROOT)
        if ("graphql" in value || "queries" in value || "mutations" in value) {
            return "mutation" in value || "mutations" in value
        }
        if ("api.porkbun" in value || "porkbun:" in value) {
            if (PORKBUN_READS.any { it in value }) return false
        }
        val upper = method.uppercase(Locale.ROOT)
        if (upper == "GET") return GET_WRITE_MARKERS.any { it in value }
        return upper != "HEAD" && upper != "OPTIONS"
    }

    override fun toString(): String = "ProviderApiOperation(id=$id, method=$method, path=$path)"

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PORKBUN_READS = listOf(
            "/ping", "/pricing/get", "/domain/listall", "/domain/checkdomain", "/domain/getns",
            "/domain/geturlforwarding", "/dns/retrieve", "/ssl/retrieve",
        )
        val GET_WRITE_MARKERS = listOf(
            ".create", ".set", ".renew", ".reactivate", ".delete", ".update", ".change", ".enable", ".disable",
            ".activate", ".reissue", ".resend", ".purchase", ".revoke", ".edit", ".reset",
            "command=register", "command=restore", "command=renew", "command=transfer", "command=set_",
            "command=create_", "command=edit_", "command=delete", "command=clear_", "command=push_",
            "command=buy_", "command=make_", "command=place_",
            "/api/register", "/api/renew", "/api/transfer", "/api/change", "/api/contactadd", "/api/contactupdate",
            "/api/domainupdate", "/api/add", "/api/remove", "/api/dnsadd", "/api/dnsupdate", "/api/dnsdelete",
            "/api/domainforward", "/api/modify", "/api/delete", "/api/portfolioadd", "/api/portfoliodelete",
        )
    }
}

enum class ProviderApiParameterLocation(val wireValue: String) {
    PATH("path"),
    QUERY("query"),
    HEADER("header"),
    ;

    companion object {
        fun fromWireValue(value: String?): ProviderApiParameterLocation? = entries.firstOrNull { it.wireValue == value }
    }
}

data class ProviderApiParameter(
    val name: String,
    val location: ProviderApiParameterLocation,
    val required: Boolean,
    val description: String,
    val type: String,
    val example: String,
    val enumValues: List<String>,
) {
    /** iOS `ProviderAPIParameter.id`: `location:name`. */
    val id: String
        get() = "${location.wireValue}:$name"

    /** Example first, then the first enum value (iOS operation view initial value). */
    val initialValue: String
        get() = example.ifEmpty { enumValues.firstOrNull().orEmpty() }
}

/** One `multipart/form-data` field from the OpenAPI schema (iOS `CloudflareOpenAPIMultipartField`). */
data class ProviderApiMultipartField(
    val name: String,
    val required: Boolean,
    val isFile: Boolean,
    val type: String?,
    val format: String?,
    val description: String?,
    val suggestedValue: String,
)

/** iOS `ProviderAPIRequestPreset`: what "Review request" carries into the raw explorer. */
data class ProviderApiRequestPreset(
    val title: String,
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String>,
    val contentType: String?,
    val multipartFields: List<ProviderApiMultipartField>,
)

/** The catalog's All / Read / Write segmented filter. */
enum class ProviderApiAccessFilter(val title: String) {
    ALL("All"),
    READ("Read"),
    WRITE("Write"),
}

object ProviderApiCatalogIds {
    const val HOSTING_PREFIX: String = "hosting."
    const val REGISTRAR_PREFIX: String = "registrar."

    /** iOS `AccountProvider.apiCatalogID` (Netlify included). */
    fun hosting(providerId: String): String = "$HOSTING_PREFIX$providerId"

    /** iOS `RegistrarProvider.apiCatalogID`. */
    fun registrar(providerId: String): String = "$REGISTRAR_PREFIX$providerId"

    const val RAILWAY: String = "hosting.railway"
}

/** Catalog-screen filtering: search, tag chip and access filter (iOS `operations`). */
fun ProviderApiCatalog.filteredOperations(
    query: String,
    tag: String?,
    access: ProviderApiAccessFilter,
): List<ProviderApiOperation> = operations.filter { operation ->
    operation.matches(query) &&
        (tag == null || tag in operation.tags) &&
        (access == ProviderApiAccessFilter.ALL || (access == ProviderApiAccessFilter.WRITE) == operation.isMutation)
}

/** Operations grouped by their primary tag, in catalog order within each group. */
fun List<ProviderApiOperation>.groupedByPrimaryTag(): Map<String, List<ProviderApiOperation>> =
    groupBy(ProviderApiOperation::primaryTag)
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)

/** Initial operation-detail values: example, then the first enum value. */
fun ProviderApiOperation.initialParameterValues(): Map<String, String> =
    parameters.associate { it.id to it.initialValue }

/**
 * A header parameter the explorer never sends from user input: credentials and transport headers
 * are attached privately (iOS drops them later; Android also exempts them from "required").
 */
fun ProviderApiParameter.isManaged(managedHeaders: Set<String>): Boolean =
    location == ProviderApiParameterLocation.HEADER &&
        (name.lowercase(Locale.ROOT) in managedHeaders || name.equals("Content-Type", ignoreCase = true))

/** iOS `hasMissingRequiredParameters`, excluding headers that Verceltics manages itself. */
fun ProviderApiOperation.hasMissingRequiredParameters(
    values: Map<String, String>,
    managedHeaders: Set<String> = emptySet(),
): Boolean = parameters.any { parameter ->
    parameter.required && !parameter.isManaged(managedHeaders) && values[parameter.id].orEmpty().trim().isEmpty()
}

/**
 * iOS `ProviderAPIOperationView.preset`: path parameters are percent-encoded into the template
 * (`{+name}` keeps reserved characters), query parameters are appended and header parameters
 * become custom headers. A `Content-Type` header parameter selects the content type instead.
 */
fun ProviderApiOperation.buildPreset(
    values: Map<String, String>,
    bodyText: String,
    contentType: String,
    managedHeaders: Set<String> = emptySet(),
): ProviderApiRequestPreset {
    var resolvedPath = path
    val queryItems = mutableListOf<Pair<String, String>>()
    val headers = linkedMapOf<String, String>()
    var headerContentType: String? = null
    for (parameter in parameters) {
        val value = values[parameter.id].orEmpty().trim()
        when (parameter.location) {
            ProviderApiParameterLocation.PATH -> {
                resolvedPath = resolvedPath
                    .replace("{+${parameter.name}}", ProviderApiRequestEncoding.pathParameter(value, allowReserved = true))
                    .replace("{${parameter.name}}", ProviderApiRequestEncoding.pathParameter(value, allowReserved = false))
            }
            ProviderApiParameterLocation.QUERY -> if (value.isNotEmpty()) queryItems += parameter.name to value
            ProviderApiParameterLocation.HEADER -> when {
                value.isEmpty() -> Unit
                parameter.name.equals("Content-Type", ignoreCase = true) -> headerContentType = value
                parameter.isManaged(managedHeaders) -> Unit
                else -> headers[parameter.name] = value
            }
        }
    }
    if (queryItems.isNotEmpty()) {
        val encoded = queryItems.joinToString("&") { (name, value) ->
            "${ProviderApiRequestEncoding.queryComponent(name)}=${ProviderApiRequestEncoding.queryComponent(value)}"
        }
        resolvedPath += if ('?' in resolvedPath) "&$encoded" else "?$encoded"
    }
    return ProviderApiRequestPreset(
        title = summary,
        method = method,
        path = resolvedPath,
        body = bodyText,
        headers = headers,
        contentType = if (contentTypes.isEmpty()) headerContentType else (headerContentType ?: contentType),
        multipartFields = multipartFields,
    )
}

/** iOS `ProviderAPIRequestEncoding`, byte-for-byte. */
object ProviderApiRequestEncoding {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val RESERVED_PATH = ":/@!$&'()*+,;="

    /**
     * RFC 3986 path-parameter encoding. Reserved expansion (`{+value}`) keeps path separators and
     * other reserved path characters but never `?` or `#`.
     */
    fun pathParameter(value: String, allowReserved: Boolean): String =
        percentEncode(value, if (allowReserved) RESERVED_PATH else "")

    /** AWS SigV4 style: every byte except RFC 3986 unreserved characters, uppercase hex. */
    fun awsQueryComponent(value: String): String = percentEncode(value, "")

    /** Query names and values appended by the catalog; unreserved characters only. */
    fun queryComponent(value: String): String = percentEncode(value, "")

    private fun percentEncode(value: String, additionallyAllowed: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (unsigned < 0x80 && (character in UNRESERVED || character in additionallyAllowed)) {
                output.append(character)
            } else {
                output.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0f])
            }
        }
        return output.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/** User-facing catalog failures (iOS `ProviderAPICatalogError`). */
class ProviderApiCatalogException(message: String) : RuntimeException(message) {
    companion object {
        fun missingBundle() = ProviderApiCatalogException("The complete provider API catalog is missing from this build.")

        fun missingProvider(id: String) = ProviderApiCatalogException("No API definition is bundled for $id.")
    }
}
