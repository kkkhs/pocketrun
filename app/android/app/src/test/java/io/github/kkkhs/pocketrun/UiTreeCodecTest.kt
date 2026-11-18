package io.github.kkkhs.pocketrun

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeCodecTest {
    private fun node(index: Int, text: String = "节点$index") = UiTreeCodec.Node(
        parentIndex = if (index == 0) -1 else 0,
        windowId = 9,
        depth = if (index == 0) 0 else 1,
        className = "android.widget.EditText",
        packageName = "io.github.kkkhs.pocketrun",
        viewId = "demo_input",
        text = text,
        contentDescription = "演示输入框",
        left = 10,
        top = 20,
        right = 200,
        bottom = 80,
        clickable = true,
        editable = true,
        enabled = true,
        focused = false,
    )

    private fun tree(nodes: List<UiTreeCodec.Node>) = UiTreeCodec.Tree(7, UiTreeCodec.Status.Available, false, nodes)

    @Test fun treeRoundTripsWithUtf8AndBounds() {
        val original = tree(listOf(node(0), node(1)))
        assertEquals(original, UiTreeCodec.decode(UiTreeCodec.encode(original), 7))
    }

    @Test fun nodeAndStringLimitsAreExplicitlyMarked() {
        val many = List(UiTreeCodec.MAX_NODES + 10) { node(it, "界".repeat(300)) }
        val decoded = UiTreeCodec.decode(UiTreeCodec.encode(tree(many)))
        assertTrue(decoded.truncated)
        assertEquals(UiTreeCodec.MAX_NODES, decoded.nodes.size)
        assertTrue(decoded.nodes.all { it.text.toByteArray().size <= UiTreeCodec.MAX_STRING_BYTES })
    }

    @Test fun depthTruncationPreservesParentsAndLaterWindows() {
        val deep = List(UiTreeCodec.MAX_DEPTH + 2) { index ->
            node(index).copy(parentIndex = index - 1, depth = index)
        }
        val secondRoot = node(0).copy(windowId = 10)
        val secondChild = node(1).copy(windowId = 10, parentIndex = deep.size)
        val decoded = UiTreeCodec.decode(UiTreeCodec.encode(tree(deep + listOf(secondRoot, secondChild))))
        assertTrue(decoded.truncated)
        assertTrue(decoded.nodes.all { it.depth <= UiTreeCodec.MAX_DEPTH })
        assertEquals(UiTreeCodec.MAX_DEPTH + 3, decoded.nodes.size)
        assertEquals(-1, decoded.nodes[decoded.nodes.lastIndex - 1].parentIndex)
        assertEquals(decoded.nodes.lastIndex - 1, decoded.nodes.last().parentIndex)
    }

    @Test fun byteBudgetIsEnforcedWithoutDanglingReferences() {
        val long = "界".repeat(300)
        val nodes = List(UiTreeCodec.MAX_NODES) { index ->
            node(index, long).copy(className = long, packageName = long, viewId = long, contentDescription = long)
        }
        val encoded = UiTreeCodec.encode(tree(nodes))
        val decoded = UiTreeCodec.decode(encoded)
        assertTrue(encoded.size <= UiTreeCodec.MAX_TREE_BYTES)
        assertTrue(decoded.truncated && decoded.nodes.size < nodes.size)
        assertTrue(decoded.nodes.drop(1).all { it.parentIndex == 0 })
    }

    @Test fun replacementCharactersAndSplitEmojiStayValidUtf8() {
        val literal = "�".repeat(300)
        val emoji = "ab" + "🙂".repeat(100)
        val decoded = UiTreeCodec.decode(UiTreeCodec.encode(tree(listOf(node(0, literal), node(1, emoji)))))
        assertTrue(decoded.truncated)
        assertEquals("�".repeat(85), decoded.nodes[0].text)
        assertEquals("ab" + "🙂".repeat(63), decoded.nodes[1].text)
        assertTrue(decoded.nodes.all { it.text.toByteArray(Charsets.UTF_8).size <= UiTreeCodec.MAX_STRING_BYTES })
    }

    @Test fun invalidParentWindowAndBoundsAreRejected() {
        val invalidTrees = listOf(
            tree(listOf(node(0).copy(parentIndex = 0))),
            tree(listOf(node(0).copy(depth = 1))),
            tree(listOf(node(0), node(1).copy(windowId = 10))),
            tree(listOf(node(0), node(1).copy(depth = 2))),
            tree(listOf(node(0).copy(windowId = -1))),
            tree(listOf(node(0).copy(left = 201))),
        )
        invalidTrees.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.encode(invalid) }
        }
        val bytes = UiTreeCodec.encode(tree(listOf(node(0))))
        assertThrows(IllegalArgumentException::class.java) {
            UiTreeCodec.decode(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(21, 0) })
        }
    }

    @Test fun corruptHeaderFlagsTruncatedAndTrailingPacketsAreRejected() {
        val bytes = UiTreeCodec.encode(tree(listOf(node(0))))
        val corrupt = listOf(
            bytes.copyOf().apply { this[0] = 0 },
            bytes.copyOf().apply { this[16] = 2 },
            bytes.copyOf().apply { this[lastIndex] = 0x80.toByte() },
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(17, UiTreeCodec.MAX_NODES + 1) },
            bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(29, UiTreeCodec.MAX_DEPTH + 1) },
            bytes.copyOf(bytes.size - 1),
            bytes + byteArrayOf(0),
        )
        corrupt.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.decode(encoded) }
        }
    }

    @Test fun malformedUtf8IsNotSilentlyReplaced() {
        val plain = node(0, "a").copy(className = "", packageName = "", viewId = "", contentDescription = "")
        val encoded = UiTreeCodec.encode(tree(listOf(plain)))
        // Header 21 + three node integers 12 + four string lengths 16.
        encoded[49] = 0xff.toByte()
        assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.decode(encoded) }
    }

    @Test fun unavailableTreesAndExpectedIdentityAreValidated() {
        val empty = UiTreeCodec.Tree(7, UiTreeCodec.Status.NoWindow, false, emptyList())
        assertEquals(empty, UiTreeCodec.decode(UiTreeCodec.encode(empty), 7))
        assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.encode(empty.copy(nodes = listOf(node(0)))) }
        val encoded = UiTreeCodec.encode(tree(listOf(node(0))))
        assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.decode(encoded, 0) }
        assertThrows(IllegalArgumentException::class.java) { UiTreeCodec.decode(encoded, 8) }
    }
}
