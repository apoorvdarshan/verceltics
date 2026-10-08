package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.array
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.bool
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.date
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.int
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.obj
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.string
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValues.strings
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class PorkbunPaginationAction {
    COMPLETE,
    LOAD_NEXT_PAGE,
    NO_PROGRESS,
}

/**
 * Read-only registrar client: one shared request/pagination engine with a small adapter per
 * registrar. A faithful port of iOS `RegistrarAPI` (validation, list pagination, normalization and
 * error copy). Every call is single-use and cancellable from another thread.
 */
class RegistrarApi internal constructor(
    private val transport: RegistrarHttpTransport,
) {
    constructor() : this(SecureRegistrarHttpTransport())

    /** Loads the whole portfolio, then derives the iOS account label (`validateCredentials`). */
    fun newValidateCredentialsCall(credentials: RegistrarCredentials): CancelableCall<RegistrarValidation> =
        RegistrarFetchCall(credentials.provider) {
            val domains = fetchDomains(credentials)
            RegistrarValidation(accountName(credentials), domains)
        }

    fun newFetchDomainsCall(credentials: RegistrarCredentials): CancelableCall<List<RegistrarDomain>> =
        RegistrarFetchCall(credentials.provider) { fetchDomains(credentials) }

    private fun RegistrarFetchCall<*>.fetchDomains(credentials: RegistrarCredentials): List<RegistrarDomain> =
        when (credentials.provider) {
            RegistrarProvider.NAME_DOT_COM -> fetchNameDotComDomains(credentials)
            RegistrarProvider.NAMECHEAP -> fetchNamecheapDomains(credentials)
            RegistrarProvider.PORKBUN -> fetchPorkbunDomains(credentials)
            RegistrarProvider.SPACESHIP -> fetchSpaceshipDomains(credentials)
            RegistrarProvider.DYNADOT -> fetchDynadotDomains(credentials)
            RegistrarProvider.NAME_SILO -> fetchNameSiloDomains(credentials)
            RegistrarProvider.GANDI -> fetchGandiDomains(credentials)
            RegistrarProvider.GO_DADDY -> fetchGoDaddyDomains(credentials)
        }

    // region Pagination (ported one-to-one from iOS)

    private fun RegistrarFetchCall<*>.fetchNameDotComDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var page = 1
        val seenPages = mutableSetOf<Int>()
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            if (!seenPages.add(page)) {
                throw RegistrarApiException.decoding("Name.com pagination repeated page $page.")
            }
            val root = obj(
                jsonGet(credentials, "/core/v1/domains", listOf("perPage" to "250", "page" to page.toString())),
            )
            domains += array(root["domains"]).mapNotNull { normalizeNameDotCom(obj(it)) }
            pageCount += 1
            val nextPage = int(root["nextPage"])
            if (nextPage == null || nextPage <= page) break
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            page = nextPage
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchNamecheapDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var page = 1
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            val body = rawGet(
                credentials,
                "/xml.response",
                listOf(
                    "Command" to "namecheap.domains.getList",
                    "ListType" to "ALL",
                    "PageSize" to "100",
                    "Page" to page.toString(),
                ),
            )
            val result = try {
                NamecheapXmlParser.parseDomainPage(body)
            } finally {
                body.fill(0)
            }
            domains += result.domains
            pageCount += 1
            if (result.totalItems.toLong() <= page.toLong() * maxOf(result.pageSize, 1).toLong()) break
            if (result.domains.isEmpty()) {
                throw RegistrarApiException.decoding(
                    "Namecheap pagination returned no domains before reaching the reported total.",
                )
            }
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchPorkbunDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        val seenDomainNames = mutableSetOf<String>()
        val seenOffsets = mutableSetOf<Int>()
        var start = 0
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            if (!seenOffsets.add(start)) {
                throw RegistrarApiException.decoding("Porkbun pagination repeated offset $start.")
            }
            val root = obj(jsonGet(credentials, PORKBUN_LIST_ALL_PATH, porkbunListAllQuery(start)))
            validatePorkbunStatus(root)
            val pageItems = array(root["domains"])
            val pageDomains = pageItems.mapNotNull { normalizePorkbun(obj(it)) }
            val newDomains = pageDomains.filter { seenDomainNames.add(it.name.lowercase(Locale.ROOT)) }
            domains += newDomains
            pageCount += 1
            when (porkbunPaginationAction(pageItems.size, newDomains.size)) {
                PorkbunPaginationAction.COMPLETE -> return domains
                PorkbunPaginationAction.NO_PROGRESS -> throw RegistrarApiException.decoding(
                    "Porkbun pagination returned no new domains for a full page.",
                )
                PorkbunPaginationAction.LOAD_NEXT_PAGE -> {
                    if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
                    start += PORKBUN_PAGE_SIZE
                }
            }
        }
    }

    private fun RegistrarFetchCall<*>.fetchSpaceshipDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var skip = 0
        val pageSize = 100
        val seenOffsets = mutableSetOf<Int>()
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            if (!seenOffsets.add(skip)) {
                throw RegistrarApiException.decoding("Spaceship pagination repeated offset $skip.")
            }
            val value = jsonGet(
                credentials,
                "/v1/domains",
                listOf("take" to pageSize.toString(), "skip" to skip.toString()),
            )
            val root = obj(value)
            val items = array(root["items"] ?: root["domains"] ?: value)
            domains += items.mapNotNull { normalizeSpaceship(obj(it)) }
            val total = int(root["total"]) ?: items.size
            skip += items.size
            pageCount += 1
            if (items.isEmpty() || skip >= total) break
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchDynadotDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var page = 0
        val pageSize = 100
        val seenPageSignatures = mutableSetOf<String>()
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            val value = jsonGet(
                credentials,
                "/api3.json",
                listOf(
                    "command" to "list_domain",
                    "count_per_page" to pageSize.toString(),
                    "page_index" to page.toString(),
                ),
            )
            val root = obj(value)
            val response = obj(root["ListDomainInfoResponse"])
            val status = string(response, "Status")
            if (status != null && status.lowercase(Locale.ROOT) != "success") {
                throw RegistrarApiException.requestFailed(
                    int(response["ResponseCode"]) ?: 400,
                    string(response, "Error") ?: status,
                )
            }
            if (status == null) throwDynadotEnvelopeError(root)
            val items = RegistrarValues.findArray(value, DYNADOT_LIST_KEYS)
            val pageDomains = items.mapNotNull { item ->
                (item as? String)?.let(RegistrarDomain::named) ?: normalizeDynadot(obj(item))
            }
            val signature = pageSignature(pageDomains)
            if (signature.isNotEmpty() && !seenPageSignatures.add(signature)) {
                throw RegistrarApiException.decoding("Dynadot pagination repeated a results page.")
            }
            domains += pageDomains
            pageCount += 1
            if (items.size != pageSize) break
            if (pageDomains.isEmpty()) {
                throw RegistrarApiException.decoding("Dynadot pagination returned no usable domains for a full page.")
            }
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchGandiDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var page = 1
        val pageSize = 100
        val seenPageSignatures = mutableSetOf<String>()
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            val value = jsonGet(
                credentials,
                "/v5/domain/domains",
                listOf("per_page" to pageSize.toString(), "page" to page.toString()),
            )
            val items = array(value)
            val pageDomains = items.mapNotNull { normalizeGandi(obj(it)) }
            val signature = pageSignature(pageDomains)
            if (signature.isNotEmpty() && !seenPageSignatures.add(signature)) {
                throw RegistrarApiException.decoding("Gandi pagination repeated a results page.")
            }
            domains += pageDomains
            pageCount += 1
            if (items.size != pageSize) break
            if (pageDomains.isEmpty()) {
                throw RegistrarApiException.decoding("Gandi pagination returned no usable domains for a full page.")
            }
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchNameSiloDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var page = 1
        val pageSize = 100
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            val root = obj(
                jsonGet(
                    credentials,
                    "/api/listDomains",
                    listOf("pageSize" to pageSize.toString(), "page" to page.toString()),
                ),
            )
            val reply = obj(root["reply"])
            val code = string(reply, "code")
            if (code != null && code != "300") {
                throw RegistrarApiException.requestFailed(
                    code.toIntOrNull() ?: 400,
                    string(reply, "detail") ?: "NameSilo rejected the request.",
                )
            }
            val items = nameSiloDomainItems(reply["domains"])
            domains += items.mapNotNull { value ->
                (value as? String)?.let(RegistrarDomain::named) ?: normalizeNameSilo(obj(value))
            }
            val pager = obj(reply["pager"])
            val total = int(pager["total"]) ?: domains.size
            pageCount += 1
            if (domains.size >= total || items.isEmpty()) break
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private fun RegistrarFetchCall<*>.fetchGoDaddyDomains(credentials: RegistrarCredentials): List<RegistrarDomain> {
        val domains = mutableListOf<RegistrarDomain>()
        var marker: String? = null
        val pageSize = 1_000
        val seenMarkers = mutableSetOf<String>()
        var pageCount = 0
        while (true) {
            throwIfCancelled()
            val query = buildList {
                add("limit" to pageSize.toString())
                add("includes" to "nameServers")
                marker?.let { add("marker" to it) }
            }
            val items = array(jsonGet(credentials, "/v1/domains", query))
            domains += items.mapNotNull { normalizeGoDaddy(obj(it)) }
            pageCount += 1
            val nextMarker = items.lastOrNull()?.let { string(obj(it), "domain") }
            if (items.size != pageSize || nextMarker == null) break
            if (!seenMarkers.add(nextMarker) || nextMarker == marker) {
                throw RegistrarApiException.decoding("GoDaddy pagination repeated a marker.")
            }
            if (pageCount >= MAXIMUM_PAGINATION_PAGES) throw paginationLimitError()
            marker = nextMarker
        }
        return deduplicatedDomains(domains)
    }

    // endregion

    // region Request helpers

    private fun RegistrarFetchCall<*>.jsonGet(
        credentials: RegistrarCredentials,
        path: String,
        query: List<Pair<String, String>>,
    ): Any {
        val body = rawGet(credentials, path, query)
        return try {
            when (val value = RegistrarJson.parse(body)) {
                is Map<*, *>, is List<*> -> value
                else -> throw RegistrarApiException.decoding("The response is not a JSON object or array.")
            }
        } catch (error: RegistrarJsonException) {
            throw RegistrarApiException.decoding(error.message ?: "Invalid JSON.")
        } finally {
            body.fill(0)
        }
    }

    private fun RegistrarFetchCall<*>.rawGet(
        credentials: RegistrarCredentials,
        path: String,
        query: List<Pair<String, String>>,
    ): ByteArray {
        val response = executeChild(transport.newGetCall(buildRequest(credentials, path, query)))
        val body = response.takeBody()
        if (response.statusCode !in 200..299) {
            val message = try {
                errorMessage(body, credentials)
            } finally {
                body.fill(0)
            }
            throw RegistrarApiException.requestFailed(response.statusCode, message)
        }
        return body
    }

    // endregion

    companion object {
        const val MAXIMUM_PAGINATION_PAGES: Int = 200
        const val PORKBUN_PAGE_SIZE: Int = 1_000
        internal const val PORKBUN_LIST_ALL_PATH = "/domain/listAll"
        private val DYNADOT_LIST_KEYS = setOf("MainDomains", "domains", "domain", "Domain")
        private const val MAX_ERROR_MESSAGE_CHARACTERS = 300

        /** The iOS-documented relative path; the zero-based offset is never negative. */
        fun porkbunListAllPath(start: Int): String = "$PORKBUN_LIST_ALL_PATH?start=${maxOf(0, start)}"

        internal fun porkbunListAllQuery(start: Int): List<Pair<String, String>> =
            listOf("start" to maxOf(0, start).toString())

        fun porkbunPaginationAction(pageItemCount: Int, newUniqueDomainCount: Int): PorkbunPaginationAction =
            when {
                pageItemCount < PORKBUN_PAGE_SIZE -> PorkbunPaginationAction.COMPLETE
                newUniqueDomainCount <= 0 -> PorkbunPaginationAction.NO_PROGRESS
                else -> PorkbunPaginationAction.LOAD_NEXT_PAGE
            }

        /** iOS `validateCredentials` account label. */
        fun accountName(credentials: RegistrarCredentials): String {
            val provider = credentials.provider
            return when (provider) {
                RegistrarProvider.NAME_DOT_COM, RegistrarProvider.NAMECHEAP ->
                    credentials.metadataValue(RegistrarMetadataKeys.USERNAME) ?: provider.displayName
                RegistrarProvider.GANDI ->
                    credentials.metadataValue(RegistrarMetadataKeys.ORGANIZATION) ?: "Gandi Account"
                else -> "${provider.displayName} · ${credentialFingerprint(credentials.primary).takeLast(4)}"
            }
        }

        /** First four SHA-256 bytes of the primary credential, hex encoded (iOS fingerprint). */
        internal fun credentialFingerprint(secret: SecretValue): String {
            val bytes = secret.use { it.toByteArray(StandardCharsets.UTF_8) }
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } finally {
                bytes.fill(0)
            }
            return try {
                digest.take(4).joinToString("") { "%02x".format(it) }
            } finally {
                digest.fill(0)
            }
        }

        /** Port of the provider authentication section of iOS `rawRequest`. */
        internal fun buildRequest(
            credentials: RegistrarCredentials,
            path: String,
            query: List<Pair<String, String>>,
        ): RegistrarHttpRequest {
            val provider = credentials.provider
            val publicQuery = query.toMutableList()
            val secretQuery = mutableListOf<Pair<String, SecretValue>>()
            val secretHeaders = mutableListOf<Pair<String, SecretValue>>()
            when (provider) {
                RegistrarProvider.NAMECHEAP -> {
                    val username = requiredMetadata(credentials, RegistrarMetadataKeys.USERNAME, "Namecheap API username")
                    val rawClientIp = requiredMetadata(credentials, RegistrarMetadataKeys.CLIENT_IP, "whitelisted client IP")
                    val clientIp = PublicIpv4Lookup.normalizedPublicIpv4(rawClientIp)
                        ?: throw RegistrarApiException.invalidConfiguration(
                            "Enter a valid public IPv4 address that is whitelisted in Namecheap.",
                        )
                    publicQuery += "ApiUser" to username
                    secretQuery += "ApiKey" to credentials.primary
                    publicQuery += "UserName" to username
                    publicQuery += "ClientIp" to clientIp
                }
                RegistrarProvider.DYNADOT -> secretQuery += "key" to credentials.primary
                RegistrarProvider.NAME_SILO -> {
                    publicQuery += "version" to "1"
                    publicQuery += "type" to "json"
                    secretQuery += "key" to credentials.primary
                }
                RegistrarProvider.NAME_DOT_COM -> {
                    val username = requiredMetadata(credentials, RegistrarMetadataKeys.USERNAME, "Name.com API username")
                    val authorization = credentials.primary.use { token ->
                        val raw = "$username:$token".toByteArray(StandardCharsets.UTF_8)
                        try {
                            SecretValue.of("Basic " + Base64.getEncoder().encodeToString(raw))
                        } finally {
                            raw.fill(0)
                        }
                    }
                    secretHeaders += "Authorization" to authorization
                }
                RegistrarProvider.PORKBUN -> {
                    secretHeaders += "X-API-Key" to credentials.primary
                    secretHeaders += "X-Secret-API-Key" to requiredSecret(credentials)
                }
                RegistrarProvider.SPACESHIP -> {
                    secretHeaders += "X-API-Key" to credentials.primary
                    secretHeaders += "X-API-Secret" to requiredSecret(credentials)
                }
                RegistrarProvider.GANDI -> secretHeaders += "Authorization" to
                    credentials.primary.use { SecretValue.of("Bearer $it") }
                RegistrarProvider.GO_DADDY -> {
                    val secret = requiredSecret(credentials)
                    secretHeaders += "Authorization" to credentials.primary.use { key ->
                        secret.use { value -> SecretValue.of("sso-key $key:$value") }
                    }
                }
            }
            return RegistrarHttpRequest(
                origin = provider.origin,
                path = provider.apiPathPrefix + path,
                queryParameters = publicQuery,
                secretQueryParameters = secretQuery,
                secretHeaders = secretHeaders,
            )
        }

        /**
         * iOS `errorMessage`: JSON `message`/`detail`/`details`/`error` (joined with details when
         * different) or the plain body text. Android additionally redacts the saved secrets,
         * drops markup bodies and bounds the copy before it reaches the UI.
         */
        internal fun errorMessage(body: ByteArray, credentials: RegistrarCredentials): String {
            val text = runCatching { RegistrarJson.decodeUtf8(body) }.getOrDefault("")
            val parsed = runCatching { RegistrarJson.parse(text) }
            val message = if (parsed.isSuccess && parsed.getOrNull() != null) {
                val root = obj(parsed.getOrNull())
                val message = string(root, "message", "detail", "details", "error")
                val details = string(root, "details", "detail")
                if (message != null && details != null && message != details) {
                    "$message — $details"
                } else {
                    message ?: text
                }
            } else {
                text
            }
            return sanitizeProviderText(message, credentials)
        }

        internal fun sanitizeProviderText(raw: String, credentials: RegistrarCredentials): String {
            var text = raw.trim()
            if (text.startsWith("<")) return ""
            listOfNotNull(credentials.primary, credentials.secondary).forEach { secret ->
                secret.use { value -> if (value.isNotEmpty()) text = text.replace(value, "<redacted>") }
            }
            text = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                .replace(WHITESPACE_RUN, " ")
                .trim()
            return if (text.length > MAX_ERROR_MESSAGE_CHARACTERS) {
                text.take(MAX_ERROR_MESSAGE_CHARACTERS).trimEnd() + "…"
            } else {
                text
            }
        }

        private val WHITESPACE_RUN = Regex("\\s+")

        private fun requiredMetadata(credentials: RegistrarCredentials, key: String, label: String): String =
            credentials.metadataValue(key)
                ?: throw RegistrarApiException.invalidConfiguration("Enter the $label.")

        private fun requiredSecret(credentials: RegistrarCredentials): SecretValue =
            credentials.secondary ?: throw RegistrarApiException.invalidConfiguration("Enter the API secret.")

        private fun validatePorkbunStatus(root: Map<String, Any?>) {
            val status = string(root, "status") ?: return
            if (status.lowercase(Locale.ROOT) !in setOf("success", "ok")) {
                throw RegistrarApiException.requestFailed(400, string(root, "message", "error") ?: status)
            }
        }

        /**
         * Android addition: Dynadot reports some failures (for example an invalid key) in a
         * generic `Response` envelope rather than `ListDomainInfoResponse`. Without this, such a
         * reply would look like an empty, successful portfolio.
         */
        private fun throwDynadotEnvelopeError(root: Map<String, Any?>) {
            for (nested in root.values) {
                val envelope = obj(nested)
                val code = int(envelope["ResponseCode"]) ?: continue
                val error = string(envelope, "Error") ?: continue
                if (code != 0) throw RegistrarApiException.requestFailed(400, error)
            }
        }

        /**
         * NameSilo lists are arrays of names or objects. Android also accepts the XML-converted
         * shape `{"domain": [...]}` / `{"domain": "name"}` instead of treating it as empty.
         */
        private fun nameSiloDomainItems(value: Any?): List<Any?> {
            if (value is List<*>) return array(value)
            return when (val nested = obj(value)["domain"]) {
                is List<*> -> array(nested)
                is String -> listOf(nested)
                is Map<*, *> -> listOf(nested)
                else -> emptyList()
            }
        }

        private fun pageSignature(domains: List<RegistrarDomain>): String =
            domains.map { it.name.lowercase(Locale.ROOT) }.sorted().joinToString("|")

        private fun deduplicatedDomains(domains: List<RegistrarDomain>): List<RegistrarDomain> {
            val seenNames = HashSet<String>()
            return domains.filter { domain ->
                val key = domain.name.trim().lowercase(Locale.ROOT)
                key.isNotEmpty() && seenNames.add(key)
            }
        }

        private fun paginationLimitError(): RegistrarApiException =
            RegistrarApiException.decoding("Pagination exceeded $MAXIMUM_PAGINATION_PAGES pages.")

        // region Normalization (ported one-to-one from iOS)

        internal fun normalizeNameDotCom(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "domainName", "domain") ?: return null
            return RegistrarDomain(
                name = name,
                status = string(value, "status"),
                createdAtMillis = date(value["createDate"]),
                expiresAtMillis = date(value["expireDate"]),
                autoRenew = bool(value["autorenewEnabled"] ?: value["autoRenew"]),
                locked = bool(value["locked"]),
                privacyEnabled = bool(value["privacyEnabled"]),
                nameservers = strings(value["nameservers"]),
                metadata = emptyMap(),
            )
        }

        internal fun normalizePorkbun(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "domain", "name") ?: return null
            return RegistrarDomain(
                name = name,
                status = string(value, "status"),
                createdAtMillis = date(value["createDate"] ?: value["createdAt"]),
                expiresAtMillis = date(value["expireDate"] ?: value["expirationDate"]),
                autoRenew = bool(value["autoRenew"]),
                locked = bool(value["securityLock"] ?: value["locked"]),
                privacyEnabled = bool(value["whoisPrivacy"] ?: value["privacy"]),
                nameservers = strings(value["nameservers"]),
                metadata = emptyMap(),
            )
        }

        internal fun normalizeSpaceship(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "unicodeName", "name", "domain") ?: return null
            val privacy = obj(value["privacyProtection"])
            val nameserverRoot = obj(value["nameservers"])
            val statuses = strings(value["eppStatuses"])
            val privacyEnabled = bool(privacy["contactForm"])
                ?: string(privacy, "level")?.let { it.lowercase(Locale.ROOT) != "none" }
            return RegistrarDomain(
                name = name,
                status = string(value, "lifecycleStatus", "status"),
                createdAtMillis = date(value["registrationDate"]),
                expiresAtMillis = date(value["expirationDate"]),
                autoRenew = bool(value["autoRenew"]),
                locked = statuses.any { it.contains("transferProhibited", ignoreCase = true) },
                privacyEnabled = privacyEnabled,
                nameservers = strings(nameserverRoot["hosts"] ?: value["nameservers"]),
                metadata = mapOf("verification" to (string(value, "verificationStatus") ?: "")),
            )
        }

        internal fun normalizeDynadot(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "Name", "name", "Domain", "domain") ?: return null
            return RegistrarDomain(
                name = name,
                status = string(value, "Status", "status"),
                createdAtMillis = date(value["Registration"] ?: value["CreateDate"] ?: value["created"]),
                expiresAtMillis = date(value["Expiration"] ?: value["ExpireDate"] ?: value["expiration"]),
                autoRenew = bool(value["RenewOption"] ?: value["AutoRenew"]),
                locked = bool(value["Locked"] ?: value["lock"]),
                privacyEnabled = bool(value["Privacy"] ?: value["privacy"]),
                nameservers = strings(value["NameServers"] ?: value["nameservers"]),
                metadata = emptyMap(),
            )
        }

        internal fun normalizeNameSilo(item: Map<String, Any?>): RegistrarDomain? {
            val name = string(item, "domain", "name") ?: return null
            return RegistrarDomain(
                name = name,
                status = string(item, "status"),
                createdAtMillis = date(item["created"] ?: item["created_at"]),
                expiresAtMillis = date(item["expires"] ?: item["expiration"]),
                autoRenew = bool(item["auto_renew"] ?: item["autoRenew"]),
                locked = bool(item["locked"]),
                privacyEnabled = bool(item["private"] ?: item["privacy"]),
                nameservers = strings(item["nameservers"]),
                metadata = emptyMap(),
            )
        }

        internal fun normalizeGandi(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "fqdn", "name") ?: return null
            val dates = obj(value["dates"])
            val nameserver = obj(value["nameserver"])
            val statuses = strings(value["status"])
            return RegistrarDomain(
                name = name,
                status = if (statuses.isEmpty()) null else statuses.joinToString(", "),
                createdAtMillis = date(value["dates_registry_created_at"] ?: dates["registry_created_at"]),
                expiresAtMillis = date(value["dates_registry_ends_at"] ?: dates["registry_ends_at"]),
                autoRenew = bool(value["autorenew"]),
                locked = statuses.any { it.contains("transferProhibited", ignoreCase = true) },
                privacyEnabled = bool(value["is_private"] ?: value["privacy"]),
                nameservers = strings(nameserver["hosts"] ?: value["nameservers"]),
                metadata = mapOf("sharingID" to (string(value, "sharing_id") ?: "")),
            )
        }

        internal fun normalizeGoDaddy(value: Map<String, Any?>): RegistrarDomain? {
            val name = string(value, "domain", "name") ?: return null
            return RegistrarDomain(
                name = name,
                status = string(value, "status"),
                createdAtMillis = date(value["createdAt"] ?: value["created"]),
                expiresAtMillis = date(value["expires"] ?: value["expiresAt"]),
                autoRenew = bool(value["renewAuto"] ?: value["autoRenew"]),
                locked = bool(value["locked"]),
                privacyEnabled = bool(value["privacy"]),
                nameservers = strings(value["nameServers"] ?: value["nameservers"]),
                metadata = mapOf("domainID" to (string(value, "domainId") ?: "")),
            )
        }

        // endregion
    }
}

