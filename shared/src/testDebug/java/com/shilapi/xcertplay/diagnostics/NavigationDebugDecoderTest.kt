package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

class NavigationDebugDecoderTest {
    @Test fun preservesRepeatedUnknownFieldsAndDecodesUnsignedDistance() {
        val body = Iap2ParameterList.of(
            Iap2Parameter(10, byteArrayOf(-1, -1, -1, -1)),
            Iap2Parameter(3, "Street\nName\u0000ignored".toByteArray()),
            Iap2Parameter(99, byteArrayOf(1)), Iap2Parameter(99, byteArrayOf(2)),
        ).encode()
        val text = NavigationDebugDecoder.decode(Iap2Frame(0x5201, body))!!
        assertTrue(text.contains("distanceToManeuverMeters=4294967295"))
        assertTrue(text.contains("currentRoad=Street Name"))
        assertFalse(text.contains("ignored"))
        assertTrue(text.contains("0063[1] raw | 01"))
        assertTrue(text.contains("0063[1] raw | 02"))
    }
    @Test fun malformedPacketCannotBreakSessionAndShortIntegerRemainsRaw() {
        assertTrue(NavigationDebugDecoder.decode(Iap2Frame(0x5201, byteArrayOf(0, 8, 0)))!!.contains("MALFORMED"))
        val payload = Iap2ParameterList.of(Iap2Parameter(10, byteArrayOf(42))).encode()
        assertTrue(NavigationDebugDecoder.decode(Iap2Frame(0x5201, payload))!!.contains("000a[1] raw | 2a"))
    }
    @Test fun ignoresNonNavigationTraffic() {
        assertNull(NavigationDebugDecoder.decode(Iap2Frame(0x4155, byteArrayOf(1, 2, 3))))
    }
}
