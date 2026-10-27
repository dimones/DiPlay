package com.shilapi.xcertplay.transport

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class LoopbackDuplexByteStreamTest {
    @Test fun preservesBytesAcrossReadsAndDistinguishesTimeoutFromEof() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            LoopbackDuplexByteStream(server.localPort).use { client ->
                server.accept().use { peer ->
                    peer.soTimeout = 1_000
                    assertNull(client.recv(8, 20))
                    peer.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                    val received = java.io.ByteArrayOutputStream()
                    while (received.size() < 4) received.write(client.recv(2, 1_000)!!)
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), received.toByteArray())
                    client.send(byteArrayOf(7, 8))
                    assertEquals(7, peer.getInputStream().read())
                    assertEquals(8, peer.getInputStream().read())
                    peer.shutdownOutput()
                    assertArrayEquals(byteArrayOf(), client.recv(8, 1_000))
                }
            }
        }
    }
}
