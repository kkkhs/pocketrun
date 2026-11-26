package io.github.kkkhs.pocketrun.taskhost

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class DemoCsvReportTest {
    @Test fun originalSampleHasTwoRecordsAndFiveUnits() {
        val bytes = "name,quantity\napple,2\nbanana,3\n".toByteArray()
        val report = DemoCsvReport.parse(bytes)
        assertEquals(2, report.items)
        assertEquals(5, report.quantity)
        assertEquals("items=2;quantity=5", report.text)
        assertEquals("items=2;quantity=5\n", report.fileText)
        assertEquals(DemoCsvReport.digest(bytes), report.inputDigest)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DemoCsvReport.digest(byteArrayOf()))
    }

    @Test fun emptyInventoryAndOptionalFinalLfKeepMeaningButRetainDistinctInputDigests() {
        for (source in listOf("name,quantity", "name,quantity\n")) {
            assertEquals("items=0;quantity=0", DemoCsvReport.parse(source.toByteArray()).text)
        }
        val plain = DemoCsvReport.parse("name,quantity\napple,0".toByteArray())
        val newline = DemoCsvReport.parse("name,quantity\napple,0\n".toByteArray())
        assertEquals(plain.text, newline.text)
        assertNotEquals(plain.inputDigest, newline.inputDigest)
    }

    @Test fun byteAndRecordLimitsAreExact() {
        val atLimit = "name,quantity\n" + ("a".repeat(32) + ",1000000\n").repeat(24) +
            "a".repeat(17) + ",1000000\n"
        assertEquals(1024, atLimit.toByteArray().size)
        assertEquals(25_000_000, DemoCsvReport.parse(atLimit.toByteArray()).quantity)
        assertThrows(IllegalArgumentException::class.java) { DemoCsvReport.parse((atLimit + "\n").toByteArray()) }
        val maximumRecords = "name,quantity\n" + "apple,1000000\n".repeat(32)
        assertEquals("items=32;quantity=32000000", DemoCsvReport.parse(maximumRecords.toByteArray()).text)
        assertThrows(IllegalArgumentException::class.java) {
            DemoCsvReport.parse((maximumRecords + "apple,1\n").toByteArray())
        }
    }

    @Test fun unsupportedCsvAndNoncanonicalQuantitiesAreRejected() {
        val rows = listOf("", "apple", "apple,1,2", "\"apple\",1", "apple,", " apple,1", "apple name,1",
            "=formula,1", "apple,-1", "apple,+1", "apple,01", "apple,1.0", "apple,1e3", "apple,1000001",
            "apple,2147483647", "apple,2147483648", "apple,999999999999999999999999")
        for (row in rows) {
            assertThrows(IllegalArgumentException::class.java) {
                DemoCsvReport.parse(("name,quantity\n" + row + "\n").toByteArray())
            }
        }
        for (source in listOf("", "name,qty\napple,1", "name,quantity\r\napple,1", "name,quantity\napple,1\n\n",
            "name,quantity\n" + "a".repeat(33) + ",1", "name,quantity\n苹果,1", "﻿name,quantity\napple,1")) {
            assertThrows(IllegalArgumentException::class.java) { DemoCsvReport.parse(source.toByteArray()) }
        }
    }

    @Test fun malformedUtf8AndImpossibleReportValuesFailClosed() {
        assertThrows(CharacterCodingException::class.java) { DemoCsvReport.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(CharacterCodingException::class.java) {
            DemoCsvReport.parse(byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
        }
        assertThrows(IllegalArgumentException::class.java) { DemoCsvReport("bad", 2, 5) }
        assertThrows(IllegalArgumentException::class.java) { DemoCsvReport("a".repeat(64), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { DemoCsvReport("a".repeat(64), 33, 0) }
        assertThrows(IllegalArgumentException::class.java) { DemoCsvReport("a".repeat(64), 32, Int.MAX_VALUE) }
    }
}
