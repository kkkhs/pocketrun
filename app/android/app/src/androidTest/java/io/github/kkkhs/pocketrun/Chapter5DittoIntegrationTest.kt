package io.github.kkkhs.pocketrun

import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbTransport
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real TLS ADB -> UID 2000 backend -> owned display test. Never run on an unselected device.
 * Required: ownedAvdName OR authorizedSerial, adbConnectPort, and either adbAlreadyPaired=true
 * or adbPairPort + adbPairCode. Pairing secrets stay in memory and are never logged by this test.
 * No mock, Assume, dependency installation, product arbitrary-shell API, or default-display input.
 */
@RunWith(AndroidJUnit4::class)
class Chapter5DittoIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val cachedImages = mutableListOf<File>()
    private val ownedPids = mutableSetOf<Int>()
    private val ownedDisplayIds = mutableSetOf<Int>()
    private lateinit var inspection: (String) -> String

    @Test fun realScreenshotTreeInputRejectAndCleanup() {
        assertTrue("Select exactly one ownedAvdName or authorizedSerial before pairing",
            (arguments.getString("ownedAvdName") != null) xor (arguments.getString("authorizedSerial") != null))
        val host = arguments.getString("adbHost") ?: "127.0.0.1"
        assertEquals("ADB must address this device, not another device", "127.0.0.1", host)
        val connectPort = requiredPort("adbConnectPort")
        // Read-only owner verification precedes even key creation and real pairing mutation.
        // Do not acquire instrumentation UiAutomation just to run getprop.
        assertAuthorizedDevice()
        val key = AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences("pocketrun_adb_test_identity", 0)),
            "pocketrun-test@android",
        )
        pairIfRequested(host, key)
        inspection = { command ->
            AdbTransport().use { transport ->
                AdbClient(host, connectPort, key, transport).use { client ->
                    client.connect(requireTls = true)
                    boundedShellOutput(client, command, 512 * 1024)
                }
            }
        }
        assertUnauthorizedExchangeIsRejected()
        assertFalse("No old Demo may be present on the selected device", inspect("dumpsys activity activities").contains("DisplayDemoActivity"))
        val backend = PrivilegedBackendController(context)
        try {
            val first = startBackend(backend, host, connectPort, key)
            assertEquals(2000, first.uid)
            assertTrue(first.pid > 0)
            assertEquals("pong", first.reply)
            // startBackend closes the ADB connection; all feature calls below are Binder RPCs.
            assertEquals(first.pid, backend.ping().pid)
            val rpc = realBinderProxy(backend)
            val displayId = backend.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertOwnedDisplay(displayId)

            assertScreenshot(rpc, displayId)
            val preview = backend.captureScreenshot(displayId)
            assertEquals(displayId, preview.displayId)
            assertEquals(VirtualDisplaySession.WIDTH, preview.width)
            assertEquals(VirtualDisplaySession.HEIGHT, preview.height)
            assertTrue("Timestamp must preserve nanoseconds as a decimal string", preview.frameTimestampNanos.toLong() > 0)
            assertPrivateCacheImage(preview.uri)

            val initialTree = awaitAvailableTree(rpc, displayId)
            val ownedThreadIds = assertResourceThreadsLive(first.pid)
            val input = initialTree.nodes.singleOrNull {
                it.editable && it.enabled && it.contentDescription == INPUT_DESCRIPTION &&
                    it.viewId == "android:id/edit" && it.packageName == context.packageName
            }
            assertNotNull("The real editable Demo input must exist exactly once", input)
            val target = checkNotNull(input)
            assertTrue(target.right > target.left && target.bottom > target.top)
            val x = target.left + (target.right - target.left) / 2
            val y = target.top + (target.bottom - target.top) / 2
            assertTrue(x in 0 until VirtualDisplaySession.WIDTH && y in 0 until VirtualDisplaySession.HEIGHT)

            assertRejectedDisplay(rpc, 0)
            val wrongId = if (displayId == Int.MAX_VALUE) displayId - 1 else displayId + 1
            assertRejectedDisplay(rpc, wrongId)
            assertRejectedTap(rpc, displayId, -1, y)
            assertRejectedTap(rpc, displayId, x, -1)
            assertRejectedTap(rpc, displayId, VirtualDisplaySession.WIDTH, y)
            assertRejectedTap(rpc, displayId, x, VirtualDisplaySession.HEIGHT)
            assertRejectedTap(rpc, displayId, Int.MAX_VALUE, Int.MAX_VALUE)
            for (text in listOf("非ASCII", "bad\ntext", "bad\ttext", "bad\u0000text", "bad\u007ftext")) {
                assertRejectedText(rpc, displayId, text)
            }
            assertTrue("Tap must be acknowledged on the requested virtual display", rpc.injectTap(displayId, x, y))
            assertTrue("Printable ASCII input must be acknowledged", rpc.injectText(displayId, INPUT_TEXT))
            assertTrue("An acknowledged injection is not proof: wait for actual input and result text", waitUntil {
                val tree = checkedTree(rpc, displayId)
                tree.status == UiTreeCodec.Status.Available &&
                    tree.nodes.any { it.editable && it.contentDescription == INPUT_DESCRIPTION && it.text == INPUT_TEXT } &&
                    tree.nodes.any { !it.editable && it.className == "android.widget.TextView" &&
                        it.viewId == "android:id/text1" && it.contentDescription == "演示输入结果" && it.text == "input: $INPUT_TEXT" }
            })
            // Inspect system task routing via retained pairing identity, not UiDevice/UiAutomation:
            // an instrumentation automation connection can displace the shell automation service.
            assertFalse("Demo must not migrate to the main display", demoDisplayIds().contains(0))
            val afterInput = awaitAvailableTree(rpc, displayId)
            assertTrue(afterInput.nodes.any { it.editable && it.text == INPUT_TEXT })
            assertOwnedDisplay(displayId)
            assertScreenshot(rpc, displayId)

            backend.releaseDisplay()
            assertEquals(-1, backend.displayStatus().displayId)
            assertRejectedDisplay(rpc, displayId)
            assertTrue("Release must close frame/query/event threads and finish asynchronous UiClose", waitUntil {
                val threads = processThreads(first.pid)
                threads.keys.none { it in ownedThreadIds } && threads.values.none(::isResourceThread)
            })
            assertReleased(displayId)
            assertImagesRemoved()
            assertFalse("Released Demo must not move to display 0", demoDisplayIds().contains(0))

            // Concurrent lifecycle preparation, on the same genuine Binder and pairing key.
            val raceId = backend.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertOwnedDisplay(raceId)
            awaitAvailableTree(rpc, raceId)
            val raceThreadIds = assertResourceThreadsLive(first.pid)
            assertDumpReleaseRace(backend, rpc, raceId)
            assertEquals(-1, backend.displayStatus().displayId)
            assertReleased(raceId)
            assertTrue("In-flight release must eventually finish owned thread cleanup", waitUntil {
                val threads = processThreads(first.pid)
                threads.keys.none { it in raceThreadIds } && threads.values.none(::isResourceThread)
            })
            assertRejectedDisplay(rpc, raceId)

            // Recreate only after actual display/thread cleanup; old IDs must stay stale.
            val secondId = backend.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertTrue("Android must not reuse a released ID within this run", secondId != displayId && secondId != raceId)
            assertOwnedDisplay(secondId)
            assertRejectedDisplay(rpc, displayId)
            assertRejectedDisplay(rpc, raceId)
            awaitAvailableTree(rpc, secondId)
            assertPrivateCacheImage(backend.captureScreenshot(secondId).uri)
            backend.stop()
            assertTrue("Shutdown must update controller state", waitUntil { !backend.status().running })
            assertTrue("Shutdown must terminate the owned PID", waitUntil { !processExists(first.pid) })
            assertReleased(secondId)
            assertDeadRpc(rpc, secondId)
            assertImagesRemoved()
            assertFalse("Stopped Demo must not move to display 0", demoDisplayIds().contains(0))

            // An unexpected death is distinct from release and orderly shutdown.
            val restarted = startBackend(backend, host, connectPort, key)
            assertTrue(restarted.pid != first.pid)
            val deathRpc = realBinderProxy(backend)
            val deathId = backend.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertOwnedDisplay(deathId)
            awaitAvailableTree(deathRpc, deathId)
            assertPrivateCacheImage(backend.captureScreenshot(deathId).uri)
            assertTrue(restarted.pid > 0)
            inspect("kill ${restarted.pid}") // Only the PID returned by this run's backend.
            assertTrue("Controller must observe Binder death", waitUntil { !backend.status().running })
            assertTrue("Killed backend process must disappear", waitUntil { !processExists(restarted.pid) })
            assertReleased(deathId)
            assertDeadRpc(deathRpc, deathId)
            assertImagesRemoved()
            assertNoMainDisplayPollution()
        } finally {
            // Never turn cleanup failure into a passing test, and never delete another task's cache.
            backend.close()
            assertTrue("No backend owned by this test may remain", waitUntil {
                !backend.status().running && ownedPids.none(::processExists)
            })
            assertTrue("No display owned by this test may remain after cleanup", waitUntil {
                ownedDisplayIds.none(::systemHasDisplay)
            })
            if (ownedDisplayIds.isNotEmpty()) {
                assertReleased(ownedDisplayIds.first())
            }
            assertImagesRemoved()
        }
    }

    /** Both service owners are this run's genuine App-started UID 2000 backends, not platform/peer services. */
    @Test fun failedRegistrationReleasesCallbackThreadsAndRetries() {
        val host = arguments.getString("adbHost") ?: "127.0.0.1"
        assertEquals("ADB must address this authorized device", "127.0.0.1", host)
        val port = requiredPort("adbConnectPort")
        assertAuthorizedDevice()
        val key = AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("pocketrun_adb_test_identity", 0)),
            "pocketrun-test@android")
        pairIfRequested(host, key)
        inspection = { command ->
            AdbTransport().use { transport ->
                AdbClient(host, port, key, transport).use { client ->
                    client.connect(requireTls = true)
                    boundedShellOutput(client, command, 512 * 1024)
                }
            }
        }
        assertFalse("No old Demo may be present", inspect("dumpsys activity activities").contains("DisplayDemoActivity"))
        val owner = PrivilegedBackendController(context)
        val contender = PrivilegedBackendController(context)
        try {
            val first = startBackend(owner, host, port, key)
            assertEquals(2000, first.uid)
            val ownerRpc = realBinderProxy(owner)
            val ownerId = owner.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertOwnedDisplay(ownerId)
            // A pre-existing unrelated registration must fail here, never be displaced or stopped.
            awaitAvailableTree(ownerRpc, ownerId)
            val ownerThreads = assertResourceThreadsLive(first.pid)
            recordThreadPhase("controlled-owner-connected", first)

            val second = startBackend(contender, host, port, key)
            assertEquals(2000, second.uid)
            assertTrue(second.pid != first.pid)
            val rpc = realBinderProxy(contender)
            val failedId = contender.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertTrue(waitUntil { systemHasDisplay(failedId) })
            recordThreadPhase("before-failed-registration", second)
            repeat(2) { attempt ->
                assertEquals("A genuine second registration must be rejected, not time out or fabricate a tree",
                    UiTreeCodec.Status.QueryFailed, checkedTree(rpc, failedId).status)
                assertTrue("Every failed query must terminate framework callback and PocketRun event threads", waitUntil {
                    processThreads(second.pid).values.none {
                        it == "UiAutomation" || it.startsWith("PocketRun-UiEvents-".take(15))
                    }
                })
                assertFailedConnectEvidence(second.pid, failedId, attempt + 1)
                recordThreadPhase("failed-registration-${attempt + 1}-cleaned", second)
                val stillOwned = processThreads(first.pid)
                assertTrue("Failed cleanup must preserve the original controlled owner's exact TIDs",
                    ownerThreads.all { it in stillOwned })
                assertTrue("The original service must still answer queries after rejection/cleanup",
                    checkedTree(ownerRpc, ownerId).status in setOf(UiTreeCodec.Status.Available,
                        UiTreeCodec.Status.NoWindow, UiTreeCodec.Status.RootUnavailable))
            }
            contender.releaseDisplay()
            assertTrue(waitUntil { !systemHasDisplay(failedId) && processThreads(second.pid).values.none(::isResourceThread) })
            recordThreadPhase("failed-display-released", second)
            assertTrue(ownerThreads.all { it in processThreads(first.pid) })

            // Release ONLY our first owner; never stop another/platform automation service.
            owner.releaseDisplay()
            assertTrue(waitUntil { !systemHasDisplay(ownerId) && processThreads(first.pid).values.none(::isResourceThread) })
            recordThreadPhase("controlled-owner-released", first)
            val retryId = contender.createDemoDisplay().displayId.also { ownedDisplayIds += it }
            assertTrue(retryId != failedId && retryId != ownerId)
            assertOwnedDisplay(retryId)
            awaitAvailableTree(rpc, retryId)
            val retryThreads = assertResourceThreadsLive(second.pid)
            recordThreadPhase("retry-connected", second)
            contender.releaseDisplay()
            assertTrue("Successful retry must also close the actual framework UiAutomation thread", waitUntil {
                val threads = processThreads(second.pid)
                retryThreads.none { it in threads } && threads.values.none(::isResourceThread)
            })
            recordThreadPhase("retry-released", second)
            assertReleased(retryId)
        } finally {
            try { contender.close() } finally { owner.close() }
            assertTrue("All test-owned backend PIDs must actually exit", waitUntil { ownedPids.none(::processExists) })
            assertTrue("All test-owned displays must disappear", waitUntil { ownedDisplayIds.none(::systemHasDisplay) })
        }
    }

    private fun recordThreadPhase(phase: String, info: PrivilegedBackendController.Info) {
        Log.i("PocketRun-Chapter5", "phase=$phase sourceAPK=${context.applicationInfo.sourceDir} " +
            "sourceSHA=${arguments.getString("sourceSha") ?: "unprovided"} API=${Build.VERSION.SDK_INT} " +
            "backendUID=${info.uid} backendPID=${info.pid} TID_NAMES=${processThreads(info.pid).toSortedMap()}")
    }

    private fun assertFailedConnectEvidence(pid: Int, displayId: Int, attempts: Int) {
        val logs = inspect("logcat -d -v brief -s PocketRun-UiAutomation:I '*:S'").lineSequence()
            .filter { it.contains("backendPID=$pid displayId=$displayId ") }.toList()
        assertTrue("Native source diagnostic must prove actual already-registered rejection, not any QueryFailed",
            logs.count { it.contains("connect-failed") && it.contains("already registered!") } >= attempts)
        val identity = Regex("thread=UiAutomation/(\\d+) identity=(\\d+)")
        val captured = logs.filter { it.contains("connect-return sourceApk=") && it.contains("groupIdentity=") }
            .mapNotNull { identity.find(it)?.value }.toSet()
        val quit = logs.filter { it.contains("callback-quit") }.mapNotNull { identity.find(it)?.value }.toSet()
        assertTrue("Native source diagnostic must capture each new framework thread by object/Looper/group identity",
            captured.size >= attempts)
        assertTrue("EVERY captured callback identity must be quit, with confirmed exit rather than just a count",
            captured.all { it in quit } &&
                logs.count { it.contains("cleanup") && it.contains("callbackThreadsExited=true") } >= attempts)
    }

    private fun assertAuthorizedDevice() {
        val avd = arguments.getString("ownedAvdName")
        val serial = arguments.getString("authorizedSerial")
        assertTrue("Select exactly one ownedAvdName or authorizedSerial; no implicit device authorization", (avd != null) xor (serial != null))
        if (avd != null) {
            assertTrue("Owned AVD name must not be empty", avd.isNotBlank())
            assertEquals("The connected AVD is not the user's selected AVD", avd,
                readOwnerProperty("ro.boot.qemu.avd_name"))
        } else {
            assertTrue("Authorized hardware serial must not be empty", !serial.isNullOrBlank())
            assertEquals("The connected hardware is not the user's authorized device", serial,
                readOwnerProperty("ro.serialno"))
        }
        assertTrue("Wireless debugging requires Android 11+", Build.VERSION.SDK_INT >= 30)
        val expectedApi = arguments.getString("authorizedApi")?.toIntOrNull() ?: 36
        assertTrue("This source fixture covers only the explicitly selected Android 15/16 API", expectedApi in setOf(35, 36))
        assertEquals("The installed device must match authorizedApi; neither source review nor another API is Native evidence",
            expectedApi, Build.VERSION.SDK_INT)
        assertTrue("Use an ARM64 device/AVD", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertEquals("Enable Developer options explicitly before running", 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
    }

    /** Exactly two fixed, read-only getprop arguments; no shell interpreter or runner command. */
    private fun readOwnerProperty(property: String): String {
        require(property == "ro.boot.qemu.avd_name" || property == "ro.serialno")
        val process = ProcessBuilder(listOf("/system/bin/getprop", property)).redirectErrorStream(true).start()
        try {
            assertTrue("Owner property observation timed out before pairing; device authorization is blocked",
                process.waitFor(3, TimeUnit.SECONDS))
            assertEquals("Owner property observation was denied/failed before pairing", 0, process.exitValue())
            val output = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024) { "Owner property output exceeded its read-only limit" }
                    output.write(buffer, 0, count)
                }
            }
            val value = output.toString("UTF-8").trim()
            assertTrue("Owner property is empty/unreadable; do not pair or fall back to post-pair authorization", value.isNotBlank())
            return value
        } finally {
            try {
                if (process.isAlive) process.destroyForcibly()
                assertTrue("Read-only owner probe must not leave a child process", process.waitFor(2, TimeUnit.SECONDS))
            } finally {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }

    private fun pairIfRequested(host: String, key: AdbKey) {
        if (arguments.getString("adbAlreadyPaired") == "true") {
            assertTrue("Do not supply a pairing secret in already-paired mode", arguments.getString("adbPairCode") == null)
            return
        }
        val pairPort = requiredPort("adbPairPort")
        val code = arguments.getString("adbPairCode")
        assertTrue("Supply the current system pairing code; it is not a fixture credential", code != null && Regex("[0-9]{6}").matches(code))
        val storeIdentity = context.getSharedPreferences("pocketrun_adb_test_identity", 0)
        PAIRING_STATE.once(instrumentation, storeIdentity) {
            AdbTransport().use { transport ->
                AdbPairingClient(host, pairPort, checkNotNull(code), key, transport).use { pairing ->
                    assertTrue("Real system TLS pairing was rejected", pairing.start())
                }
            }
        }
    }

    private fun requiredPort(name: String): Int {
        val port = arguments.getString(name)?.toIntOrNull()
        assertTrue("Supply the current system $name in 1..65535", port != null && port in 1..65535)
        return checkNotNull(port)
    }

    private fun startBackend(backend: PrivilegedBackendController, host: String, port: Int, key: AdbKey): PrivilegedBackendController.Info =
        AdbTransport().use { transport ->
            AdbClient(host, port, key, transport).use { client ->
                client.connect(requireTls = true)
                assertEquals("2000", shellOutput(client, "id -u").trim())
                assertEquals("2000", shellOutput(client, "id -u").trim())
                backend.start { command -> shellOutput(client, command) }.also { ownedPids += it.pid }
            }
        }

    private fun shellOutput(client: AdbClient, command: String): String = boundedShellOutput(client, command, 1024)

    private fun boundedShellOutput(client: AdbClient, command: String, limit: Int): String {
        val output = ByteArrayOutputStream()
        client.shellCommand(command) { bytes ->
            require(output.size() + bytes.size <= limit) { "Bounded fixture ADB output exceeded its limit" }
            output.write(bytes)
        }
        return output.toString("UTF-8")
    }

    /** Fixed test-only observations; never a product or runner-supplied arbitrary-shell API. */
    private fun inspect(command: String): String {
        val fixed = command in setOf("getprop ro.boot.qemu.avd_name", "getprop ro.serialno",
            "dumpsys activity activities", "dumpsys window windows", "logcat -d -v brief -s PocketRun-UiAutomation:I '*:S'")
        val ownProcess = ownedPids.any {
            command == "ps -p $it -o PID" || command == "ps -T -p $it -o TID,CMD" || command == "kill $it"
        }
        require(fixed || ownProcess) { "Only fixed observations and this run's validated backend PID are allowed" }
        return inspection(command)
    }

    /** Inspect only the real controller's retained proxy; do not add a production test backdoor. */
    private fun realBinderProxy(backend: PrivilegedBackendController): IPrivilegedBackend =
        PrivilegedBackendController::class.java.getDeclaredField("service").let { field ->
            field.isAccessible = true
            checkNotNull(field.get(backend) as? IPrivilegedBackend)
        }

    private fun assertScreenshot(rpc: IPrivilegedBackend, displayId: Int) {
        val memory: SharedMemory = checkNotNull(rpc.captureScreenshot(displayId))
        memory.use {
            assertTrue(memory.size in 33..(32 + ScreenshotCodec.MAX_JPEG_BYTES))
            val header = memory.mapReadOnly().order(ByteOrder.BIG_ENDIAN)
            val bytes = try {
                assertEquals(0x50525353, header.int)
                assertEquals(1, header.int)
                assertEquals(displayId, header.int)
                assertEquals(VirtualDisplaySession.WIDTH, header.int)
                assertEquals(VirtualDisplaySession.HEIGHT, header.int)
                assertTrue(header.long > 0)
                assertEquals(memory.size - 32, header.int)
                header.rewind()
                ByteArray(memory.size).also(header::get)
            } finally {
                SharedMemory.unmap(header)
            }
            val packet = ScreenshotCodec.decode(bytes, displayId)
            val copied = ScreenshotCodec.fromSharedMemory(memory, displayId)
            assertEquals(displayId, copied.displayId)
            assertTrue(packet.jpeg.contentEquals(copied.jpeg))
            assertTrue(packet.jpeg.isNotEmpty())
            val bitmap = BitmapFactory.decodeByteArray(packet.jpeg, 0, packet.jpeg.size)
            assertNotNull("Payload must be a decodable real JPEG, not just a nonempty byte array", bitmap)
            checkNotNull(bitmap).let {
                try {
                    assertEquals(packet.width, it.width)
                    assertEquals(packet.height, it.height)
                } finally { it.recycle() }
            }
            var writable = false
            try {
                val writeMap = memory.mapReadWrite()
                writable = true
                SharedMemory.unmap(writeMap)
            } catch (_: android.system.ErrnoException) {
                // A protected real SharedMemory FD must deny a writable mapping.
            }
            assertFalse("The backend must mark screenshot SharedMemory read-only", writable)
        }
    }

    private fun assertPrivateCacheImage(value: String) {
        val uri = Uri.parse(value)
        assertEquals("No public/external/provider URI may be returned", "file", uri.scheme)
        val file = File(checkNotNull(uri.path)).canonicalFile
        val cache = context.cacheDir.canonicalFile
        assertTrue("Screenshot URI must stay under this App's private cache", file.path.startsWith(cache.path + File.separator))
        assertTrue(file.isFile && file.length() in 1..ScreenshotCodec.MAX_JPEG_BYTES.toLong())
        val stat = Os.stat(file.path)
        assertEquals("The cache file must be owned by the App UID", Process.myUid(), stat.st_uid)
        assertEquals("The cache image must not grant group/other access", 0, stat.st_mode and 0x3f)
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull("Private preview must contain the actual JPEG", bitmap)
        bitmap?.recycle()
        cachedImages += file
    }

    private fun checkedTree(rpc: IPrivilegedBackend, displayId: Int): UiTreeCodec.Tree =
        decodeCheckedTree(checkNotNull(rpc.dumpUiTree(displayId)), displayId)

    private fun decodeCheckedTree(bytes: ByteArray, displayId: Int): UiTreeCodec.Tree {
        assertTrue(bytes.size in 21..UiTreeCodec.MAX_TREE_BYTES)
        val tree = UiTreeCodec.decode(bytes, displayId)
        assertEquals(displayId, tree.displayId)
        assertTrue(tree.nodes.size <= UiTreeCodec.MAX_NODES)
        tree.nodes.forEachIndexed { index, node ->
            assertTrue(node.depth in 0..UiTreeCodec.MAX_DEPTH)
            assertTrue("Parent index must point backward or mark a root", node.parentIndex == -1 || node.parentIndex in 0 until index)
        }
        if (tree.status != UiTreeCodec.Status.Available) {
            assertTrue("Typed unavailable states must not fabricate nodes", tree.nodes.isEmpty())
        }
        return tree
    }

    private fun awaitAvailableTree(rpc: IPrivilegedBackend, displayId: Int): UiTreeCodec.Tree {
        var result: UiTreeCodec.Tree? = null
        assertTrue("Real UiAutomation must expose an available tree on the exact display; no permission skip", waitUntil {
            checkedTree(rpc, displayId).also { result = it }.status == UiTreeCodec.Status.Available &&
                result?.nodes?.any { it.editable && it.contentDescription == INPUT_DESCRIPTION } == true
        })
        return checkNotNull(result).also { assertFalse("The small Demo tree must not be truncated", it.truncated) }
    }

    private fun assertDumpReleaseRace(backend: PrivilegedBackendController, rpc: IPrivilegedBackend, expectedId: Int) {
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<*>>()
        try {
            val dump = pool.submit<UiTreeCodec.Tree?> {
                ready.countDown()
                check(start.await(2, TimeUnit.SECONDS)) { "Dump did not reach the same-start barrier" }
                // Catch stale-session failures only from the actual RPC. Decoder failures,
                // including an incorrectly returned new-session ID, must never become a pass.
                val bytes = try {
                    rpc.dumpUiTree(expectedId)
                } catch (_: IllegalStateException) {
                    return@submit null
                } catch (_: IllegalArgumentException) {
                    return@submit null
                }
                decodeCheckedTree(checkNotNull(bytes), expectedId)
            }
            futures += dump
            val release = pool.submit<PrivilegedBackendController.DisplayInfo> {
                ready.countDown()
                check(start.await(2, TimeUnit.SECONDS)) { "Release did not reach the same-start barrier" }
                backend.releaseDisplay()
            }
            futures += release
            assertTrue("Both real Binder calls must be ready before the shared barrier", ready.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            start.countDown()
            val tree = awaitRaceFuture(dump, deadline)
            val released = awaitRaceFuture(release, deadline)
            assertFalse(released.active)
            assertEquals(-1, released.displayId)
            if (tree != null) {
                assertEquals("A racing dump must never return a future session's display", expectedId, tree.displayId)
                assertTrue("Only explicitly typed safe tree states are allowed", tree.status in setOf(
                    UiTreeCodec.Status.Available, UiTreeCodec.Status.NoWindow, UiTreeCodec.Status.RootUnavailable,
                    UiTreeCodec.Status.QueryFailed, UiTreeCodec.Status.Timeout, UiTreeCodec.Status.Busy,
                ))
            }
            // The barrier prepares a real race, but does not force which RPC wins or fabricate
            // a Busy/Timeout hit. Those statuses may be accepted, never claimed as exercised.
        } finally {
            start.countDown()
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
            assertTrue("Race fixture workers must terminate within their cleanup bound", pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun <T> awaitRaceFuture(future: Future<T>, deadlineNanos: Long): T {
        val remaining = deadlineNanos - System.nanoTime()
        assertTrue("Both racing calls must finish within the shared five-second deadline", remaining > 0)
        return future.get(remaining, TimeUnit.NANOSECONDS)
    }

    private fun assertRejectedDisplay(rpc: IPrivilegedBackend, displayId: Int) {
        assertThrows("Screenshot must reject default/wrong/stale display $displayId") { rpc.captureScreenshot(displayId)?.close() }
        assertThrows("Tree must reject default/wrong/stale display $displayId") { rpc.dumpUiTree(displayId) }
        assertRejectedTap(rpc, displayId, 1, 1)
        assertRejectedText(rpc, displayId, INPUT_TEXT)
    }

    private fun assertRejectedTap(rpc: IPrivilegedBackend, id: Int, x: Int, y: Int) =
        assertRejectedBoolean("Tap must reject invalid display/coordinates") { rpc.injectTap(id, x, y) }

    private fun assertRejectedText(rpc: IPrivilegedBackend, id: Int, text: String) =
        assertRejectedBoolean("Text must reject invalid display/non-printable ASCII") { rpc.injectText(id, text) }

    private fun assertRejectedBoolean(message: String, call: () -> Boolean) {
        try {
            assertFalse(message, call())
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
    }

    private fun assertThrows(message: String, call: () -> Unit) {
        try {
            call()
        } catch (_: IllegalArgumentException) { return
        } catch (_: IllegalStateException) { return
        } catch (_: SecurityException) { return
        }
        fail(message)
    }

    private fun assertDeadRpc(rpc: IPrivilegedBackend, displayId: Int) {
        val calls: List<() -> Unit> = listOf(
            { rpc.captureScreenshot(displayId)?.close(); Unit },
            { rpc.dumpUiTree(displayId); Unit },
            { rpc.injectTap(displayId, 1, 1); Unit },
            { rpc.injectText(displayId, INPUT_TEXT); Unit },
        )
        calls.forEach { call ->
            try { call(); fail("A retained dead Binder must not execute any old display RPC") }
            catch (_: android.os.DeadObjectException) { }
        }
    }

    private fun assertOwnedDisplay(displayId: Int) {
        assertTrue(displayId > 0)
        assertTrue("System DisplayManager must register the display", waitUntil { systemHasDisplay(displayId) })
        assertTrue("System task must independently belong to the same display", waitUntil { demoDisplayIds() == setOf(displayId) })
    }

    private fun assertReleased(displayId: Int) {
        assertTrue("Released display must disappear from DisplayManager", waitUntil { !systemHasDisplay(displayId) })
        assertTrue("Demo task/window must not survive display release", waitUntil {
            !inspect("dumpsys activity activities").contains("DisplayDemoActivity") &&
                !inspect("dumpsys window windows").contains("DisplayDemoActivity")
        })
    }

    private fun systemHasDisplay(id: Int): Boolean = context.getSystemService(DisplayManager::class.java).getDisplay(id) != null

    private fun demoDisplayIds(): Set<Int> {
        val dump = inspect("dumpsys activity activities")
        val ids = mutableSetOf<Int>()
        var current: Int? = null
        dump.lineSequence().forEach { line ->
            Regex("^\\s*Display #(\\d+)(?:\\s|:|$)").find(line)?.let { current = it.groupValues[1].toInt() }
            if (line.contains("DisplayDemoActivity")) current?.let(ids::add)
        }
        return ids
    }

    private fun assertNoMainDisplayPollution() {
        assertFalse("Demo Activity must never belong to display 0", demoDisplayIds().contains(0))
        // This is deliberately the first UiDevice use, after all backend instances have died.
        // Never reconnect shell automation afterward, or inspect Panel JSON with textContains.
        // UiDevice uses Instrumentation.getUiAutomation with exactly these Configurator flags.
        // Retain the actual object acquired by THIS test, not a name/global service reference.
        val flags = Configurator.getInstance().uiAutomationFlags
        val ownedAutomation = checkNotNull(instrumentation.getUiAutomation(flags))
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertFalse("Demo title must not appear on the main display", device.hasObject(By.displayId(0).text("PocketRun Virtual Display Demo")))
            assertFalse("Demo input must not appear on the main display", device.hasObject(By.displayId(0).desc(INPUT_DESCRIPTION)))
            assertFalse("Virtual-display text must not be injected into a main-display EditText",
                device.hasObject(By.displayId(0).clazz("android.widget.EditText").text(INPUT_TEXT)))
        } finally {
            // This fixture alone acquired it after all its backend PIDs died. A later test must
            // not inherit its registration. Do not obtain/disconnect any other automation here.
            ownedAutomation.javaClass.getMethod("disconnect").invoke(ownedAutomation)
        }
    }

    private fun assertImagesRemoved() {
        assertTrue("Release/shutdown/death must invalidate private screenshot files", waitUntil { cachedImages.none(File::exists) })
        cachedImages.clear()
    }

    private fun processExists(pid: Int): Boolean {
        require(pid > 0)
        val dump = inspect("ps -p $pid -o PID")
        assertTrue("Process observation must return a real ps header", dump.lineSequence().any { it.trim() == "PID" })
        return dump.lineSequence().any { it.trim() == pid.toString() }
    }

    private fun processThreads(pid: Int): Map<Int, String> {
        val dump = inspect("ps -T -p $pid -o TID,CMD")
        assertTrue("Thread observation must be supported, not silently empty", dump.lineSequence().any { it.contains("TID") && it.contains("CMD") })
        return dump.lineSequence().mapNotNull { line ->
            Regex("^\\s*(\\d+)\\s+(.+)$").find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() }
        }.toMap().also { assertTrue("The live owned backend must expose actual threads", it.isNotEmpty()) }
    }

    private fun isResourceThread(name: String): Boolean = RESOURCE_THREAD_NAMES.any { name.startsWith(it.take(15)) }

    private fun assertResourceThreadsLive(pid: Int): Set<Int> {
        var observed = emptyMap<Int, String>()
        val complete = waitUntil {
            observed = processThreads(pid)
            RESOURCE_THREAD_NAMES.take(4).all { expected -> observed.values.any { it.startsWith(expected.take(15)) } }
        }
        assertTrue("The real frame and UiAutomation query/event threads must be observable; observed=${observed.values.sorted()}", complete)
        return observed.filterValues(::isResourceThread).keys
    }

    private fun assertUnauthorizedExchangeIsRejected() {
        val extras = Bundle().apply {
            putString(BinderExchangeProvider.KEY_TOKEN, "A".repeat(43))
            putBinder(BinderExchangeProvider.KEY_BACKEND, Binder())
        }
        try {
            context.contentResolver.call("${context.packageName}.binder_exchange", BinderExchangeProvider.METHOD_EXCHANGE, null, extras)
            fail("App UID must not impersonate shell UID during Binder exchange")
        } catch (_: SecurityException) { }
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (predicate()) return true
            SystemClock.sleep(50) // Bounded polling interval, never a substitute for a result predicate.
        } while (SystemClock.elapsedRealtime() < deadline)
        return predicate()
    }

    companion object {
        private val PAIRING_STATE = Chapter5PairingState()
        private const val INPUT_DESCRIPTION = "演示输入框"
        private const val INPUT_TEXT = "PocketRun_Ditto_5"
        // Kernel comm/ps CMD can truncate to 15 bytes; TIDs tie these prefixes to this PID.
        private val RESOURCE_THREAD_NAMES = listOf("PocketRun-DisplayFrames", "PocketRun-UiQuery-",
            "PocketRun-UiEvents-", "UiAutomation", "PocketRun-UiClose-")
    }
}
