package com.sirisakboy.ecuobd2

import com.sirisakboy.ecuobd2.protocol.HondaConstants
import com.sirisakboy.ecuobd2.protocol.ObdConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolUnitTest {

    @Test
    fun testHondaChecksum() {
        val testBytes = byteArrayOf(0x72.toByte(), 0x02.toByte(), 0x74.toByte(), 0x01.toByte())
        val ck = HondaConstants.checksum8bitHonda(testBytes)
        assertEquals(0x17, ck.toInt() and 0xFF)

        val fullMsg = byteArrayOf(0x72.toByte(), 0x05.toByte(), 0x71.toByte(), 0x00.toByte(), 0x10.toByte(), 0x08.toByte())
        val fullSum = HondaConstants.checksum8bitHonda(fullMsg)
        assertEquals(0x00, fullSum.toInt() and 0xFF)
    }

    @Test
    fun testFormatHondaMessage() {
        val msg = HondaConstants.formatMessage(0x72.toByte(), byteArrayOf(0x74.toByte(), 0x01.toByte()))
        assertEquals(5, msg.size)
        assertEquals(0x72.toByte(), msg[0])
        assertEquals(0x05.toByte(), msg[1])
        assertEquals(0x74.toByte(), msg[2])
        assertEquals(0x01.toByte(), msg[3])
        assertEquals(0x14.toByte(), msg[4])
    }

    @Test
    fun testFormatReadAddress() {
        val addrBytes = HondaConstants.formatReadAddress(0x123456)
        assertEquals(3, addrBytes.size)
        assertEquals(0x12.toByte(), addrBytes[0])
        assertEquals(0x56.toByte(), addrBytes[1])
        assertEquals(0x34.toByte(), addrBytes[2])

        val reconstructed = HondaConstants.addressFromBytes(
            addrBytes[0].toInt() and 0xFF,
            addrBytes[1].toInt() and 0xFF,
            addrBytes[2].toInt() and 0xFF
        )
        assertEquals(0x123456, reconstructed)
    }

    @Test
    fun testDtcParsing() {
        val dtcBytes = byteArrayOf(0x03.toByte(), 0x01.toByte(), 0x04.toByte(), 0x20.toByte())
        val dtcs = ObdConstants.decodeDTCs(dtcBytes)
        assertEquals(2, dtcs.size)
        assertEquals("P0301", dtcs[0])
        assertEquals("P0420", dtcs[1])

        val encoded = ObdConstants.dtcToHexBytes("P0301")
        assertEquals(0x03.toByte(), encoded[0])
        assertEquals(0x01.toByte(), encoded[1])
    }

    @Test
    fun testHexConversion() {
        val hexStr = HondaConstants.toHexStr(byteArrayOf(0xFE.toByte(), 0x72.toByte()))
        assertEquals("FE 72", hexStr)
    }
}
