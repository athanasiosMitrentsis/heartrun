package de.mitre.heartrun.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uint8WithoutRr() {
        val s = HeartRateParser.parse(bytes(0x00, 72))!!
        assertEquals(72, s.bpm)
        assertEquals(emptyList<Int>(), s.rrMs)
        assertNull(s.sensorContact)
    }

    @Test
    fun uint16() {
        val s = HeartRateParser.parse(bytes(0x01, 0x2C, 0x01))!!
        assertEquals(300, s.bpm)
    }

    @Test
    fun uint8WithContactEnergyAndTwoRr() {
        // flags: contact supported + detected, energy, RR
        val s = HeartRateParser.parse(
            bytes(0x1E, 150, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02),
        )!!
        assertEquals(150, s.bpm)
        assertEquals(true, s.sensorContact)
        // 0x0400 = 1024 -> 1000 ms, 0x0200 = 512 -> 500 ms
        assertEquals(listOf(1000, 500), s.rrMs)
    }

    @Test
    fun contactSupportedButNotDetected() {
        val s = HeartRateParser.parse(bytes(0x04, 60))!!
        assertEquals(false, s.sensorContact)
    }

    @Test
    fun invalidInput() {
        assertNull(HeartRateParser.parse(ByteArray(0)))
        assertNull(HeartRateParser.parse(bytes(0x00)))
        assertNull(HeartRateParser.parse(bytes(0x01, 0x10)))
    }
}
