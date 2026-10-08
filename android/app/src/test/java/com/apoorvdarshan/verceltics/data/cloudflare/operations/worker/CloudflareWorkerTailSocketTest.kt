package com.apoorvdarshan.verceltics.data.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ProtocolException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareWorkerTailSocketTest {
    @Test
    fun onlyCloudflareTailHostOverWssIsAccepted() {
        val endpoint = CloudflareTailEndpoint.parse("wss://tail.developers.workers.dev/abc123?x=1")
        assertEquals("tail.developers.workers.dev", endpoint.host)
        assertEquals("/abc123?x=1", endpoint.requestTarget)
        assertEquals("/", CloudflareTailEndpoint.parse("wss://TAIL.developers.workers.dev").requestTarget)
        assertTrue(!endpoint.toString().contains("abc123"))

        listOf(
            "ws://tail.developers.workers.dev/abc",
            "https://tail.developers.workers.dev/abc",
            "wss://evil.example/abc",
            "wss://tail.developers.workers.dev.evil.example/abc",
            "wss://my-worker.workers.dev/abc",
            "wss://tail.developers.workers.dev:8443/abc",
            "wss://user@tail.developers.workers.dev/abc",
            "wss://tail.developers.workers.dev/abc#frag",
            "wss://tail.developers.workers.dev/a b",
            "not a url",
        ).forEach { url ->
            try {
                CloudflareTailEndpoint.parse(url)
                fail("Accepted $url")
            } catch (error: CloudflareOperationException) {
                assertEquals("Cloudflare returned an invalid live-tail URL.", error.userMessage)
            }
        }
    }

    @Test
    fun handshakeUsesRfcAcceptKeyAndRejectsBadUpgrades() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", CloudflareWebSocketHandshake.acceptFor(key))
        val request = CloudflareWebSocketHandshake.request(CloudflareTailEndpoint.parse("wss://tail.developers.workers.dev/t1"), key)
        assertTrue(request.startsWith("GET /t1 HTTP/1.1\r\nHost: tail.developers.workers.dev\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Key: $key\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Protocol: trace-v1\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))

        val good = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        val head = CloudflareWebSocketHandshake.readHead(ByteArrayInputStream((good + "frame-bytes").toByteArray()))
        assertEquals(good, head)
        CloudflareWebSocketHandshake.validateResponse(head, key)

        listOf(
            good.replace("101 Switching", "200 OK"),
            good.replace("Upgrade: websocket\r\n", ""),
            good.replace("keep-alive, Upgrade", "keep-alive"),
            good.replace("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "wrong="),
        ).forEach { response ->
            try {
                CloudflareWebSocketHandshake.validateResponse(response, key)
                fail("Accepted $response")
            } catch (_: ProtocolException) {
            }
        }
        try {
            CloudflareWebSocketHandshake.readHead(ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() }))
            fail("Unbounded head accepted")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun framesAssembleFragmentsAnswerPingsAndStopOnClose() {
        val server = ByteArrayOutputStream()
        server.write(frame(fin = false, opcode = 0x1, payload = "{\"a\":".toByteArray()))
        server.write(frame(fin = true, opcode = 0x9, payload = "hi".toByteArray()))
        server.write(frame(fin = true, opcode = 0x0, payload = "1}".toByteArray()))
        server.write(frame(fin = true, opcode = 0x2, payload = ByteArray(300) { 7 }))
        server.write(frame(fin = true, opcode = 0x8, payload = byteArrayOf(0x03, 0xE8.toByte())))
        val written = ByteArrayOutputStream()
        val frames = CloudflareWebSocketFrames(ByteArrayInputStream(server.toByteArray()), written, SecureRandom(), 1_024)

        assertEquals("{\"a\":1}", String(frames.readMessage()!!, StandardCharsets.UTF_8))
        assertArrayEquals(ByteArray(300) { 7 }, frames.readMessage())
        assertNull(frames.readMessage())

        val replies = parseClientFrames(written.toByteArray())
        assertEquals(listOf(0xA to "hi", 0x8 to "\u0003è"), replies.map { it.first to String(it.second, StandardCharsets.ISO_8859_1) })
    }

    @Test
    fun maskedOversizedAndUnexpectedFramesAreRejected() {
        expectProtocolError(frame(true, 0x1, "x".toByteArray(), masked = true))
        expectProtocolError(frame(true, 0x1, ByteArray(2_000)))
        expectProtocolError(frame(true, 0x0, "orphan".toByteArray()))
        expectProtocolError(frame(false, 0x9, "ping".toByteArray()))
        expectProtocolError(frame(true, 0x3, ByteArray(0)))
        expectProtocolError(frame(false, 0x1, ByteArray(600)) + frame(true, 0x0, ByteArray(600)))
        try {
            CloudflareWebSocketFrames(ByteArrayInputStream(byteArrayOf(0x81.toByte())), ByteArrayOutputStream(), SecureRandom(), 1_024).readMessage()
            fail("Truncated frame accepted")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun clientFramesAreAlwaysMasked() {
        val written = ByteArrayOutputStream()
        val frames = CloudflareWebSocketFrames(ByteArrayInputStream(ByteArray(0)), written, SecureRandom(), 1_024)
        frames.writeFrame(0x1, ByteArray(70_000) { 1 })
        val bytes = written.toByteArray()
        assertEquals(0x81, bytes[0].toInt() and 0xFF)
        assertEquals(0x80 or 127, bytes[1].toInt() and 0xFF)
        val (opcode, payload) = parseClientFrames(bytes).single()
        assertEquals(0x1, opcode)
        assertArrayEquals(ByteArray(70_000) { 1 }, payload)
    }

    @Test
    fun nonUtf8MessagesAreSummarized() {
        assertEquals("hello", decodeTailMessage("hello".toByteArray()))
        assertEquals("<2 binary bytes>", decodeTailMessage(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    private fun expectProtocolError(bytes: ByteArray) {
        try {
            CloudflareWebSocketFrames(ByteArrayInputStream(bytes), ByteArrayOutputStream(), SecureRandom(), 1_024).readMessage()
            fail("Invalid frame accepted")
        } catch (_: ProtocolException) {
        }
    }

    private fun frame(fin: Boolean, opcode: Int, payload: ByteArray, masked: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size <= 125 -> out.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        if (masked) out.write(byteArrayOf(1, 2, 3, 4))
        out.write(payload)
        return out.toByteArray()
    }

    /** Decodes masked client frames into (opcode, unmasked payload). */
    private fun parseClientFrames(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var index = 0
        while (index < bytes.size) {
            val opcode = bytes[index].toInt() and 0x0F
            val second = bytes[index + 1].toInt() and 0xFF
            assertTrue("Client frames must be masked", (second and 0x80) != 0)
            var length = second and 0x7F
            index += 2
            if (length == 126) {
                length = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
                index += 2
            } else if (length == 127) {
                var value = 0L
                repeat(8) { value = (value shl 8) or (bytes[index + it].toLong() and 0xFF) }
                length = value.toInt()
                index += 8
            }
            val mask = bytes.copyOfRange(index, index + 4)
            index += 4
            val payload = ByteArray(length) { (bytes[index + it].toInt() xor mask[it % 4].toInt()).toByte() }
            index += length
            result += opcode to payload
        }
        return result
    }
}
