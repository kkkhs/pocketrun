package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import io.github.kkkhs.pocketrun.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenAiModelTransportTest {
    @Test
    fun resolvesStandardAndVersionedChatCompletionEndpoints() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiModelTransport.resolveChatCompletionsUrl("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiModelTransport.resolveChatCompletionsUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            OpenAiModelTransport.resolveChatCompletionsUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAiModelTransport.resolveChatCompletionsUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiModelTransport.resolveChatCompletionsUrl("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun streamsTextDeltasAndToolCallsAndReplaysToolResults() {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val sseTurn1 = listOf(
            """data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":"让我查一下 "},"finish_reason":null}]}""",
            """data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"工作区。","tool_calls":[{"index":0,"id":"call_shell_1","type":"function","function":{"name":"linux_run_shell","arguments":"{\"comm"}}]},"finish_reason":null}]}""",
            """data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\":\"ls -la\",\"timeout_sec\":8}"}}]},"finish_reason":null}]}""",
            """data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":15,"completion_tokens":22}}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n")

        val sseTurn2 = listOf(
            """data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{"content":"执行完成！"},"finish_reason":null}]}""",
            """data: {"id":"chatcmpl-2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":30,"completion_tokens":9}}""",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n")

        val responses = ArrayDeque(listOf(sseTurn1, sseTurn2))
        ServerSocket(0).use { server ->
            val thread = Thread {
                repeat(2) {
                    val socket = server.accept()
                    socket.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val headerBytes = ByteArrayOutputStream()
                        var prev3 = 0
                        var prev2 = 0
                        var prev1 = 0
                        while (true) {
                            val b = input.read()
                            if (b < 0) break
                            headerBytes.write(b)
                            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                            prev3 = prev2
                            prev2 = prev1
                            prev1 = b
                        }
                        val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
                        val contentLength = headerText
                            .lineSequence()
                            .firstOrNull { it.lowercase().startsWith("content-length:") }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.toInt()
                            ?: 0
                        val rawBody = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(rawBody, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        capturedBodies += String(rawBody, 0, read, Charsets.UTF_8)
                        val body = responses.removeFirst().toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
                        s.getOutputStream().write(body)
                        s.getOutputStream().flush()
                    }
                }
            }.apply { isDaemon = true; start() }

            val credential = ModelCredential(
                ModelSettings("https://api.openai.com/v1", "deepseek/deepseek-chat", ModelProtocol.OPENAI),
                "sk-demo-not-a-real-key-123456",
            )
            val transport = OpenAiModelTransport(
                credential = credential,
                mode = ModelMode.CHAT,
                endpointOverride = "http://127.0.0.1:${server.localPort}/v1/chat/completions",
            )
            try {
                val deltas = CopyOnWriteArrayList<String>()
                val done1 = CountDownLatch(1)
                val res1 = AtomicReference<Result<Message>>()
                val history = mutableListOf(MessageParam.ofUser("帮我看看工作区"))
                transport.requestStreaming(history, onTextDelta = { deltas += it }) {
                    res1.set(it)
                    done1.countDown()
                }
                assertTrue(done1.await(4, TimeUnit.SECONDS))
                val msg1 = res1.get().getOrThrow()
                assertEquals("让我查一下 工作区。", deltas.joinToString(""))
                assertEquals(StopReason.TOOL_USE, msg1.stopReason().get())
                assertEquals(22L, msg1.usage().outputTokens())
                val toolBlock = msg1.content().single { it.isToolUse() }.asToolUse()
                val parsed = ModelToolSurface.parse(toolBlock, ModelMode.CHAT)
                assertEquals("linux.run_shell", parsed.tool)
                assertEquals("ls -la", parsed.arguments["command"])
                assertEquals(8, parsed.arguments["timeout_sec"])

                history += msg1.toParam()
                history += MessageParam.ofUserOfContentBlockParams(
                    listOf(
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(toolBlock.id())
                                .content("exitCode=0\nstdout=items.csv")
                                .build(),
                        ),
                    ),
                )
                val done2 = CountDownLatch(1)
                val res2 = AtomicReference<Result<Message>>()
                transport.requestStreaming(history, onTextDelta = {}) {
                    res2.set(it)
                    done2.countDown()
                }
                assertTrue(done2.await(4, TimeUnit.SECONDS))
                val msg2 = res2.get().getOrThrow()
                assertEquals(StopReason.END_TURN, msg2.stopReason().get())
                assertEquals("执行完成！", msg2.content().single { it.isText() }.asText().text())
                assertTrue(capturedBodies[1].contains("\"role\":\"tool\""))
                assertTrue(capturedBodies[1].contains("\"tool_call_id\":\"call_shell_1\""))
            } finally {
                transport.close()
                thread.join(2_000)
            }
        }
    }

    @Test
    fun shellToolContractEnforcesApprovalAndBoundsAndWorkspaceDeleteRemovesRegularFileOnly() {
        val action = ToolSchema.validate(RawToolCall("linux.run_shell", mapOf("command" to "ls -la")))
        assertEquals(ParsedAction.RunLinuxShell("ls -la", 10), action)
        assertTrue(action.approvalRequired)
        try {
            ToolSchema.validate(RawToolCall("linux.run_shell", mapOf("command" to "ls", "timeout_sec" to 30)))
            fail("Expected timeout bound rejection")
        } catch (e: ToolValidationException) {
            assertEquals("INVALID_TIMEOUT", e.code)
        }

        val dir = Files.createTempDirectory("pocketrun-ws-del-").toRealPath()
        try {
            WorkspaceSession(dir.toFile()).use { ws ->
                ws.writeTextFile("notes/draft.md", "hello")
                assertTrue(ws.listFiles().any { it.relativePath == "notes/draft.md" })
                assertTrue(ws.deleteTextFile("notes/draft.md"))
                assertFalse(ws.listFiles().any { it.relativePath == "notes/draft.md" })
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
