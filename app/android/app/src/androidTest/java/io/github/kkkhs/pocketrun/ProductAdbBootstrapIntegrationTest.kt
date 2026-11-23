package io.github.kkkhs.pocketrun

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task-owned API 36 ARM64 emulator only. Fresh Settings credentials never leave device memory; no screenshots/dumps,
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
        authorizeBeforeObserver() // Strict private binding validation AND one-shot consumption precede any observer.
        val observer = OwnedInstrumentationObserver(instrumentation, Configurator.getInstance().uiAutomationFlags)
        var failure: AssertionError? = null
        try {
            stage = "instrumentation-observer"
            val automation = observer.acquire() // The holder exists even if getUiAutomation returns null or throws.
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            device = UiDevice.getInstance(instrumentation)
            launchApp()
            assertTrue("Do not reuse/stop a pre-existing product backend", !appControl("停止 shell 后端").isEnabled)
            assertTrue("Do not disconnect an existing product ADB owner", appControl("配对").isEnabled)

            stage = "settings-fresh-pairing"
            openAlreadyEnabledWirelessDebugging()
            stage = "settings-connect-port"
            connectionPort = port(requiredSettingsText(ADDRESS))
            stage = "settings-native-split-screen"
            enterNativeSplitScreen()
            stage = "settings-open-pair-dialog"
            // Settings is the first/top split pane. Scroll it to focus its real window before lookup.
            device.swipe(device.displayWidth / 2, device.displayHeight * 5 / 12,
                device.displayWidth / 2, device.displayHeight / 8, 20)
            repeat(12) {
                val pair = device.findObject(By.pkg(SETTINGS_PACKAGE).text(PAIR_ENTRY))
                val visible = pair?.visibleBounds?.let { it.width() > 0 && it.height() > 0 } == true
                pair?.recycle()
                if (!visible) checkNotNull(device.findObject(By.pkg(SETTINGS_PACKAGE)
                    .res("com.android.settings:id/recycler_view"))).scroll(Direction.DOWN, 0.8f)
            }
            checkNotNull(device.findObject(By.pkg(SETTINGS_PACKAGE).text(PAIR_ENTRY))) {
                "The authorized Settings pairing entry is missing."
            }.click()
            stage = "settings-pair-code"
            pairingCode = requiredSettingsText(PAIR_CODE).toCharArray()
            stage = "settings-pair-port"
            pairingPort = port(requiredSettingsText(ADDRESS))
            Log.i("PocketRun-ProductAdb", "phase=settings-pair-listener-before-app listening=${pairListenerPresent()}")

            // NEW_TASK only: do not press Back/dismiss the still-live Settings pairing dialog.
            stage = "production-pair"
            launchApp()
            Log.i("PocketRun-ProductAdb", "phase=settings-pair-listener-after-app listening=${pairListenerPresent()}")
            wroteInputs = true
            stage = "production-pair-port-input"
            setInputWithoutLogging("配对端口", pairingPort.toString())
            stage = "production-pair-code-input"
            setInputWithoutLogging("六位配对码", String(checkNotNull(pairingCode)))
            stage = "production-pair-click"
            clickApp("配对")
            stage = "production-pair-result"
            waitAppText(Pattern.compile("配对完成。现在使用无线调试主页的连接端口。"), "adb-operation-status")
            success("production-paired")

            stage = "production-connect"
            setInputWithoutLogging("连接端口", connectionPort.toString())
            clickApp("连接")
            waitAppText(Pattern.compile("连接已验证，连接端 UID：2000"), "adb-operation-status")
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

            stage = "production-clear-pairing-inputs"
            for (label in listOf("六位配对码", "配对端口", "连接端口")) setInputWithoutLogging(label, "")
            wroteInputs = false
            val manager = context.getSystemService(DisplayManager::class.java)
            val previousIds = manager.displays.map { it.displayId }.toSet()
            stage = "production-display-create"
            clickApp("创建演示虚拟屏") // Last UI action: launching the Demo can focus its own display.
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
            var displayId: Int? = null
            while (displayId == null && System.nanoTime() < until) {
                val created = manager.displays.singleOrNull { it.name == VirtualDisplaySession.DISPLAY_NAME &&
                    it.displayId > 0 && it.displayId !in previousIds }
                if (created != null) {
                    val tasks = device.executeShellCommand("dumpsys activity activities")
                    check(tasks.length <= 524288)
                    var current: Int? = null
                    tasks.lineSequence().forEach { line ->
                        Regex("^\\s*Display #(\\d+)(?:\\s|:|$)").find(line)?.let { current = it.groupValues[1].toInt() }
                        if (current == created.displayId && line.contains("DisplayDemoActivity")) displayId = current
                    }
                }
                if (displayId == null) Thread.sleep(50)
            }
            val actualId = checkNotNull(displayId) { "The real UI action did not create and route its Demo display." }
            assertTrue(manager.getDisplay(actualId) != null)
            success("production-display-created-no-tree-query", uid = 2000, pid = backendPid, displayId = actualId)
        } catch (error: Throwable) {
            observer.diagnoseFailure("product-test-$stage-catch", error) // Classes + fixed category only, no raw cause/message.
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
            val workerExited = observer.closeAndConfirmWorker()
            Log.i("PocketRun-ObserverDiag", "stage=product-test-cleanup sourceUID=${Process.myUid()} sourcePID=${Process.myPid()} " +
                "fieldsCleared=$fieldsCleared acquisitionWorkerExited=$workerExited frameworkCleanup=runner-finish-pending")
            if (!fieldsCleared || !workerExited) {
                failure = AssertionError("Bootstrap cleanup blocked: fieldsCleared=$fieldsCleared workerExited=$workerExited.")
            }
            // Retain the actual product pairing/backend/display for the main-owned continuation.
            // No cancel/disconnect product button, unpair, releaseDisplay, stopBackend, or force-stop.
        }
        failure?.let { throw it }
    }

    /**
     * Normal App UID uses the host's owned-AVD attestation, not privileged hardware identifiers.
     * The main's fresh AVD/serial + boot guard writes this App-private nonce-bound receipt via run-as.
     * This is an explicit host identity attestation, NOT a boolean bypass or privileged phone API.
     */
    private fun authorizeBeforeObserver() {
        var point = "explicit-arguments"
        try {
            check(arguments.getString("authorizedSerial") == AUTHORIZED_SERIAL)
            check(arguments.getString("authorizedApi") == "36" && Build.VERSION.SDK_INT == 36)
            check(context.packageName == "io.github.kkkhs.pocketrun")
            check(context.applicationInfo.uid == Process.myUid())
            val nonce = checkNotNull(arguments.getString("deviceBindingNonce"))
            val expectedApk = checkNotNull(arguments.getString("sourceApkSha256"))
            check(nonce.matches(Regex("[0-9a-f]{64}")) && expectedApk.matches(Regex("[0-9a-f]{64}")))

            point = "fixed-private-file"
            val directory = File(context.filesDir, "device-bindings")
            check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            val directoryStat = Os.lstat(directory.absolutePath)
            check(directoryStat.st_uid == Process.myUid())
            check((directoryStat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR)
            check((directoryStat.st_mode and 0xfff) == 0x1c0) // Exactly 0700, no special/group/other bits.
            val receipt = File(directory, "product-adb-bootstrap.json") // Never supplied by arguments.
            val fd = Os.open(receipt.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            val opened: android.system.StructStat
            val text: String
            try {
                opened = Os.fstat(fd)
                check(opened.st_uid == Process.myUid())
                check((opened.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG)
                check((opened.st_mode and 0xfff) == 0x180 && opened.st_size in 1..4096 && opened.st_nlink == 1L) // 0600, one bounded regular file.
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val count = Os.read(fd, buffer, 0, buffer.size)
                    if (count == 0) break
                    check(count > 0 && output.size() + count <= 4096)
                    output.write(buffer, 0, count)
                }
                text = output.toString("UTF-8")
            } finally { Os.close(fd) }

            point = "strict-schema-and-nonce"
            val binding = JSONObject(text)
            check(binding.keys().asSequence().toSet() == BINDING_KEYS)
            fun number(key: String): Long {
                val value = binding.get(key)
                check(value is Int || value is Long) // No numeric-string/boolean/double coercion.
                return (value as Number).toLong()
            }
            fun string(key: String): String = (binding.get(key) as? String)
                ?: error("Invalid binding string type.")
            check(number("schema_v") == 1L)
            check(MessageDigest.isEqual(string("nonce").toByteArray(Charsets.US_ASCII), nonce.toByteArray(Charsets.US_ASCII)))

            point = "current-device-identity"
            check(string("serial") == AUTHORIZED_SERIAL)
            check(number("api") == Build.VERSION.SDK_INT.toLong())
            val abi = checkNotNull(Build.SUPPORTED_ABIS.firstOrNull())
            check(abi == "arm64-v8a" && string("abi") == abi)
            check(number("app_uid") == Process.myUid().toLong())
            point = "current-boot-count"
            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(bootCount >= 0 && number("boot_count") == bootCount.toLong())
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)

            point = "installed-source-apk"
            apkSha = sha256(File(context.applicationInfo.sourceDir))
            check(string("source_apk_sha256") == expectedApk && apkSha == expectedApk)
            point = "short-ttl"
            val expires = number("expires_at_epoch")
            val remaining = expires - System.currentTimeMillis() / 1000
            check(remaining in 1..120) // Host uses this device's current epoch, never a stale/long lease.

            point = "one-shot-consume"
            synchronized(CONSUMED_BINDINGS) {
                check(nonce !in CONSUMED_BINDINGS)
                val current = Os.lstat(receipt.absolutePath)
                check(current.st_ino == opened.st_ino && current.st_dev == opened.st_dev)
                check(expires > System.currentTimeMillis() / 1000)
                check(receipt.delete()) // Public File.delete unlinks this regular file; success is required BEFORE observer.
                CONSUMED_BINDINGS.add(nonce)
            }
            Log.i("PocketRun-ProductAdb", "phase=device-binding-consumed bindingSerial=$AUTHORIZED_SERIAL " +
                "API=${Build.VERSION.SDK_INT} ABI=$abi appUID=${Process.myUid()} bootCount=$bootCount " +
                "sourceAPKsha256=$apkSha") // No nonce, receipt body, pairing code, or port is published.
        } catch (error: Throwable) {
            // JSON/OS exceptions can include receipt contents. Publish only a fixed checkpoint/class.
            throw AssertionError("Device binding rejected BEFORE observer at $point (${error.javaClass.simpleName}).")
        }
    }

    private fun launchApp() {
        context.startActivity(Intent().setClassName(context.packageName, "${context.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        assertTrue("The real production MainActivity must be visible", device.wait(Until.hasObject(
            By.displayId(0).pkg(context.packageName)), 8000))
    }

    /** Reuses the existing pairing fixture's Settings route/regex; never changes toggle/Always/network policy. */
    private fun openAlreadyEnabledWirelessDebugging() {
        stage = "settings-open-developer-options"
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("Authorized Settings must open", device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), 5000))
        stage = "settings-scroll-wireless"
        UiScrollable(UiSelector().packageName(SETTINGS_PACKAGE).scrollable(true)).setMaxSearchSwipes(12)
            .scrollIntoView(UiSelector().textMatches("无线调试|Wireless debugging"))
        stage = "settings-click-wireless"
        checkNotNull(device.wait(Until.findObject(By.pkg(SETTINGS_PACKAGE).text(WIRELESS_ENTRY)), 5000))
            .click()
        stage = "settings-wait-pair-entry"
        assertTrue("User-approved wireless debugging must already expose pairing; do not toggle/approve it here",
            device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE).text(PAIR_ENTRY)), 5000))
    }

    private fun enterNativeSplitScreen() {
        device.pressRecentApps()
        checkNotNull(device.wait(Until.findObject(By.pkg("com.android.launcher3")
            .desc(Pattern.compile("Settings.*"))), 5000)) { "The selected recent task must be Settings." }
        val icons = device.findObjects(By.res("com.android.launcher3:id/icon"))
        check(icons.size == 1) { "Native split entry must identify exactly the current task icon." }
        icons.single().click()
        checkNotNull(device.wait(Until.findObject(By.pkg("com.android.launcher3").text("Split screen")), 5000)).click()
        checkNotNull(device.wait(Until.findObject(By.pkg("com.android.launcher3")
            .res("com.android.launcher3:id/snapshot").desc("PocketRun")), 5000)).click()
        assertTrue("Both App and Settings must remain visible for the live pairing dialog", device.wait(
            Until.hasObject(By.pkg(context.packageName)), 5000) && device.hasObject(By.pkg(SETTINGS_PACKAGE)))
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

    private fun pairListenerPresent(): Boolean {
        val listeners = device.executeShellCommand("ss -ltn")
        check(listeners.length <= 65536)
        return listeners.lineSequence().any { line ->
            line.trim().split(Regex("\\s+")).getOrNull(3)?.endsWith(":$pairingPort") == true
        }
    }

    private fun appControl(label: String): UiObject2 {
        val statusId = label.endsWith("-operation-status")
        val selector = By.displayId(0).pkg(context.packageName).apply {
            if (statusId) res(label) else desc(label)
        }
        repeat(3) { attempt ->
            val control = device.findObject(selector)
            val bounds = control?.visibleBounds
            val window = Rect().also { control?.accessibilityNodeInfo?.window?.getBoundsInScreen(it) }
            val bars = context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars())
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0 &&
                bounds.centerY() in window.top + bars.top + 20 until window.bottom - bars.bottom - 20) return checkNotNull(control)
            control?.recycle()
            if (attempt == 2) error("Required production control is not safely visible.")
            if (attempt == 0 || bounds == null) {
                val scroller = checkNotNull(device.findObject(By.displayId(0).pkg(context.packageName).scrollable(true)))
                val visible = Condition<UiObject2, UiObject2?> { scope ->
                    val found = scope.findObject(selector)
                    if (found?.visibleBounds?.let { it.width() > 0 && it.height() > 0 } == true) found
                    else { found?.recycle(); null }
                }
                try {
                    (scroller.scrollUntil(Direction.UP, visible)
                        ?: scroller.scrollUntil(Direction.DOWN, visible))?.recycle()
                } finally { scroller.recycle() }
            } else {
                check(window.height() > 200) { "The actual App window is too small for a safe control." }
                val upper = window.top + window.height() / 3
                val lower = window.top + window.height() * 2 / 3
                val start = if (bounds.centerY() < window.top + bars.top + 20) upper else lower
                val end = if (bounds.centerY() < window.top + bars.top + 20) lower else upper
                device.swipe(window.centerX(), start, window.centerX(), end, 20)
            }
        }
        error("Required production control is not safely visible.")
    }

    private fun clickApp(label: String) {
        val control = appControl(label)
        try {
            assertTrue("Production control must be enabled: $label", control.isEnabled)
            Log.i("PocketRun-ProductAdb", "phase=ui-control-touch label=$label bounds=${control.visibleBounds}")
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

    private fun waitAppText(pattern: Pattern, controlLabel: String? = null): String {
        // Only sanitized, fixed status patterns are inspected; no raw hierarchy/error panel output.
        val selector = By.displayId(0).pkg(context.packageName).text(pattern)
        if (controlLabel != null) {
            appControl(controlLabel).recycle()
            val result = device.wait(Until.findObject(selector.res(controlLabel)), 16000)
            if (result == null) {
                val status = appControl(controlLabel)
                val category = try {
                    when (status.text) {
                        "请输入系统显示的六位配对码。" -> "INVALID_CODE_INPUT"
                        "请输入 1～65535 的整数端口。" -> "INVALID_PORT_INPUT"
                        "操作超时，连接已关闭。" -> "TRANSPORT_TIMEOUT"
                        "配对失败，请检查配对码和配对端口。" -> "PAIR_REJECTED"
                        "尚未连接。" -> "NOT_STARTED"
                        "正在操作，最多等待 15 秒…" -> "STILL_RUNNING"
                        "ADB 操作失败。" -> "NATIVE_ERROR"
                        null -> "NO_STATUS_TEXT"
                        else -> if (status.text?.contains("ECONNREFUSED") == true) "CONNECT_REFUSED"
                            else if (status.text?.contains("Connection closed") == true) "CONNECTION_CLOSED"
                            else if (status.text?.let { pattern.matcher(it).matches() } == true) "EXPECTED_TEXT_SELECTOR_MISS"
                            else "UNEXPECTED_STATUS"
                    }
                } finally { status.recycle() }
                Log.i("PocketRun-ProductAdb", "phase=adb-ui-result category=$category")
                throw IllegalStateException("The actual ADB UI did not report success.")
            }
            try { return checkNotNull(result.text) }
            finally { result.recycle() }
        }
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
        private val CONSUMED_BINDINGS = mutableSetOf<String>()
        private val BINDING_KEYS = setOf("schema_v", "serial", "api", "abi", "app_uid", "boot_count",
            "source_apk_sha256", "expires_at_epoch", "nonce")
        private const val AUTHORIZED_SERIAL = "emulator-5556"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private val WIRELESS_ENTRY = Pattern.compile("无线调试|Wireless debugging")
        private val PAIR_ENTRY = Pattern.compile("使用配对码配对设备|Pair device with pairing code")
        private val ADDRESS = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:[0-9]+")
        private val PAIR_CODE = Pattern.compile("[0-9]{6}")
        private val BACKEND_PONG = Pattern.compile("Binder pong，后端 UID：2000，PID：([1-9][0-9]*)")
        private val DISPLAY_CREATED = Pattern.compile("Demo 已启动到虚拟屏，displayId：([1-9][0-9]*)")
    }
}
