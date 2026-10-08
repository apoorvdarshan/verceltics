package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailConnection
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailConnector
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailEndpoint
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerTailSession
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.SecureWebSocketTailConnector
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.prettyJson
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudflareWorkerTailLine(val id: Long, val timestamp: Instant, val text: String)

data class CloudflareWorkerLiveTailState(
    val lines: List<CloudflareWorkerTailLine> = emptyList(),
    val status: String = "Start a live tail to stream events",
    val isConnected: Boolean = false,
    val isStarting: Boolean = false,
    val error: String? = null,
)

/** Best-effort cleanup that outlives the screen, so leaving never strands a tail session. */
object CloudflareTailCleanup {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * Port of iOS `CloudflareWorkerLiveTailViewModel`. Creating a tail session is a Cloudflare mutation,
 * so it starts only after the user confirms; the session is deleted when the screen goes away.
 */
class CloudflareWorkerLiveTailViewModel(
    private val api: CloudflareWorkerOperationsApi,
    val accountId: String,
    val scriptName: String,
    private val connector: CloudflareTailConnector = SecureWebSocketTailConnector(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupScope: CoroutineScope = CloudflareTailCleanup.scope,
    private val now: () -> Instant = Instant::now,
) : CloudflareOperationsViewModel() {
    private val _state = MutableStateFlow(CloudflareWorkerLiveTailState())
    val state: StateFlow<CloudflareWorkerLiveTailState> = _state.asStateFlow()

    private var tail: CloudflareWorkerTailSession? = null
    private var connection: CloudflareTailConnection? = null
    private var receiveJob: Job? = null
    private val lineIds = AtomicLong()

    fun requestStart() {
        if (connection != null || _state.value.isStarting) return
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Start live tail?",
                message = "Cloudflare creates a temporary tail session for $scriptName and streams its request logs, " +
                    "console output and exceptions to this device until you stop it or leave this screen.",
                confirmLabel = "Start live tail",
                resourceId = scriptName,
                workingId = TAIL,
            ),
        ) { confirmation -> start(confirmation) }
    }

    private suspend fun start(confirmation: CloudflareMutationConfirmation) {
        if (connection != null) return
        _state.update { it.copy(status = "Creating live tail…", error = null, isStarting = true) }
        try {
            val created = api.createTail(accountId, scriptName, confirmation)
            tail = created
            val endpoint = CloudflareTailEndpoint.parse(created.url)
            _state.update { it.copy(status = "Connecting securely…") }
            val opened = withContext(ioDispatcher) { connector.connect(endpoint) }
            connection = opened
            _state.update { it.copy(isConnected = true, isStarting = false, status = "Listening for Worker events") }
            receiveJob = viewModelScope.launch(ioDispatcher) { receiveLoop(opened) }
        } catch (error: CancellationException) {
            cleanUp()
            throw error
        } catch (error: Exception) {
            _state.update {
                it.copy(isStarting = false, isConnected = false, status = "Tail unavailable", error = liveTailMessage(error))
            }
            cleanUp()
        }
    }

    private suspend fun receiveLoop(opened: CloudflareTailConnection) {
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val message = opened.receive() ?: break
                append(message)
            }
            if (connection === opened) {
                _state.update { it.copy(isConnected = false, status = "Live tail disconnected") }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (connection === opened) {
                _state.update {
                    it.copy(isConnected = false, status = "Live tail disconnected", error = liveTailMessage(error))
                }
            }
        }
    }

    /** iOS `append`: pretty JSON when the event is JSON, newest 500 events kept. */
    internal fun append(raw: String) {
        val formatted = runCatching { ProviderJsonParser.parse(raw).prettyJson() }.getOrNull() ?: raw
        val line = CloudflareWorkerTailLine(lineIds.incrementAndGet(), now(), formatted)
        _state.update { it.copy(lines = (it.lines + line).takeLast(MAXIMUM_LINES)) }
    }

    /** Stops streaming and deletes the tail session (iOS `stop`). Safe to call repeatedly. */
    fun stop() {
        val wasActive = connection != null || tail != null
        cleanUp()
        if (wasActive) _state.update { it.copy(isConnected = false, isStarting = false, status = "Tail stopped") }
    }

    private fun cleanUp() {
        receiveJob?.cancel()
        receiveJob = null
        val closing = connection
        connection = null
        val session = tail
        tail = null
        if (closing == null && session == null) return
        cleanupScope.launch {
            closing?.let { runCatching { it.close() } }
            session?.let { runCatching { api.deleteTail(accountId, scriptName, it) } }
        }
    }

    override fun onCleared() {
        cleanUp()
        super.onCleared()
    }

    private fun liveTailMessage(error: Throwable): String = when (error) {
        is java.io.IOException -> "The live-tail connection failed. Check your connection and try again."
        else -> cloudflareUserMessage(error)
    }

    companion object {
        const val TAIL: String = "tail"
        const val MAXIMUM_LINES: Int = 500
    }
}
