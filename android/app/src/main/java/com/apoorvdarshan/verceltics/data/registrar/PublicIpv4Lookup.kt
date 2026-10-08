package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.map

enum class PublicIpv4LookupFailure {
    INVALID_RESPONSE,
    REQUEST_FAILED,
    INVALID_ADDRESS,
}

/** Mirrors iOS `PublicIPv4LookupError` copy. */
class PublicIpv4LookupException(
    val failure: PublicIpv4LookupFailure,
    val statusCode: Int? = null,
) : RuntimeException(
    when (failure) {
        PublicIpv4LookupFailure.INVALID_RESPONSE -> "The public IP service returned an invalid response."
        PublicIpv4LookupFailure.REQUEST_FAILED -> "The public IP service returned HTTP $statusCode."
        PublicIpv4LookupFailure.INVALID_ADDRESS -> "A valid public IPv4 address could not be detected."
    },
)

/**
 * Credential-free lookup of this network's public IPv4 (Namecheap's required `ClientIp` and
 * Name.com's optional allowlist). Port of iOS `PublicIPv4Lookup`.
 */
class PublicIpv4Lookup internal constructor(
    private val transport: RegistrarHttpTransport,
) {
    constructor() : this(SecureRegistrarHttpTransport())

    fun newResolveCall(): CancelableCall<String> = transport.newGetCall(
        RegistrarHttpRequest(
            origin = SecureRegistrarHttpTransport.IPIFY_ORIGIN,
            path = "/",
            headers = mapOf("Cache-Control" to "no-store"),
            accept = "text/plain",
            maximumResponseBytes = MAXIMUM_RESPONSE_BYTES,
        ),
    ).map { response ->
        if (response.statusCode !in 200..299) {
            throw PublicIpv4LookupException(PublicIpv4LookupFailure.REQUEST_FAILED, response.statusCode)
        }
        val body = response.takeBody()
        val raw = try {
            RegistrarJson.decodeUtf8(body)
        } catch (_: RegistrarJsonException) {
            throw PublicIpv4LookupException(PublicIpv4LookupFailure.INVALID_ADDRESS)
        } finally {
            body.fill(0)
        }
        normalizedPublicIpv4(raw)
            ?: throw PublicIpv4LookupException(PublicIpv4LookupFailure.INVALID_ADDRESS)
    }

    companion object {
        const val ENDPOINT: String = "https://api.ipify.org"
        const val MAXIMUM_RESPONSE_BYTES: Int = 64

        /** Canonical dotted-quad when [rawValue] is a routable public IPv4, otherwise null. */
        fun normalizedPublicIpv4(rawValue: String): String? {
            val value = rawValue.trim()
            val parts = value.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                octets[index] = octet
            }
            if (!isPublic(octets)) return null
            return octets.joinToString(".")
        }

        private fun isPublic(octets: IntArray): Boolean {
            val first = octets[0]
            val second = octets[1]
            val third = octets[2]
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
            if (first == 192 && second == 0 && (third == 0 || third == 2)) return false
            if (first == 192 && second == 88 && third == 99) return false
            if (first == 198 && (second == 18 || second == 19)) return false
            if (first == 198 && second == 51 && third == 100) return false
            if (first == 203 && second == 0 && third == 113) return false
            return true
        }
    }
}
