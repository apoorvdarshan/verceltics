package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/** A parsed JSON body plus the (non-sensitive) response headers. */
internal class JsonPage(val value: JsonValue, private val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Shared per-provider request plumbing: fixed origin + base path, authentication, safe error
 * classification and bounded JSON parsing. Provider response text is never surfaced to users.
 */
internal class HostingRequestClient(
    val provider: HostingProvider,
    private val transport: HostingHttpTransport,
    private val endpoint: HostingEndpoint,
    private val basePath: List<String>,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val authProvider: suspend () -> HostingAuth,
) {
    suspend fun json(
        method: HostingHttpMethod = HostingHttpMethod.GET,
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        operation: String,
    ): JsonValue = jsonWithHeaders(method, path, query, headers, body, operation).value

    suspend fun jsonWithHeaders(
        method: HostingHttpMethod = HostingHttpMethod.GET,
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        operation: String,
    ): JsonPage {
        val response = send(method, path, query, headers, body, operation)
        val bytes = response.takeBody()
        return try {
            val value = if (bytes.all { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }) {
                JsonObject.EMPTY
            } else {
                HostingJson.parse(bytes)
            }
            JsonPage(value, response.headers)
        } catch (_: HostingResponseFormatException) {
            throw HostingApiException(
                HostingFailure(HostingFailureKind.INVALID_RESPONSE, "${provider.displayName} returned an invalid response."),
            )
        } finally {
            bytes.fill(0)
        }
    }

    /** Sends a write request; the response body is discarded after the status check. */
    suspend fun execute(
        method: HostingHttpMethod,
        path: List<String>,
        body: String? = null,
        operation: String,
    ) {
        send(method, path, emptyList(), emptyMap(), body, operation).takeBody().fill(0)
    }

    private suspend fun send(
        method: HostingHttpMethod,
        path: List<String>,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        body: String?,
        operation: String,
    ): HttpResponse {
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
        val request = try {
            HostingHttpRequest(
                endpoint = endpoint,
                method = method,
                pathSegments = basePath + path,
                query = query,
                headers = defaultHeaders + headers,
                body = bodyBytes,
                auth = authProvider(),
            )
        } finally {
            bodyBytes?.fill(0)
        }
        val response = try {
            transport.send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostingApiException) {
            throw error
        } catch (_: ResponseTooLargeException) {
            throw failure(HostingFailureKind.INVALID_RESPONSE, "The ${provider.displayName} response exceeded the safe size limit.")
        } catch (_: UnsafeRedirectException) {
            throw failure(HostingFailureKind.INVALID_RESPONSE, "${provider.displayName} returned an unsafe redirect.")
        } catch (_: IOException) {
            throw failure(
                HostingFailureKind.NETWORK,
                "${provider.displayName} could not be reached. Check your connection and try again.",
            )
        }
        requireSuccessful(response, operation)
        return response
    }

    private fun requireSuccessful(response: HttpResponse, operation: String) {
        val status = response.statusCode
        if (status in 200..299) return
        response.takeBody().fill(0)
        val name = provider.displayName
        val awsErrorType = response.headers.entries
            .firstOrNull { it.key.equals("x-amzn-ErrorType", ignoreCase = true) }
            ?.value?.firstOrNull().orEmpty()
        val failure = when {
            provider == HostingProvider.FIREBASE && status == 401 -> HostingFailure(
                HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED,
                "Google authorization expired. Continue with Google to reconnect Firebase Hosting.",
                status,
            )
            provider == HostingProvider.FIREBASE && status == 403 -> HostingFailure(
                HostingFailureKind.PERMISSION,
                "Google denied access. Check the project ID, that the Firebase Hosting API is enabled, " +
                    "and that this Google account can access the project.",
                status,
            )
            provider == HostingProvider.AWS_AMPLIFY && (status == 401 || status == 403 || AWS_AUTH_ERRORS.any { it in awsErrorType }) ->
                HostingFailure(
                    HostingFailureKind.AUTHENTICATION,
                    "AWS rejected these credentials or they lack Amplify permissions.",
                    status,
                )
            status == 401 -> HostingFailure(HostingFailureKind.AUTHENTICATION, "$name rejected these credentials.", status)
            status == 403 -> HostingFailure(
                HostingFailureKind.PERMISSION,
                "$name denied access. Check the credential's permissions.",
                status,
            )
            status == 404 -> HostingFailure(
                HostingFailureKind.NOT_FOUND,
                "$name could not find the requested resource.",
                status,
            )
            status == 408 -> HostingFailure(HostingFailureKind.NETWORK, "$name timed out while trying to $operation.", status)
            status == 429 -> HostingFailure(
                HostingFailureKind.RATE_LIMITED,
                "$name is rate limiting requests. Please try again shortly.",
                status,
            )
            status in 500..599 -> HostingFailure(HostingFailureKind.TEMPORARY, "$name is temporarily unavailable.", status)
            else -> HostingFailure(HostingFailureKind.TEMPORARY, "Unable to $operation (HTTP $status).", status)
        }
        throw HostingApiException(failure)
    }

    private fun failure(kind: HostingFailureKind, message: String) = HostingApiException(HostingFailure(kind, message))

    private companion object {
        val AWS_AUTH_ERRORS = listOf(
            "UnrecognizedClientException",
            "InvalidSignatureException",
            "IncompleteSignatureException",
            "ExpiredTokenException",
            "AccessDenied",
            "MissingAuthenticationToken",
        )
    }
}
