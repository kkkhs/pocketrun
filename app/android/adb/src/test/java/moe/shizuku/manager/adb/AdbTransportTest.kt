package moe.shizuku.manager.adb

import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AdbTransportTest {
    @Test fun silentPeerHasAReadDeadline() {
        ServerSocket(0).use { server ->
            AdbTransport(250).use { transport ->
                val socket = transport.connect("127.0.0.1", server.localPort)
                server.accept().use {
                    transport.beforeRead()
                    try {
                        socket.getInputStream().read()
                        fail("Silent peer must time out")
                    } catch (_: SocketTimeoutException) {}
                }
            }
        }
    }

    @Test fun cancelUnblocksReadAndRepeatedCloseIsSafe() {
        ServerSocket(0).use { server ->
            AdbTransport().use { transport ->
                val socket = transport.connect("127.0.0.1", server.localPort)
                server.accept().use {
                    val started = CountDownLatch(1)
                    val error = AtomicReference<Throwable?>()
                    val reader = Thread {
                        started.countDown()
                        try { socket.getInputStream().read() } catch (failure: IOException) { error.set(failure) }
                    }
                    reader.isDaemon = true
                    reader.start()
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                    transport.cancel()
                    transport.cancel()
                    transport.close()
                    reader.join(1000)
                    assertFalse("Cancelled read must finish", reader.isAlive)
                    assertNotNull(error.get())
                    assertTrue(transport.cancelled)
                }
            }
        }
    }

    @Test fun aNewProbeCanStartAfterAnIdleDeadline() {
        ServerSocket(0).use { server ->
            AdbTransport(200).use { transport ->
                val socket = transport.connect("127.0.0.1", server.localPort)
                server.accept().use { peer ->
                    Thread.sleep(250)
                    transport.beginDeadline()
                    peer.getOutputStream().write(42)
                    transport.beforeRead()
                    assertEquals(42, socket.getInputStream().read())
                }
            }
        }
    }

    @Test fun protocolStringsKeepTheirTerminatorAndLogsExcludePayloads() {
        val message = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "secret")
        assertEquals(0, message.data!!.last().toInt())
        assertTrue(message.validate())
        assertFalse(message.toStringShort().contains("secret"))
        val bad = AdbMessage(message.command, 1, 0, 7, 0, 0, message.data)
        assertFalse(bad.validate())
        val retired = AdbMessage(AdbProtocol.A_CLSE, 7, 1, null)
        assertTrue(retired.isRetiredClose(2))
        assertFalse(retired.isRetiredClose(1))
        assertFalse(message.isRetiredClose(2))
    }
}