/**
 * Single-use cancellable orchestration around child HTTP calls. Transport, parsing and runtime
 * failures are mapped to user-safe [RegistrarApiException]s; cancellation propagates unchanged.
 */
private class RegistrarFetchCall<T>(
    private val provider: RegistrarProvider,
    private val body: RegistrarFetchCall<T>.() -> T,
) : CancelableCall<T> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeChild = AtomicReference<CancelableCall<*>?>()

    override fun execute(): T {
        check(started.compareAndSet(false, true)) { "A registrar call can only execute once." }
        throwIfCancelled()
        return try {
            body()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RegistrarApiException) {
            throw error
        } catch (error: ResponseTooLargeException) {
            throw RegistrarApiException(
                RegistrarFailureKind.INVALID_RESPONSE,
                "The response exceeded the safe ${formatMegabytes(SecureRegistrarHttpTransport.DEFAULT_MAXIMUM_RESPONSE_BYTES)} limit.",
            )
        } catch (error: UnsafeRedirectException) {
            throw RegistrarApiException(
                RegistrarFailureKind.INVALID_RESPONSE,
                "${provider.displayName} returned an unsafe redirect.",
            )
        } catch (error: IOException) {
            if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
            throw RegistrarApiException.network(provider)
        } catch (error: RegistrarJsonException) {
            throw RegistrarApiException.decoding(error.message ?: "Invalid JSON.")
        } catch (error: IllegalArgumentException) {
            throw RegistrarApiException.invalidConfiguration("The registrar request configuration is invalid.")
        } catch (error: Exception) {
            throw RegistrarApiException.invalidResponse()
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeChild.getAndSet(null)?.cancel()
    }

    fun executeChild(call: CancelableCall<HttpResponse>): HttpResponse {
        throwIfCancelled()
        activeChild.set(call)
        if (cancelled.get()) {
            activeChild.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("The registrar request was cancelled.")
        }
        return try {
            val value = call.execute()
            throwIfCancelled()
            value
        } finally {
            activeChild.compareAndSet(call, null)
        }
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
    }

    override fun toString(): String = "RegistrarFetchCall(provider=${provider.id})"

    private fun formatMegabytes(bytes: Int): String =
        String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}
