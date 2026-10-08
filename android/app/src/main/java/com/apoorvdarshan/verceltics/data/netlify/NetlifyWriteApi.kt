package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.HostingApiException
import com.apoorvdarshan.verceltics.data.hosting.HostingAuth
import com.apoorvdarshan.verceltics.data.hosting.HostingEndpoint
import com.apoorvdarshan.verceltics.data.hosting.HostingFailure
import com.apoorvdarshan.verceltics.data.hosting.HostingFailureKind
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpMethod
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpRequest
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpTransport
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/**
 * iOS `HostingProviderAPI.performPrimaryAction` for Netlify: "Redeploy" triggers a fresh build
 * with `POST /api/v1/sites/{site_id}/builds` and an empty JSON object. Only the fixed Netlify
 * origin can be reached; the site id is a single, encoded path segment.
 */
object NetlifyRedeployRequest {
    const val BODY: String = "{}"

    fun build(token: SecretValue, siteId: String): HostingHttpRequest {
        val safeSiteId = siteId.trim()
        require(safeSiteId.isNotEmpty() && safeSiteId.length <= MAX_SITE_ID_CHARACTERS) {
            "A Netlify site id is required."
        }
        require(SAFE_SITE_ID.matches(safeSiteId)) { "The Netlify site id is not safe for a provider path." }
        return HostingHttpRequest(
            endpoint = HostingEndpoint.NETLIFY,
            method = HostingHttpMethod.POST,
            pathSegments = listOf("api", "v1", "sites", safeSiteId, "builds"),
            body = BODY.toByteArray(StandardCharsets.UTF_8),
            contentType = HostingHttpRequest.JSON_CONTENT_TYPE,
            auth = HostingAuth.Bearer(token),
        )
    }

    private const val MAX_SITE_ID_CHARACTERS = 256
    private val SAFE_SITE_ID = Regex("[A-Za-z0-9._~-]+")
}

/** Netlify's write surface. Every call here is a real provider mutation and needs confirmation. */
class NetlifyWriteApi(private val transport: HostingHttpTransport) {
    /** Starts a new build of [siteId] (iOS "Redeploy"). Provider bodies are never surfaced. */
    suspend fun redeploy(token: SecretValue, siteId: String) {
        val request = NetlifyRedeployRequest.build(token, siteId)
        val response = try {
            transport.send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostingApiException) {
            throw error
        } catch (_: ResponseTooLargeException) {
            throw failure(HostingFailureKind.INVALID_RESPONSE, "The Netlify response exceeded the safe size limit.")
        } catch (_: UnsafeRedirectException) {
            throw failure(HostingFailureKind.INVALID_RESPONSE, "Netlify returned an unsafe redirect.")
        } catch (_: IOException) {
            throw failure(HostingFailureKind.NETWORK, "Netlify could not be reached. Check your connection and try again.")
        }
        response.takeBody().fill(0)
        val status = response.statusCode
        if (status in 200..299) return
        throw HostingApiException(
            when (status) {
                401 -> HostingFailure(HostingFailureKind.AUTHENTICATION, "Netlify rejected this personal token.", status)
                403 -> HostingFailure(
                    HostingFailureKind.PERMISSION,
                    "Netlify denied access. Check that this token can deploy the site.",
                    status,
                )
                404 -> HostingFailure(HostingFailureKind.NOT_FOUND, "Netlify could not find this site.", status)
                422 -> HostingFailure(
                    HostingFailureKind.CONFIGURATION,
                    "Netlify could not start a build for this site. Check that it is linked to a repository.",
                    status,
                )
                429 -> HostingFailure(
                    HostingFailureKind.RATE_LIMITED,
                    "Netlify is rate limiting requests. Please try again shortly.",
                    status,
                )
                in 500..599 -> HostingFailure(HostingFailureKind.TEMPORARY, "Netlify is temporarily unavailable.", status)
                else -> HostingFailure(HostingFailureKind.TEMPORARY, "Unable to redeploy the Netlify site (HTTP $status).", status)
            },
        )
    }

    private fun failure(kind: HostingFailureKind, message: String) = HostingApiException(HostingFailure(kind, message))
}
