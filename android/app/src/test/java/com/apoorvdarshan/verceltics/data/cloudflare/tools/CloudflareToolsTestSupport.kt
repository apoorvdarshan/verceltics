package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.nio.charset.StandardCharsets

/** Records every request and answers from a scripted handler. Never touches the network. */
class RecordingToolsTransport(
    private val handler: (CloudflareToolsHttpRequest) -> HttpResponse = { jsonResponse(200, """{"success":true,"result":{}}""") },
) : CloudflareToolsTransport {
    val requests = mutableListOf<CloudflareToolsHttpRequest>()
    val tokens = mutableListOf<String>()
    var cancelledCalls = 0
        private set

    override fun newCall(request: CloudflareToolsHttpRequest, token: SecretValue): CancelableCall<HttpResponse> =
        object : CancelableCall<HttpResponse> {
            override fun execute(): HttpResponse {
                requests += request
                tokens += token.use { it }
                return handler(request)
            }

            override fun cancel() {
                cancelledCalls += 1
            }
        }

    val lastBody: String?
        get() = requests.lastOrNull()?.bodyCopy()?.let { String(it, StandardCharsets.UTF_8) }
}

fun jsonResponse(status: Int, body: String, headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json"))) =
    HttpResponse(status, body.toByteArray(StandardCharsets.UTF_8), headers)
