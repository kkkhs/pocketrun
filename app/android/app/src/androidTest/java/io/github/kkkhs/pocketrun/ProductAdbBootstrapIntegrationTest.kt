package io.github.kkkhs.pocketrun

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Selected Pixel only. Fresh Settings credentials never leave device memory; no screenshots/dumps,
 * shell bootstrap, test AdbClient/key, native-module invocation, or teardown of other paired owners.
 * Every business mutation is an actual production UI button -> JS onPress -> AdbModule.
 * This creates a display but deliberately NEVER requests its UI tree while the observer is connected.
 */
@RunWith(AndroidJUnit4::class)
class ProductAdbBootstrapIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private lateinit var device: UiDevice
    private var stage = "authorization"
    private var apkSha = "unverified"
    private var pairingCode: CharArray? = null
    private var pairingPort = 0
    private var connectionPort = 0
    private var wroteInputs = false

    @Test fun pairConnectStartBackendAndCreateDisplayViaProductionUi() {
        authorizeBeforeObserver()
        apkSha = sha256(File(context.applicationInfo.sourceDir))
        arguments.getString("sourceApkSha256")?.let { expected ->
            assertTrue("sourceApkSha256 must be a SHA256 digest", expected.matches(Regex("[0-9a-f]{64}")))
            assertEquals("Installed source APK must match the selected artifact", expected, apkSha)
        }
        val observer = OwnedInstrumentationObserver(instrumentation, Configurator.getInstance().uiAutomationFlags)
        var failure: AssertionError? = null
        try {
            stage = "instrumentation-observer"
            observer.acquire() // The holder exists even if getUiAutomation returns null or throws.
            device = UiDevice.getInstance(instrumentation)
            launchApp()
            assertTrue("Do not reuse/stop a pre-existing product backend", !appControl("停止 shell 后端").isEnabled)
            assertTrue("Do not disconnect an existing product ADB owner", appControl("配对").isEnabled)

            stage = "settings-fresh-pairing"
            openAlreadyEnabledWirelessDebugging()
            connectionPort = port(requiredSettingsText(ADDRESS))
            val pair = device.wait(Until.findObject(By.pkg(SETTINGS_PACKAGE).text(PAIR_ENTRY)), 5000)
            checkNotNull(pair) { "The authorized Settings pairing entry is missing." }.click()
            pairingCode = requiredSettingsText(PAIR_CODE).toCharArray()
            pairingPort = port(requiredSettingsText(ADDRESS))

            // NEW_TASK only: do not press Back/dismiss the still-live Settings pairing dialog.
            stage = "production-pair"
            launchApp()
            wroteInputs = true
            setInputWithoutLogging("配对端口", pairingPort.toString())
            setInputWithoutLogging("六位配对码", String(checkNotNull(pairingCode)))
            clickApp("配对")
            waitAppText(Pattern.compile("配对完成。现在使用无线调试主页的连接端口。"))
            success("production-paired")

            stage = "production-connect"
            setInputWithoutLogging("连接端口", connectionPort.toString())
            clickApp("连接")
            waitAppText(Pattern.compile("连接已验证，连接端 UID：2000"))
            success("production-connected", uid = 2000)

            stage = "production-backend-start"
            clickApp("启动 shell 后端")
            val backendText = waitAppText(BACKEND_PONG)
            val backendPid = checkNotNull(BACKEND_PONG.matcher(backendText).let {
                if (it.matches()) it.group(1)?.toIntOrNull() else null
            }) { "The actual production UI did not report a positive backend PID." }
            assertTrue("Backend PID must be positive", backendPid > 0)
            stage = "production-binder-ping"
            clickApp("通过 Binder 检查 shell 后端")
            val ping = BACKEND_PONG.matcher(waitAppText(BACKEND_PONG))
            assertTrue("Actual Binder button must report pong", ping.matches())
            assertEquals("Binder ping must preserve the production backend PID", backendPid, checkNotNull(ping.group(1)).toInt())
            success("production-binder-pong", uid = 2000, pid = backendPid)

            stage = "production-display-create"
            clickApp("创建演示虚拟屏")
            val displayText = DISPLAY_CREATED.matcher(waitAppText(DISPLAY_CREATED))
            assertTrue("Production UI must acknowledge its actual created display", displayText.matches())
            val displayId = checkNotNull(displayText.group(1)).toInt()
            assertTrue("Display ID must be non-default", displayId > 0)
            assertTrue("DisplayManager must independently register that ID",
                context.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
            success("production-display-created-no-tree-query", uid = 2000, pid = backendPid, displayId = displayId)
        } catch (error: Throwable) {
            // Never forward a framework/node/RN exception message or cause containing UI credentials.
            failure = AssertionError("Product bootstrap blocked at $stage (${error.javaClass.simpleName}); credentials withheld.")
        } finally {
            var fieldsCleared = true
            if (wroteInputs && ::device.isInitialized) {
                fieldsCleared = runCatching {
                    launchApp()
                    for (label in listOf("六位配对码", "配对端口", "连接端口")) setInputWithoutLogging(label, "")
                }.isSuccess
            }
            pairingCode?.fill('\u0000')
            pairingCode = null
            pairingPort = 0
            connectionPort = 0
            val observerExited = observer.closeAndConfirm()
            if (!fieldsCleared || !observerExited) {
                failure = AssertionError("Bootstrap cleanup blocked: fieldsCleared=$fieldsCleared observerExited=$observerExited.")
            }
            // Retain the actual product pairing/backend/display for the main-owned continuation.
            // No cancel/disconnect product button, unpair, releaseDisplay, stopBackend, or force-stop.
        }
        failure?.let { throw it }
    }

    private fun authorizeBeforeObserver() {
        assertEquals("Only this explicitly selected hardware device is authorized", AUTHORIZED_SERIAL,
            arguments.getString("authorizedSerial"))
        assertEquals("Explicit authorizedApi=35 is required", "35", arguments.getString("authorizedApi"))
        assertEquals("This fixture is only for the authorized Android 15 device", 35, Build.VERSION.SDK_INT)
        assertEquals("Device identity must be verified before observer acquisition", AUTHORIZED_SERIAL, readSerial())
        assertEquals("Developer options must already be enabled by the user", 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
    }

    private fun readSerial(): String {
        val process = ProcessBuilder("/system/bin/getprop", "ro.serialno").redirectErrorStream(true).start()
        try {
            assertTrue("Fixed read-only owner probe timed out", process.waitFor(3, TimeUnit.SECONDS))
            assertEquals("Fixed read-only owner probe failed", 0, process.exitValue())
            val output = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1024) { "Owner probe output exceeded its bound." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8").trim()
        } finally {
            if (process.isAlive) process.destroyForcibly() // Only the fixed child created above, never any backend PID.
            process.waitFor(2, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun launchApp() {
        context.startActivity(Intent().setClassName(context.packageName, "${context.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("The real production MainActivity must be visible", device.wait(Until.hasObject(
            By.displayId(0).pkg(context.packageName)), 8000))
    }

    /** Reuses the existing pairing fixture's Settings route/regex; never changes toggle/Always/network policy. */
    private fun openAlreadyEnabledWirelessDebugging() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Authorized Settings must open", device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), 5000))
        UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(12)
            .scrollIntoView(UiSelector().textMatches("无线调试|Wireless debugging"))
        checkNotNull(device.wait(Until.findObject(By.pkg(SETTINGS_PACKAGE).text(WIRELESS_ENTRY)), 5000))
            .click()
        assertTrue("User-approved wireless debugging must already expose pairing; do not toggle/approve it here",
            device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE).text(PAIR_ENTRY)), 5000))
    }

    private fun requiredSettingsText(pattern: Pattern): String {
        val value = checkNotNull(device.wait(Until.findObject(By.pkg(SETTINGS_PACKAGE).text(pattern)), 5000)) {
            "The current Settings value is unavailable."
        }
        try { return checkNotNull(value.text) { "The current Settings value is unreadable." } }
        finally { value.recycle() }
    }

    private fun port(address: String): Int = address.substringAfterLast(':').toInt().also {
        check(it in 1..65535) { "Current Settings port is invalid." }
    }

    private fun appControl(label: String): UiObject2 {
        val selector = By.displayId(0).pkg(context.packageName).desc(label)
        device.findObject(selector)?.let { return it }
        UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(12)
            .scrollIntoView(UiSelector().description(label))
        return checkNotNull(device.wait(Until.findObject(selector), 5000)) { "Required production control is missing." }
    }

    private fun clickApp(label: String) {
        val control = appControl(label)
        try {
            assertTrue("Production control must be enabled: $label", control.isEnabled)
            control.click() // Real UI touch -> onPress; never call the native module as a test shortcut.
        } finally { control.recycle() }
    }

    private fun setInputWithoutLogging(label: String, value: String) {
        val field = appControl(label)
        val payload = Bundle()
        try {
            payload.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            // UiObject2.setText logs the literal value. Its public node action has the same real
            // EditText/onChange semantics without that log statement. Never use setText/clear.
            assertTrue("Production input must accept its accessibility action: $label",
                field.accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, payload))
        } finally {
            payload.clear()
            field.recycle()
        }
    }

    private fun waitAppText(pattern: Pattern): String {
        // Only sanitized, fixed status patterns are inspected; no raw hierarchy/error panel output.
        val selector = By.displayId(0).pkg(context.packageName).text(pattern)
        var visible = device.findObject(selector)
        // Status is below the controls in the real ScrollView. Search twice to account for a
        // promise resolving after the first pass; never accept a native-client substitute.
        repeat(2) {
            if (visible == null) {
                UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(12)
                    .scrollIntoView(UiSelector().textMatches(pattern.pattern()))
                visible = device.wait(Until.findObject(selector), 5000)
            }
        }
        val result = checkNotNull(visible) { "Production UI did not confirm the required stage within its bound." }
        try { return checkNotNull(result.text) { "Production success status is unreadable." } }
        finally { result.recycle() }
    }

    private fun success(phase: String, uid: Int = -1, pid: Int = -1, displayId: Int = -1) {
        Log.i("PocketRun-ProductAdb", "phase=$phase appUID=${Process.myUid()} backendUID=$uid backendPID=$pid " +
            "displayId=$displayId sourceAPK=${context.applicationInfo.sourceDir} sourceAPKsha256=$apkSha")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val AUTHORIZED_SERIAL = "58131JEBF07460"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private val WIRELESS_ENTRY = Pattern.compile("无线调试|Wireless debugging")
        private val PAIR_ENTRY = Pattern.compile("使用配对码配对设备|Pair device with pairing code")
        private val ADDRESS = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:[0-9]+")
        private val PAIR_CODE = Pattern.compile("[0-9]{6}")
        private val BACKEND_PONG = Pattern.compile("Binder pong，后端 UID：2000，PID：([1-9][0-9]*)")
        private val DISPLAY_CREATED = Pattern.compile("Demo 已启动到虚拟屏，displayId：([1-9][0-9]*)")
    }
}
