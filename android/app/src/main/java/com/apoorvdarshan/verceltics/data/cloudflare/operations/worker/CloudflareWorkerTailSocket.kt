package com.apoorvdarshan.verceltics.data.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A live-tail WebSocket URL returned by `POST .../tails`, accepted only for Cloudflare's tail host
 * over `wss` on the default port. iOS opens the URL as returned; Android pins it so a malformed or
 * unexpected response can never make the app connect anywhere else.
 */
class CloudflareTailEndpoint private constructor(val uri: URI) {
    val host: String get() = uri.host

    /** Request target for the HTTP upgrade (`/path?query`). */
    val requestTarget: String
        get() = (uri.rawPath?.takeIf(String::isNotEmpty) ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

    override fun toString(): String = "CloudflareTailEndpoint(host=$host, path=<redacted>)"

    companion object {
        const val TAIL_HOST: String = "tail.developers.workers.dev"

        fun parse(url: String): CloudflareTailEndpoint {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val valid = uri != null &&
                uri.scheme.equals("wss", ignoreCase = true) &&
                uri.host.equals(TAIL_HOST, ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                url.length <= 4_096 &&
                url.none { it.isISOControl() || it.isWhitespace() }
            if (!valid) throw CloudflareOperationException.invalidRequest("Cloudflare returned an invalid live-tail URL.")
            return CloudflareTailEndpoint(requireNotNull(uri))
        }
    }
}

/** An open live-tail stream. [receive] blocks for the next event and returns null once closed. */
interface CloudflareTailConnection {
    fun receive(): String?

    fun close()
}

fun interface CloudflareTailConnector {
    fun connect(endpoint: CloudflareTailEndpoint): CloudflareTailConnection
}

/**
 * Minimal RFC 6455 client over a hostname-verified TLS socket: text and binary messages, ping/pong
 * and close. Messages are bounded so a misbehaving server cannot exhaust memory.
 */
class SecureWebSocketTailConnector(
    private val connectTimeoutMillis: Int = 15_000,
    private val maximumMessageBytes: Int = DEFAULT_MAXIMUM_MESSAGE_BYTES,
) : CloudflareTailConnector {
    override fun connect(endpoint: CloudflareTailEndpoint): CloudflareTailConnection {
        val host = endpoint.host
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, 443), connectTimeoutMillis)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket(plain, host, 443, true) as SSLSocket
            try {
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(host))
                }
                socket.soTimeout = connectTimeoutMillis
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
                    throw IOException("The live-tail certificate did not match Cloudflare's tail host.")
                }
                val input = socket.inputStream.buffered()
                val output = socket.outputStream.buffered()
                val key = CloudflareWebSocketHandshake.newKey(RANDOM)
                output.write(CloudflareWebSocketHandshake.request(endpoint, key).toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                CloudflareWebSocketHandshake.validateResponse(CloudflareWebSocketHandshake.readHead(input), key)
                socket.soTimeout = 0
                return SocketTailConnection(socket, CloudflareWebSocketFrames(input, output, RANDOM, maximumMessageBytes))
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        } catch (error: Exception) {
            runCatching { plain.close() }
            throw error
        }
    }

    private class SocketTailConnection(
        private val socket: Socket,
        private val frames: CloudflareWebSocketFrames,
    ) : CloudflareTailConnection {
        private val closed = AtomicBoolean(false)

        override fun receive(): String? = try {
            if (closed.get()) null else frames.readMessage()?.let(::decodeTailMessage)
        } catch (error: IOException) {
            if (closed.get()) null else throw error
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { frames.sendClose() }
            runCatching { socket.close() }
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_MESSAGE_BYTES: Int = 1_024 * 1_024
        private val RANDOM = SecureRandom()
    }
}

/** iOS shows UTF-8 messages as text and anything else as `<N binary bytes>`. */
fun decodeTailMessage(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    "<${bytes.size} binary bytes>"
}

/** The HTTP/1.1 upgrade exchange (RFC 6455 section 4). */
internal object CloudflareWebSocketHandshake {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAXIMUM_HEAD_BYTES = 16 * 1_024

