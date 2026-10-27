package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Debug-only packet inspection. Unknown fields stay raw; no inferred route state. */
object NavigationDebugDecoder {
    fun name(id: Int): String? = when (id) {
        0x5201 -> "RouteGuidance"
        0x5202 -> "Maneuver"
        0x5204 -> "LaneGuidance"
        else -> null
    }

    fun decode(frame: Iap2Frame): String? {
        val name = name(frame.messageId) ?: return null
        val header = "0x%04x %s (%d B)".format(frame.messageId, name, frame.payload.size)
        return try {
            val fields = frame.body().asList()
            buildString {
                append(header)
                for (field in fields.take(48)) {
                    val data = field.payload
                    val decoded = when (frame.messageId) {
                        0x5201 -> when (field.id) {
                            1 -> number(data, 1)?.let { "state=$it" + when (it) { "0" -> " (no route)"; "2" -> " (arrived)"; else -> "" } }
                            3 -> "currentRoad=${string(data)}"
                            5 -> number(data, 8)?.let { "arrivalEpochSeconds=$it" }
                            6 -> number(data, 8)?.let { "remainingSeconds=$it" }
                            7 -> number(data, 4)?.let { "remainingMeters=$it" }
                            10 -> number(data, 4)?.let { "distanceToManeuverMeters=$it" }
                            13 -> if (data.size % 2 == 0) "maneuverIndexes=" + data.asList().chunked(2).joinToString(",") { number(it.toByteArray(), 2)!! } else null
                            else -> null
                        }
                        0x5202 -> when (field.id) {
                            1 -> number(data, 2)?.let { "maneuverIndex=$it" }
                            3 -> number(data, 1)?.let { "maneuverType=$it" }
                            4 -> "afterRoad=${string(data)}"
                            8 -> number(data, 1)?.let { "drivingSide=$it" }
                            else -> null
                        }
                        else -> null
                    }
                    append("\n  %04x[%d] ".format(field.id, data.size))
                    append(decoded ?: "raw")
                    append(" | ").append(hex(data, 32))
                }
                if (fields.size > 48) append("\n  … remaining fields omitted from display")
                if (frame.messageId == 0x5204) {
                    for (lane in Iap2LaneGuidance.parse(frame.payload).take(8)) {
                        append("\n  lane=${lane.index} status=${lane.status} angle=${lane.angle} highlight=${lane.highlightAngle}")
                    }
                }
            }
        } catch (_: Exception) {
            "$header\n  MALFORMED TLV | ${hex(frame.payload, 64)}"
        }
    }

    fun hex(data: ByteArray, limit: Int = data.size): String =
        data.take(limit).joinToString("") { "%02x".format(it.toInt() and 255) } + if (data.size > limit) "…" else ""

    private fun number(data: ByteArray, width: Int): String? {
        if (data.size != width) return null
        return data.fold(0uL) { value, byte -> (value shl 8) or (byte.toInt() and 255).toULong() }.toString()
    }

    private fun string(data: ByteArray): String = data.takeWhile { it != 0.toByte() }.toByteArray()
        .toString(Charsets.UTF_8).take(160).map { if (it.isISOControl()) ' ' else it }.joinToString("")
}
