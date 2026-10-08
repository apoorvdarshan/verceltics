package com.apoorvdarshan.verceltics.data.registrar

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

internal data class NamecheapDomainPage(
    val domains: List<RegistrarDomain>,
    val totalItems: Int,
    val pageSize: Int,
)

/**
 * Port of the iOS `NamecheapDomainXMLDelegate` + `parseNamecheapDomains`. Uses the platform SAX
 * parser (available on Android and the JVM) with document type declarations rejected up front, so
 * external entities can never be resolved.
 */
internal object NamecheapXmlParser {
    private const val MAX_TEXT_CHARACTERS = 65_536
    private const val MAX_DOMAINS_PER_PAGE = 10_000

    fun parseDomainPage(bytes: ByteArray): NamecheapDomainPage {
        val xml = try {
            RegistrarJson.decodeUtf8(bytes)
        } catch (_: RegistrarJsonException) {
            throw RegistrarApiException.decoding("Namecheap returned invalid XML.")
        }
        return parseDomainPage(xml)
    }

    fun parseDomainPage(xml: String): NamecheapDomainPage {
        val upper = xml.uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in upper || "<!ENTITY" in upper) {
            throw RegistrarApiException.decoding("Namecheap XML parsing failed.")
        }
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                SAFE_FEATURES.forEach { (feature, enabled) ->
                    runCatching { setFeature(feature, enabled) }
                }
            }
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (error: RegistrarApiException) {
            throw error
        } catch (_: Exception) {
            throw RegistrarApiException.decoding("Namecheap XML parsing failed.")
        }
        handler.apiError?.let { throw RegistrarApiException.requestFailed(400, it) }
        val domains = handler.domains.mapNotNull { attributes ->
            val name = attributes["Name"]?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            RegistrarDomain(
                name = name,
                status = if (attributes["IsExpired"] == "true") "Expired" else "Active",
                createdAtMillis = RegistrarValues.date(attributes["Created"]),
                expiresAtMillis = RegistrarValues.date(attributes["Expires"]),
                autoRenew = RegistrarValues.bool(attributes["AutoRenew"]),
                locked = RegistrarValues.bool(attributes["IsLocked"]),
                privacyEnabled = attributes["WhoisGuard"]?.uppercase(Locale.ROOT) in PRIVATE_WHOIS_GUARD,
                nameservers = emptyList(),
                metadata = mapOf("isOurDNS" to (attributes["IsOurDNS"] ?: "")),
            )
        }
        return NamecheapDomainPage(
            domains = domains,
            totalItems = handler.totalItems ?: domains.size,
            pageSize = handler.pageSize ?: maxOf(domains.size, 1),
        )
    }

    private class Handler : DefaultHandler() {
        val domains = mutableListOf<Map<String, String>>()
        var apiError: String? = null
        var totalItems: Int? = null
        var pageSize: Int? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            text.setLength(0)
            if (elementName(localName, qName) == "Domain" && attributes.length > 0) {
                if (domains.size >= MAX_DOMAINS_PER_PAGE) {
                    throw RegistrarApiException.decoding("Namecheap returned too many domains.")
                }
                val values = LinkedHashMap<String, String>()
                for (index in 0 until attributes.length) {
                    val name = attributes.getQName(index)?.takeIf(String::isNotEmpty)
                        ?: attributes.getLocalName(index)
                    if (!name.isNullOrEmpty()) values[name] = attributes.getValue(index).orEmpty()
                }
                domains += values
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (text.length + length <= MAX_TEXT_CHARACTERS) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val value = text.toString().trim()
            when (elementName(localName, qName)) {
                "Error" -> if (value.isNotEmpty()) apiError = value
                "TotalItems" -> totalItems = value.toIntOrNull()
                "PageSize" -> pageSize = value.toIntOrNull()
            }
        }

        private fun elementName(localName: String?, qName: String?): String =
            (qName?.takeIf(String::isNotEmpty) ?: localName.orEmpty()).substringAfter(':')
    }

    private val PRIVATE_WHOIS_GUARD = setOf("ENABLED", "WITHHELD")

    private val SAFE_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )
}
