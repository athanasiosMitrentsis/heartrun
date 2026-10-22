package de.mitre.heartrun.ble

/**
 * One Heart Rate Measurement notification. [elapsedRealtimeNanos] is set by the caller
 * on arrival, because the packet itself has no timestamp (ADR 0001, Entscheidung 4).
 */
data class HeartRateSample(
    val bpm: Int,
    val rrMs: List<Int>,
    val sensorContact: Boolean?,
    val elapsedRealtimeNanos: Long = 0L,
)

/** Parses the Heart Rate Measurement characteristic. The flags come from byte 0 of the value. */
object HeartRateParser {
    private const val FLAG_HR_UINT16 = 0x01
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_ENERGY_EXPENDED = 0x08
    private const val FLAG_RR_INTERVALS = 0x10

    fun parse(value: ByteArray): HeartRateSample? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        var offset = 1

        val bpm: Int
        if (flags and FLAG_HR_UINT16 != 0) {
            if (value.size < offset + 2) return null
            bpm = uint16(value, offset)
            offset += 2
        } else {
            if (value.size < offset + 1) return null
            bpm = value[offset].toInt() and 0xFF
            offset += 1
        }

        val sensorContact =
            if (flags and FLAG_CONTACT_SUPPORTED != 0) flags and FLAG_CONTACT_DETECTED != 0 else null

        if (flags and FLAG_ENERGY_EXPENDED != 0) offset += 2

        val rrMs = buildList {
            if (flags and FLAG_RR_INTERVALS != 0) {
                while (offset + 1 < value.size) {
                    // RR is in units of 1/1024 s.
                    add((uint16(value, offset) * 1000L / 1024L).toInt())
                    offset += 2
                }
            }
        }

        return HeartRateSample(bpm = bpm, rrMs = rrMs, sensorContact = sensorContact)
    }

    private fun uint16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
}
