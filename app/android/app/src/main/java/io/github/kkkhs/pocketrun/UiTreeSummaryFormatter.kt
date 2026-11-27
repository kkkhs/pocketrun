package io.github.kkkhs.pocketrun

/**
 * Compresses a raw virtual-display Accessibility UiTree into a compact, coordinate-annotated
 * summary suitable for LLM tool results while staying well within PocketRun's context budget.
 */
internal object UiTreeSummaryFormatter {
    const val MAX_SUMMARY_BYTES = 7_500
    const val MAX_ELEMENTS = 80

    data class Summary(
        val topPackage: String,
        val elementCount: Int,
        val truncated: Boolean,
        val text: String,
    )

    fun format(tree: UiTreeCodec.Tree): Summary {
        if (tree.status != UiTreeCodec.Status.Available || tree.nodes.isEmpty()) {
            return Summary(
                topPackage = "",
                elementCount = 0,
                truncated = tree.truncated,
                text = "(screen status=${tree.status.wire}, no visible UI elements)",
            )
        }
        val packageCounts = linkedMapOf<String, Int>()
        val lines = mutableListOf<String>()
        var bytes = 0
        var truncated = tree.truncated

        for (node in tree.nodes) {
            if (node.packageName.isNotBlank()) {
                packageCounts[node.packageName] = (packageCounts[node.packageName] ?: 0) + 1
            }
            val width = node.right - node.left
            val height = node.bottom - node.top
            if (width <= 0 || height <= 0) continue

            val cx = (node.left + node.right) / 2
            val cy = (node.top + node.bottom) / 2
            if (cx !in 0 until DisplayInputPolicy.WIDTH || cy !in 0 until DisplayInputPolicy.HEIGHT) continue

            val label = buildLabel(node)
            val shortId = node.viewId.substringAfterLast('/', "").take(32)
            val meaningful = label.isNotEmpty() || node.editable || node.focused || (node.clickable && shortId.isNotEmpty())
            if (!meaningful) continue

            if (lines.size >= MAX_ELEMENTS) {
                truncated = true
                break
            }
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.focused) add("focused")
                if (!node.enabled) add("disabled")
            }
            val line = buildString {
                append('[')
                append(lines.size + 1)
                append("] ")
                if (label.isNotEmpty()) {
                    append('"')
                    append(label)
                    append('"')
                } else {
                    append(node.className.substringAfterLast('.').ifEmpty { "View" })
                }
                if (shortId.isNotEmpty()) {
                    append(" id=")
                    append(shortId)
                }
                append(" center=(")
                append(cx)
                append(',')
                append(cy)
                append(')')
                if (flags.isNotEmpty()) {
                    append(" [")
                    append(flags.joinToString(","))
                    append(']')
                }
            }
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + lineBytes > MAX_SUMMARY_BYTES) {
                truncated = true
                break
            }
            lines += line
            bytes += lineBytes
        }

        val topPackage = packageCounts.maxByOrNull { it.value }?.key.orEmpty()
        val body = if (lines.isEmpty()) "(no interactive or text nodes)" else lines.joinToString("\n")
        return Summary(
            topPackage = topPackage,
            elementCount = lines.size,
            truncated = truncated,
            text = body,
        )
    }

    private fun buildLabel(node: UiTreeCodec.Node): String {
        val t = sanitize(node.text)
        val d = sanitize(node.contentDescription)
        return when {
            t.isNotEmpty() && d.isNotEmpty() && t != d -> "$t ($d)".take(80)
            t.isNotEmpty() -> t.take(80)
            d.isNotEmpty() -> d.take(80)
            else -> ""
        }
    }

    private fun sanitize(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace('"', '\'').trim()
}
