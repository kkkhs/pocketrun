package io.github.kkkhs.pocketrun.taskhost

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.UUID

internal data class MemoryNote(val id: String, val text: String)

internal class MemorySnapshot(val revision: String, notes: List<MemoryNote>) {
    val notes: List<MemoryNote> = Collections.unmodifiableList(ArrayList(notes))
}

internal class MemorySelection(val revision: String, notes: List<MemoryNote>, val json: String) {
    val notes: List<MemoryNote> = Collections.unmodifiableList(ArrayList(notes))
    val ids: List<String> = Collections.unmodifiableList(this.notes.map { it.id })
    val bytes = json.toByteArray(Charsets.UTF_8).size
}

/** User-authored data only. Neither a note ID nor a revision conveys tool authorization. */
internal object MemoryCodec {
    const val MAX_NOTES = 8
    const val MAX_NOTE_BYTES = 256
    const val MAX_SNAPSHOT_BYTES = 4096
    const val MAX_SELECTED = 4
    const val MAX_SELECTED_BYTES = 2048
    private val NOTE_ID = Regex("note_[a-f0-9]{32}")
    private val REVISION = Regex("r_[a-f0-9]{32}")

    fun newNoteId() = "note_" + UUID.randomUUID().toString().replace("-", "")
    fun newRevision() = "r_" + UUID.randomUUID().toString().replace("-", "")

    fun validateText(text: String) {
        require(text.length in 1..MAX_NOTE_BYTES && text.isNotBlank() && encoded(text).size <= MAX_NOTE_BYTES) { "INVALID_MEMORY_TEXT" }
        require(text.none { it.code < 0x20 && it != '\n' && it != '\t' }) { "INVALID_MEMORY_TEXT" }
    }

    fun validateId(value: String) { require(value.length == 37 && NOTE_ID.matches(value)) { "INVALID_MEMORY_ID" } }
    fun validateRevision(value: String) { require(value.length == 34 && REVISION.matches(value)) { "INVALID_MEMORY_REVISION" } }

    fun snapshot(notes: List<MemoryNote>, revision: String = newRevision()): MemorySnapshot =
        MemorySnapshot(revision, notes).also { encode(it) }

    fun encode(snapshot: MemorySnapshot): ByteArray {
        validateRevision(snapshot.revision)
        require(snapshot.notes.size <= MAX_NOTES && snapshot.notes.map { it.id }.distinct().size == snapshot.notes.size) {
            "MEMORY_NOTE_BUDGET"
        }
        val fields = linkedMapOf<String, Any>("schema" to 1, "revision" to snapshot.revision)
        snapshot.notes.forEach { note ->
            validateId(note.id)
            validateText(note.text)
            fields[note.id] = note.text
        }
        return encoded(SdkJsonBudget.json(fields)).also {
            require(it.size <= MAX_SNAPSHOT_BYTES) { "MEMORY_SNAPSHOT_BUDGET" }
        }
    }

    fun decode(bytes: ByteArray): MemorySnapshot {
        require(bytes.size in 1..MAX_SNAPSHOT_BYTES) { "MEMORY_SNAPSHOT_BUDGET" }
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val fields = TaskHostJson.parseObject(source)
        require(fields["schema"] == 1 && fields["schema"] is Int) { "INVALID_MEMORY_SCHEMA" }
        val revision = fields["revision"]
        require(revision is String) { "INVALID_MEMORY_REVISION" }
        val notes = fields.filterKeys { it != "schema" && it != "revision" }.map { (id, text) ->
            require(text is String) { "INVALID_MEMORY_TEXT" }
            MemoryNote(id, text)
        }
        return snapshot(notes, revision)
    }

    fun select(snapshot: MemorySnapshot, ids: List<String>): MemorySelection {
        encode(snapshot)
        require(ids.size <= MAX_SELECTED && ids.distinct().size == ids.size) { "MEMORY_SELECTION_BUDGET" }
        val selected = ids.map { id ->
            validateId(id)
            snapshot.notes.singleOrNull { it.id == id } ?: throw IllegalArgumentException("MEMORY_NOTE_MISSING")
        }
        val fields = linkedMapOf<String, Any>("revision" to snapshot.revision)
        selected.forEach { fields[it.id] = it.text }
        val json = SdkJsonBudget.json(fields)
        require(encoded(json).size <= MAX_SELECTED_BYTES) { "MEMORY_SELECTION_BUDGET" }
        return MemorySelection(snapshot.revision, selected, json)
    }

    private fun encoded(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }
}
