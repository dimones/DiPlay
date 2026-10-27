package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Framed loopback Ethernet connection. Partial frames survive receive timeouts. */
class LoopbackEthernetTransport(port: Int = 19778) : EthernetFrameTransport {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: DataOutputStream
    val hostMac: ByteArray
    private val header = ByteArray(4)
    private var headerUsed = 0
    private var body: ByteArray? = null
    private var bodyUsed = 0
    private val readLock = Any()
    private val writeLock = Any()

    init {
        require(port in 1..65535)
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 5_000
            input = DataInputStream(socket.getInputStream())
            output = DataOutputStream(socket.getOutputStream())
            val magic = ByteArray(4).also(input::readFully)
            check(magic.contentEquals(byteArrayOf(68, 73, 80, 78))) { "Invalid NCM relay handshake" }
            hostMac = ByteArray(6).also(input::readFully)
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    override fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        require(timeoutMillis > 0)
        require(frame.size in 54..2048)
        try {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        } catch (error: IOException) {
            socket.close()
            throw error
        }
    }

    override fun recv(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(timeoutMillis in 1..Int.MAX_VALUE.toLong())
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        try {
            while (true) {
                val remaining = (deadline - System.nanoTime() + 999_999L) / 1_000_000L
                if (remaining <= 0) return@synchronized null
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val current = body
                if (current == null) {
                    val n = input.read(header, headerUsed, header.size - headerUsed)
                    if (n < 0) throw EOFException("NCM relay closed")
                    headerUsed += n
                    if (headerUsed == 4) {
                        val size = java.nio.ByteBuffer.wrap(header).int
                        if (size !in 54..2048) throw IOException("Invalid Ethernet frame length: $size")
                        body = ByteArray(size)
                        bodyUsed = 0
                    }
                } else {
                    val n = input.read(current, bodyUsed, current.size - bodyUsed)
                    if (n < 0) throw EOFException("NCM relay closed mid-frame")
                    bodyUsed += n
                    if (bodyUsed == current.size) {
                        body = null
                        bodyUsed = 0
                        headerUsed = 0
                        return@synchronized current
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: IOException) {
            socket.close()
            throw error
        }
    }

    override fun close() = socket.close()
}
