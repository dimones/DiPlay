package com.shilapi.xcertplay.transport

import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LoopbackEthernetTransportTest {
    @Test fun retainsPartialHeadersAndBodiesAcrossTimeouts() {
        val headerSent = CountDownLatch(1)
        val continueHeader = CountDownLatch(1)
        val bodySent = CountDownLatch(1)
        val continueBody = CountDownLatch(1)
        val frame = ByteArray(80) { it.toByte() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val task = worker.submit {
                    server.accept().use { peer ->
                        val output = DataOutputStream(peer.getOutputStream())
                        output.write(byteArrayOf(68, 73, 80, 78, 2, 3, 4, 5, 6, 7))
                        output.write(byteArrayOf(0, 0)); output.flush(); headerSent.countDown()
                        check(continueHeader.await(3, TimeUnit.SECONDS))
                        output.write(byteArrayOf(0, 80)); output.write(frame, 0, 20)
                        output.flush(); bodySent.countDown()
                        check(continueBody.await(3, TimeUnit.SECONDS))
                        output.write(frame, 20, 60); output.flush()
                    }
                }
                LoopbackEthernetTransport(server.localPort).use { client ->
                    assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6, 7), client.hostMac)
                    assertTrue(headerSent.await(1, TimeUnit.SECONDS))
                    assertNull(client.recv(30)); continueHeader.countDown()
                    assertTrue(bodySent.await(1, TimeUnit.SECONDS))
                    assertNull(client.recv(30)); continueBody.countDown()
                    assertArrayEquals(frame, client.recv(1000))
                    try { client.recv(1000); fail("EOF must end the bridge") } catch (_: EOFException) {}
                }
                task.get(3, TimeUnit.SECONDS)
            }
        } finally { continueHeader.countDown(); continueBody.countDown(); worker.shutdownNow() }
    }

    @Test fun rejectsUnboundedFrameBeforeAllocation() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val task = worker.submit {
                    server.accept().use { peer ->
                        val output = DataOutputStream(peer.getOutputStream())
                        output.write(byteArrayOf(68, 73, 80, 78, 2, 3, 4, 5, 6, 7))
                        output.writeInt(Int.MAX_VALUE); output.flush()
                    }
                }
                LoopbackEthernetTransport(server.localPort).use { client ->
                    try { client.recv(1000); fail("Oversized frame must fail") }
                    catch (error: IOException) { assertTrue(error.message!!.contains("frame length")) }
                }
                task.get(3, TimeUnit.SECONDS)
            }
        } finally { worker.shutdownNow() }
    }
}
