package com.apoorvdarshan.verceltics.data.vercel

import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicEndpointSSLSocketFactoryTest {
    @Test
    fun tlsIsLayeredOnlyOverSocketsConnectedToPublicAddresses() {
        val delegate = RecordingFactory()
        val factory = PublicEndpointSSLSocketFactory(delegate)
        val publicSocket = FakeSocket(InetAddress.getByName("76.76.21.21"))

        val layered = factory.createSocket(publicSocket, "studio.example", 443, true)

        assertSame(delegate.result, layered)
        assertEquals(listOf("studio.example"), delegate.layeredHosts)
        assertFalse(publicSocket.closed)
    }

    @Test
    fun rebindingToAPrivateAddressIsRefusedBeforeTlsStarts() {
        listOf("10.0.0.5", "127.0.0.1", "169.254.169.254", "192.168.1.20", "::1", "fd00::1").forEach { literal ->
            val delegate = RecordingFactory()
            val socket = FakeSocket(InetAddress.getByName(literal))

            assertThrows(SocketException::class.java) {
                PublicEndpointSSLSocketFactory(delegate).createSocket(socket, "studio.example", 443, true)
            }
            assertTrue("$literal: the raw socket is closed.", socket.closed)
            assertTrue("$literal: no TLS handshake was attempted.", delegate.layeredHosts.isEmpty())
        }
    }

    @Test
    fun unconnectedSocketsAreRefused() {
        val factory = PublicEndpointSSLSocketFactory(RecordingFactory())

        assertThrows(SocketException::class.java) { factory.createSocket(FakeSocket(null), "studio.example", 443, true) }
        assertThrows(SocketException::class.java) { factory.createSocket() }
    }

    private class FakeSocket(private val remote: InetAddress?) : Socket() {
        var closed = false

        override fun getInetAddress(): InetAddress? = remote

        override fun close() {
            closed = true
        }
    }

    private class RecordingFactory : SSLSocketFactory() {
        val layeredHosts = mutableListOf<String?>()
        val result = Socket()

        override fun getDefaultCipherSuites(): Array<String> = emptyArray()

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
            layeredHosts += host
            return result
        }

        override fun createSocket(host: String?, port: Int): Socket = result

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = result

        override fun createSocket(host: InetAddress?, port: Int): Socket = result

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = result
    }
}
