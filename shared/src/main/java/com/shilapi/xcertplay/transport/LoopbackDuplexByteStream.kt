package com.shilapi.xcertplay.transport

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Local lab bridge reached through an explicit adb reverse mapping. */
class LoopbackDuplexByteStream(port: Int) : BlockingDuplexByteStream {
    private val socket = Socket()
    init {
        require(port in 1..65535)
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.tcpNoDelay = true
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val readLock = Any()
    private val writeLock = Any()

    override fun send(data: ByteArray) = synchronized(writeLock) {
        output.write(data)
        output.flush()
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(maxBytes in 1..1_048_576)
        require(timeoutMillis in 1..Int.MAX_VALUE.toLong())
        socket.soTimeout = timeoutMillis.toInt()
        val buffer = ByteArray(maxBytes)
        try {
            val count = input.read(buffer)
            if (count < 0) byteArrayOf() else buffer.copyOf(count)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()
}