    fun newKey(random: SecureRandom): String = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    fun acceptFor(key: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(StandardCharsets.US_ASCII)),
    )

    fun request(endpoint: CloudflareTailEndpoint, key: String): String =
        "GET ${endpoint.requestTarget} HTTP/1.1\r\n" +
            "Host: ${endpoint.host}\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: trace-v1\r\n" +
            "User-Agent: Verceltics-Android/${BuildConfig.VERSION_NAME}\r\n" +
            "\r\n"

    fun readHead(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < terminator.size) {
            val next = input.read()
            if (next < 0) throw EOFException("The live-tail server closed the connection during the handshake.")
            output.write(next)
            if (output.size() > MAXIMUM_HEAD_BYTES) throw ProtocolException("The live-tail handshake response is too large.")
            matched = if (next.toByte() == terminator[matched]) matched + 1 else if (next.toByte() == terminator[0]) 1 else 0
        }
        return String(output.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    fun validateResponse(head: String, key: String) {
        val lines = head.split("\r\n").filter(String::isNotEmpty)
        val status = lines.firstOrNull().orEmpty().split(' ')
        if (status.size < 2 || !status[0].startsWith("HTTP/1.1") || status[1] != "101") {
            throw ProtocolException("Cloudflare refused the live-tail connection.")
        }
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase(Locale.ROOT) to line.substring(separator + 1).trim()
        }.groupBy({ it.first }, { it.second })
        if (headers["upgrade"]?.any { it.equals("websocket", ignoreCase = true) } != true) {
            throw ProtocolException("The live-tail server did not upgrade to WebSocket.")
        }
        if (headers["connection"]?.any { value -> value.split(',').any { it.trim().equals("upgrade", ignoreCase = true) } } != true) {
            throw ProtocolException("The live-tail server did not upgrade the connection.")
        }
        if (headers["sec-websocket-accept"]?.singleOrNull() != acceptFor(key)) {
            throw ProtocolException("The live-tail server returned an invalid WebSocket accept key.")
        }
    }
}

/** WebSocket framing (RFC 6455 section 5). Client frames are always masked; server frames never are. */
internal class CloudflareWebSocketFrames(
    private val input: InputStream,
    private val output: OutputStream,
    private val random: SecureRandom,
    private val maximumMessageBytes: Int,
) {
    /** Returns the next complete data message, or null after the server closes. */
    fun readMessage(): ByteArray? {
        val message = ByteArrayOutputStream()
        var inMessage = false
        while (true) {
            val first = readByte()
            val second = readByte()
            val fin = (first and 0x80) != 0
            if ((first and 0x70) != 0) throw ProtocolException("The live-tail server used unsupported WebSocket extensions.")
            val opcode = first and 0x0F
            if ((second and 0x80) != 0) throw ProtocolException("The live-tail server sent a masked frame.")
            val length = when (val short = second and 0x7F) {
                126 -> (readByte() shl 8) or readByte()
                127 -> {
                    var value = 0L
                    repeat(8) { value = (value shl 8) or readByte().toLong() }
                    if (value < 0 || value > maximumMessageBytes) throw ProtocolException("A live-tail event was too large.")
                    value.toInt()
                }
                else -> short
            }
            if (opcode >= 0x8 && (!fin || length > 125)) throw ProtocolException("The live-tail server sent an invalid control frame.")
            if (length > maximumMessageBytes) throw ProtocolException("A live-tail event was too large.")
            val payload = readFully(length)
            when (opcode) {
                OPCODE_CLOSE -> {
                    runCatching { writeFrame(OPCODE_CLOSE, payload.copyOf(minOf(payload.size, 2))) }
                    return null
                }
                OPCODE_PING -> writeFrame(OPCODE_PONG, payload)
                OPCODE_PONG -> Unit
                OPCODE_TEXT, OPCODE_BINARY -> {
                    if (inMessage) throw ProtocolException("The live-tail server interleaved WebSocket messages.")
                    inMessage = true
                    append(message, payload)
                    if (fin) return message.toByteArray()
                }
                OPCODE_CONTINUATION -> {
                    if (!inMessage) throw ProtocolException("The live-tail server sent an unexpected continuation frame.")
                    append(message, payload)
                    if (fin) return message.toByteArray()
                }
                else -> throw ProtocolException("The live-tail server sent an unknown WebSocket frame.")
            }
        }
    }

    @Synchronized
    fun writeFrame(opcode: Int, payload: ByteArray) {
        require(opcode in 0..0xF)
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size <= 125 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    /** Normal-closure frame (status 1000). */
    fun sendClose() = writeFrame(OPCODE_CLOSE, byteArrayOf(0x03, 0xE8.toByte()))

    private fun append(message: ByteArrayOutputStream, payload: ByteArray) {
        if (message.size() + payload.size > maximumMessageBytes) throw ProtocolException("A live-tail event was too large.")
        message.write(payload)
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("The live-tail connection closed.")
        return value
    }

    private fun readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) throw EOFException("The live-tail connection closed.")
            offset += count
        }
        return buffer
    }

    companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
    }
}
