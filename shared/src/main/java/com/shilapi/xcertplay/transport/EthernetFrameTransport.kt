package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Ethernet packets supplied by USB NCM or an explicitly selected local lab bridge. */
interface EthernetFrameTransport : Closeable {
    fun send(frame: ByteArray, timeoutMillis: Int)
    fun recv(timeoutMillis: Long): ByteArray?
}
