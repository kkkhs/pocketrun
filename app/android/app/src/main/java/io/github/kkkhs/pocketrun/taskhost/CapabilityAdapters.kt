package io.github.kkkhs.pocketrun.taskhost

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

/** Immutable capability snapshots supplied by the eventual Android/Linux adapters. */
data class DeviceInfoTarget(val packageUid: Int, val available: Boolean = true)

data class LinuxTarget(
    val rootfsGeneration: String,
    val packageUid: Int,
    val workspaceRoot: Path,
    val verifiedLinuxSession: Boolean,
    val backendAlive: Boolean,
)

data class DisplayTarget(
    val displayEpoch: Long,
    val displayId: Int,
    val packageUid: Int,
    val backendReady: Boolean,
)

data class WorkspaceTarget(val packageUid: Int, val root: Path, val inputDigest: String)

data class DemoObservation(
    val target: DisplayTarget,
    val value: String,
    val resultValue: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val focused: Boolean,
) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}

/**
 * An adapter creates the resource first; TaskHost installs sole ownership before start().
 * close() must cancel the native/process/Binder work it owns. After a successful close it is
 * idempotent; after a thrown close it must retain enough ownership for a later close retry.
 */
interface OwnedExecution : Closeable {
    fun start(completion: (ExecutionOutcome) -> Unit)
}

sealed interface ExecutionOutcome {
    data class Success(val result: ToolResult) : ExecutionOutcome
    data class Failure(val code: String, val message: String, val result: ToolResult? = null) : ExecutionOutcome
}

class ToolResult(fields: Map<String, String>) {
    val fields: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(fields))

    init {
        require(this.fields.size <= 32) { "Too many result fields." }
        require(this.fields.all { (key, value) ->
            key.toByteArray(Charsets.UTF_8).size in 1..64 &&
                value.toByteArray(Charsets.UTF_8).size <= 65_536
        }) { "Invalid result field." }
    }

    override fun equals(other: Any?) = other is ToolResult && fields == other.fields
    override fun hashCode() = fields.hashCode()
    override fun toString() = "ToolResult(fields=${fields.keys})"
}

/**
 * Main will implement this with DeviceInfo, verified LinuxSession, and typed Binder adapters.
 * Each prepare method must atomically compare its expected snapshot under the capability owner's
 * lifecycle lock before acquiring work; the host-side recheck is not permission to race a new
 * rootfs/display generation.
 */
interface TaskCapabilities {
    fun currentDeviceInfoTarget(): DeviceInfoTarget
    fun currentLinuxTarget(): LinuxTarget
    fun currentDisplayTarget(): DisplayTarget

    fun prepareDeviceInfo(expected: DeviceInfoTarget): OwnedExecution
    fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path): OwnedExecution
    fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution

    fun currentWorkspaceTarget(): WorkspaceTarget = throw UnsupportedOperationException("Workspace capability unavailable.")
    fun currentDemoObservation(expected: DisplayTarget): DemoObservation = throw UnsupportedOperationException("Demo observation unavailable.")
    fun currentDemoResult(): ByteArray? = throw UnsupportedOperationException("Demo result unavailable.")
    fun prepareCompleteDisplay(expected: DisplayTarget, action: DisplayAction, skipInput: () -> Boolean): OwnedExecution =
        throw UnsupportedOperationException("Complete Demo display unavailable.")
    fun prepareReadDemoCsv(expected: WorkspaceTarget): OwnedExecution = throw UnsupportedOperationException("Demo file read unavailable.")
    fun preparePublishDemoResult(expected: WorkspaceTarget, text: String, guard: () -> Unit): OwnedExecution =
        throw UnsupportedOperationException("Demo publication unavailable.")
}

sealed interface DisplayAction {
    data object Observe : DisplayAction
    data object ObserveDemo : DisplayAction
    data class DemoTap(val x: Int, val y: Int) : DisplayAction
    data class DemoText(val text: String) : DisplayAction
}

/** Fixed guest path is /workspace/items.csv; host path must remain a real child without symlinks. */
object CanonicalChildPath {
    const val DEMO_GUEST_PATH = "/workspace/items.csv"
    const val DEMO_RELATIVE_PATH = "items.csv"

    fun existingRegularFile(root: Path, relative: String): Path {
        if (relative.toByteArray(Charsets.UTF_8).size !in 1..128) {
            throw ToolValidationException("INVALID_PATH", "Invalid child path length.")
        }
        val requested = try {
            Paths.get(relative)
        } catch (_: RuntimeException) {
            throw ToolValidationException("INVALID_PATH", "Invalid child path.")
        }
        if (requested.isAbsolute || requested.nameCount == 0 ||
            requested.any { it.toString().isEmpty() || it.toString() == "." || it.toString() == ".." }) {
            throw ToolValidationException("PATH_TRAVERSAL", "Child path must stay below its trusted root.")
        }

        val absoluteRoot = root.toAbsolutePath().normalize()
        val realRoot = try {
            root.toRealPath()
        } catch (_: Exception) {
            throw ToolValidationException("INVALID_PATH", "Trusted root is unavailable.")
        }
        if (absoluteRoot != realRoot || Files.isSymbolicLink(absoluteRoot)) {
            throw ToolValidationException("INVALID_PATH", "Trusted root must already be canonical.")
        }
        val candidate = absoluteRoot.resolve(requested).normalize()
        if (!candidate.startsWith(absoluteRoot)) {
            throw ToolValidationException("PATH_TRAVERSAL", "Child path escaped its trusted root.")
        }

        var cursor = absoluteRoot
        for (segment in requested) {
            cursor = cursor.resolve(segment)
            if (Files.isSymbolicLink(cursor)) {
                throw ToolValidationException("SYMLINK_REJECTED", "Symlink child paths are not accepted.")
            }
        }
        val realChild = try {
            candidate.toRealPath()
        } catch (_: Exception) {
            throw ToolValidationException("INVALID_PATH", "Demo CSV is unavailable.")
        }
        if (realChild != candidate || !realChild.startsWith(realRoot) ||
            !Files.isRegularFile(realChild, LinkOption.NOFOLLOW_LINKS)) {
            throw ToolValidationException("INVALID_PATH", "Demo CSV is not a canonical regular child.")
        }
        return realChild
    }
}
