package io.github.kkkhs.pocketrun

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbTransport
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on the task-owned Android 11+ emulator; no pairing code is logged or persisted. */
@RunWith(AndroidJUnit4::class)
class AdbPairingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun pairConnectStartBackendAndDisconnect() {
        assertEquals(
            "This test may only change the tutorial's owned emulator",
            "pocketrun-ch1-api36",
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim(),
        )
        assertTrue("Developer options must be enabled on the owned emulator", isDeveloperOptionsEnabled())
        assertUnauthorizedExchangeIsRejected()
        openWirelessDebugging()
        val connectionPort = addressPort()
        device.findObject(By.text("Pair device with pairing code")).click()
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("[0-9]{6}"))), 5000))
        val code = requiredText(Pattern.compile("[0-9]{6}"))
        val pairingPort = port(requiredText(Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:[0-9]+")))

        // Keep the test identity away from the product identity and reuse it across runs.
        val key = AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences("pocketrun_adb_test_identity", 0)),
            "pocketrun-test@android",
        )
        AdbTransport().use { transport ->
            AdbPairingClient("127.0.0.1", pairingPort, code, key, transport).use {
                assertTrue("The system pairing code was rejected", it.start())
            }
        }

        val backend = PrivilegedBackendController(context)
        val first = AdbTransport().use { transport ->
            AdbClient("127.0.0.1", connectionPort, key, transport).use { client ->
                client.connect(requireTls = true)
                assertEquals("2000", shellUid(client))
                // A second stream exercises local-id rollover on the retained connection.
                assertEquals("2000", shellUid(client))
                backend.start { command -> shellOutput(client, command) }
            }
        }
        assertEquals(2000, first.uid)
        assertTrue(first.pid > 0)
        assertEquals("pong", first.reply)
        // The ADB client is closed above; these calls must travel only through Binder.
        assertEquals(first.pid, backend.ping().pid)
        val display = backend.createDemoDisplay()
        assertTrue(display.active)
        assertTrue(display.displayId > 0)
        assertEquals(display.displayId, backend.displayStatus().displayId)
        assertTrue("Virtual display was not registered", waitUntil { systemHasDisplay(display.displayId) })
        assertTrue("Demo Activity was not routed to the virtual display", waitUntil {
            demoActivityUsesDisplay(display.displayId)
        })

        backend.releaseDisplay()
        assertEquals(-1, backend.displayStatus().displayId)
        assertTrue("Released display is still registered", waitUntil { !systemHasDisplay(display.displayId) })
        assertTrue("Demo Activity survived display release", waitUntil { !demoActivityIsRunning() })

        val secondDisplay = backend.createDemoDisplay()
        assertTrue(secondDisplay.displayId > 0)
        assertTrue(waitUntil { demoActivityUsesDisplay(secondDisplay.displayId) })
        device.executeShellCommand("kill ${first.pid}")
        assertTrue("Binder death was not observed", waitUntil { !backend.status().running })
        assertTrue("Display survived backend death", waitUntil { !systemHasDisplay(secondDisplay.displayId) })
        assertTrue("Demo Activity survived backend death", waitUntil { !demoActivityIsRunning() })

        AdbTransport().use { transport ->
            AdbClient("127.0.0.1", connectionPort, key, transport).use { client ->
                client.connect(requireTls = true)
                val restarted = backend.start { command -> shellOutput(client, command) }
                assertEquals(2000, restarted.uid)
                assertTrue(restarted.pid != first.pid)
            }
        }
        backend.stop()
        assertTrue(!backend.status().running)
        backend.close()
    }

    @Test fun pendingBackendStartCanBeCancelled() {
        assertEquals(
            "This test may only change the tutorial's owned emulator",
            "pocketrun-ch1-api36",
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim(),
        )
        val backend = PrivilegedBackendController(context)
        val enteredWait = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val starter = Thread {
            try {
                backend.start {
                    enteredWait.countDown()
                    BackendLaunchCommand.STARTED_MARKER
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        starter.start()
        assertTrue(enteredWait.await(2, TimeUnit.SECONDS))
        backend.cancelStart()
        starter.join(2000)
        assertFalse("Cancelled backend start must finish", starter.isAlive)
        assertTrue(failure.get() is CancellationException)
        backend.close()
    }

    private fun systemHasDisplay(displayId: Int): Boolean =
        context.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null

    private fun demoActivityIsRunning(): Boolean =
        device.executeShellCommand("dumpsys activity activities").contains("DisplayDemoActivity")

    private fun demoActivityUsesDisplay(displayId: Int): Boolean {
        val dump = device.executeShellCommand("dumpsys activity activities")
        val activity = dump.indexOf("DisplayDemoActivity")
        if (activity < 0) return false
        val displayHeader = dump.lastIndexOf("Display #", activity)
        if (displayHeader < 0) return false
        val headerEnd = dump.indexOf('\n', displayHeader).let { if (it < 0) dump.length else it }
        return dump.substring(displayHeader, headerEnd).startsWith("Display #$displayId ")
    }

    private fun assertUnauthorizedExchangeIsRejected() {
        val extras = Bundle().apply {
            putString(BinderExchangeProvider.KEY_TOKEN, "A".repeat(43))
            putBinder(BinderExchangeProvider.KEY_BACKEND, Binder())
        }
        try {
            context.contentResolver.call(
                "${context.packageName}.binder_exchange",
                BinderExchangeProvider.METHOD_EXCHANGE,
                null,
                extras,
            )
            fail("The App UID must not exchange a privileged Binder")
        } catch (_: SecurityException) {
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun shellOutput(client: AdbClient, command: String): String {
        val output = ByteArrayOutputStream()
        client.shellCommand(command) { chunk ->
            require(output.size() + chunk.size <= 1024) { "Backend launch output exceeded its limit" }
            output.write(chunk)
        }
        return output.toString("UTF-8")
    }

    private fun openWirelessDebugging() {
        device.pressHome()
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Wireless debugging")
        device.findObject(By.text("Wireless debugging")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Use wireless debugging")), 5000))
        val toggle = device.findObject(By.clazz("android.widget.Switch"))
        if (!toggle.isChecked) {
            toggle.click()
            if (device.wait(Until.hasObject(By.text("ALLOW")), 3000)) device.findObject(By.text("ALLOW")).click()
            // Some system builds return to Developer options after the confirmation.
            if (!device.wait(Until.hasObject(By.text("Pair device with pairing code")), 3000)) {
                UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Wireless debugging")
                device.findObject(By.text("Wireless debugging")).click()
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("Pair device with pairing code")), 5000))
    }

    private fun addressPort(): Int = port(requiredText(Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:[0-9]+")))

    private fun requiredText(pattern: Pattern): String {
        val object2 = device.wait(Until.findObject(By.text(pattern)), 5000)
        assertNotNull("Expected Settings value is missing", object2)
        return object2.text
    }

    private fun port(address: String): Int = address.substringAfterLast(':').toInt()

    private fun isDeveloperOptionsEnabled(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun shellUid(client: AdbClient): String {
        val output = ByteArrayOutputStream()
        client.shellCommand("id -u") { chunk ->
            require(output.size() + chunk.size <= 1024) { "Connection probe output exceeded its limit" }
            output.write(chunk)
        }
        return output.toString("UTF-8").trim()
    }
}
