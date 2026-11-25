package io.github.kkkhs.pocketrun.taskhost

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class MemoryCodecTest {
    private fun note(index: Int, text: String = "Summary starts with report A") =
        MemoryNote("note_" + index.toString(16).padStart(32, '0'), text)
    private val revision = "r_" + "a".repeat(32)

    @Test fun snapshotRoundTripPreservesUserTextAndSelectionOrder() {
        val snapshot = MemoryCodec.snapshot(listOf(note(1, "中文\nquote: \"A\""), note(2)), revision)
        val decoded = MemoryCodec.decode(MemoryCodec.encode(snapshot))
        assertEquals(revision, decoded.revision)
        assertEquals(snapshot.notes, decoded.notes)
        val selected = MemoryCodec.select(decoded, listOf(note(2).id, note(1).id))
        assertEquals(listOf(note(2).id, note(1).id), selected.ids)
        assertEquals("中文\nquote: \"A\"", TaskHostJson.parseObject(selected.json)[note(1).id])
        assertTrue(selected.bytes <= MemoryCodec.MAX_SELECTED_BYTES)
    }

    @Test fun noteBudgetCountsUtf8AndRejectsMalformedText() {
        MemoryCodec.validateText("中".repeat(85))
        MemoryCodec.validateText("😀".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.validateText("中".repeat(86)) }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.validateText("😀".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.validateText("  \n") }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.validateText("a" + 0.toChar() + "b") }
        assertThrows(CharacterCodingException::class.java) { MemoryCodec.validateText("\ud800") }
    }

    @Test fun encodedSnapshotAndSelectionLimitsIncludeEscaping() {
        val escaped = (1..8).map { note(it, "\\".repeat(256)) }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.snapshot(escaped, revision) }
        val smaller = MemoryCodec.snapshot(escaped.take(4), revision)
        assertThrows(IllegalArgumentException::class.java) {
            MemoryCodec.select(smaller, smaller.notes.map { it.id })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryCodec.snapshot((1..9).map { note(it) }, revision)
        }
    }

    @Test fun badSchemaDuplicateFieldsAndInvalidEncodingNeverBecomeEmptyMemory() {
        val bad = listOf("{}", "{\"schema\":true,\"revision\":\"$revision\"}",
            "{\"schema\":1,\"schema\":1,\"revision\":\"$revision\"}",
            "{\"schema\":1,\"revision\":\"$revision\",\"approve\":true}",
            "{\"schema\":1,\"revision\":\"old\"}",
            "{\"schema\":1,\"revision\":\"$revision\",\"${note(1).id}\":null}")
        bad.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { MemoryCodec.decode(source.toByteArray()) }
        }
        assertThrows(CharacterCodingException::class.java) { MemoryCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.decode(ByteArray(4097)) }
    }

    @Test fun selectedIdsMustBeUniquePresentAndBounded() {
        val snapshot = MemoryCodec.snapshot((1..8).map { note(it) }, revision)
        assertThrows(IllegalArgumentException::class.java) {
            MemoryCodec.select(snapshot, listOf(note(1).id, note(1).id))
        }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.select(snapshot, listOf(note(9).id)) }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryCodec.select(snapshot, snapshot.notes.take(5).map { it.id })
        }
        assertThrows(IllegalArgumentException::class.java) { MemoryCodec.select(snapshot, listOf("approve")) }
        assertTrue(MemoryCodec.select(snapshot, emptyList()).notes.isEmpty())
    }

    @Test fun snapshotsCannotBeMutatedThroughCallerListsOrReturnedCollections() {
        val notes = mutableListOf(note(1))
        val snapshot = MemoryCodec.snapshot(notes, revision)
        notes.clear()
        assertEquals(1, snapshot.notes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.notes as MutableList<MemoryNote>).clear()
        }
        val selection = MemoryCodec.select(snapshot, listOf(note(1).id))
        assertThrows(UnsupportedOperationException::class.java) {
            (selection.ids as MutableList<String>).clear()
        }
    }
}
