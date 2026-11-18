package io.github.kkkhs.pocketrun

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object UiTreeCodec {
    private const val MAGIC = 0x50525549
    private const val VERSION = 1
    private const val HEADER_BYTES = 21
    private const val KNOWN_FLAGS = 15
    const val MAX_NODES = 512
    const val MAX_DEPTH = 32
    const val MAX_STRING_BYTES = 256
    const val MAX_TREE_BYTES = 256 * 1024

    enum class Status(val code: Int, val wire: String) {
        Available(0, "available"),
        NoWindow(1, "no_window"),
        RootUnavailable(2, "root_unavailable"),
        Timeout(3, "timeout"),
        Busy(4, "busy"),
        QueryFailed(5, "query_failed");

        companion object {
            fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("UI tree 状态无效。")
        }
    }

    data class Node(
        val parentIndex: Int,
        val windowId: Int,
        val depth: Int,
        val className: String,
        val packageName: String,
        val viewId: String,
        val text: String,
        val contentDescription: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
    )

    data class Tree(
        val displayId: Int,
        val status: Status,
        val truncated: Boolean,
        val nodes: List<Node>,
    )

    fun encode(tree: Tree): ByteArray {
        require(tree.displayId > 0) { "UI tree displayId 无效。" }
        require(tree.status == Status.Available || tree.nodes.isEmpty()) { "不可用的 UI tree 不能包含节点。" }
        val encodedNodes = ArrayList<ByteArray>()
        val selectedIndices = HashMap<Int, Int>()
        var size = HEADER_BYTES
        var truncated = tree.truncated || tree.nodes.size > MAX_NODES
        for ((index, node) in tree.nodes.take(MAX_NODES).withIndex()) {
            validateNode(node, index, tree.nodes)
            if (node.depth > MAX_DEPTH || node.parentIndex >= 0 && node.parentIndex !in selectedIndices) {
                truncated = true
                continue
            }
            val selected = node.copy(parentIndex = selectedIndices[node.parentIndex] ?: -1)
            val bytes = ByteArrayOutputStream().use { raw ->
                DataOutputStream(raw).use { out -> writeNode(out, selected) }
                raw.toByteArray()
            }
            if (size + bytes.size > MAX_TREE_BYTES) {
                truncated = true
                break
            }
            if (strings(node).any { it.toByteArray(Charsets.UTF_8).size > MAX_STRING_BYTES }) truncated = true
            selectedIndices[index] = encodedNodes.size
            encodedNodes += bytes
            size += bytes.size
        }
        return ByteArrayOutputStream(size).use { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(tree.displayId)
                out.writeInt(tree.status.code)
                out.writeBoolean(truncated)
                out.writeInt(encodedNodes.size)
                encodedNodes.forEach(out::write)
            }
            raw.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): Tree {
        require(bytes.size in HEADER_BYTES..MAX_TREE_BYTES) { "UI tree 数据长度无效。" }
        try {
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION) { "UI tree 数据格式无效。" }
                val displayId = input.readInt()
                val status = Status.fromCode(input.readInt())
                val truncatedByte = input.readUnsignedByte()
                val count = input.readInt()
                require(displayId > 0 && truncatedByte in 0..1 && count in 0..MAX_NODES) { "UI tree 元数据无效。" }
                require(status == Status.Available || count == 0) { "不可用的 UI tree 不能包含节点。" }
                val nodes = ArrayList<Node>(count)
                repeat(count) { index ->
                    val node = readNode(input)
                    require(node.depth <= MAX_DEPTH) { "UI node depth 无效。" }
                    validateNode(node, index, nodes)
                    nodes += node
                }
                require(input.available() == 0) { "UI tree 包含尾随数据。" }
                Tree(displayId, status, truncatedByte == 1, nodes)
            }
        } catch (error: EOFException) {
            throw IllegalArgumentException("UI tree 数据不完整。", error)
        }
    }

    fun decode(bytes: ByteArray, expectedDisplayId: Int): Tree {
        require(expectedDisplayId > 0) { "UI tree expectedDisplayId 无效。" }
        return decode(bytes).also { require(it.displayId == expectedDisplayId) { "UI tree displayId 不匹配。" } }
    }

    private fun validateNode(node: Node, index: Int, nodes: List<Node>) {
        require(node.windowId >= 0 && node.depth >= 0) { "UI node 身份无效。" }
        require(node.left <= node.right && node.top <= node.bottom) { "UI node bounds 无效。" }
        if (node.parentIndex == -1) {
            require(node.depth == 0) { "UI tree 根节点 depth 必须为 0。" }
        } else {
            require(node.parentIndex in 0 until index) { "UI node parentIndex 无效。" }
            val parent = nodes[node.parentIndex]
            require(parent.windowId == node.windowId && node.depth == parent.depth + 1) { "UI tree 父子关系无效。" }
        }
    }

    private fun strings(node: Node) = listOf(node.className, node.packageName, node.viewId, node.text, node.contentDescription)

    private fun writeNode(out: DataOutputStream, node: Node) {
        out.writeInt(node.parentIndex)
        out.writeInt(node.windowId)
        out.writeInt(node.depth)
        strings(node).forEach { writeString(out, it) }
        out.writeInt(node.left)
        out.writeInt(node.top)
        out.writeInt(node.right)
        out.writeInt(node.bottom)
        var flags = 0
        if (node.clickable) flags = flags or 1
        if (node.editable) flags = flags or 2
        if (node.enabled) flags = flags or 4
        if (node.focused) flags = flags or 8
        out.writeByte(flags)
    }

    private fun readNode(input: DataInputStream): Node {
        val parent = input.readInt()
        val window = input.readInt()
        val depth = input.readInt()
        val className = readString(input)
        val packageName = readString(input)
        val viewId = readString(input)
        val text = readString(input)
        val description = readString(input)
        val left = input.readInt()
        val top = input.readInt()
        val right = input.readInt()
        val bottom = input.readInt()
        val flags = input.readUnsignedByte()
        require(flags and KNOWN_FLAGS.inv() == 0) { "UI node flags 无效。" }
        return Node(parent, window, depth, className, packageName, viewId, text, description,
            left, top, right, bottom, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        var length = minOf(encoded.size, MAX_STRING_BYTES)
        // If the first excluded byte is a continuation, discard its partial character.
        if (encoded.size > length) while (length > 0 && (encoded[length].toInt() and 0xc0) == 0x80) length--
        out.writeInt(length)
        out.write(encoded, 0, length)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES && size <= input.available()) { "UI node 字符串长度无效。" }
        val bytes = ByteArray(size).also(input::readFully)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("UI node UTF-8 无效。", error)
        }
    }
}
