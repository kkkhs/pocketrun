package io.github.kkkhs.pocketrun

import android.os.Process
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

@ReactModule(name = WorkspaceModule.NAME)
class WorkspaceModule(context: ReactApplicationContext) : NativePocketRunWorkspaceSpec(context) {
    private val session = WorkspaceSession(context.filesDir)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun getName() = NAME

    override fun prepareWorkspace(promise: Promise) {
        if (!begin(promise)) return
        submit(promise) {
            val info = session.prepare()
            Arguments.createMap().apply {
                putString("directory", info.directory)
                putString("sampleFile", info.sampleFile)
                putBoolean("sampleCreated", info.sampleCreated)
                putInt("appUid", Process.myUid())
            }
        }
    }

    override fun runDemoPipeline(promise: Promise) {
        if (!begin(promise)) return
        val run = try {
            session.reserve()
        } catch (error: Exception) {
            busy.set(false)
            promise.reject("E_WORKSPACE_PIPELINE", error.message, error)
            return
        }
        submit(promise) {
            val result = session.runDemo(run)
            val codes = Arguments.createArray().apply { result.exitCodes.forEach(::pushInt) }
            Arguments.createMap().apply {
                putString("status", result.status)
                putString("stdout", result.stdout)
                putString("stderr", result.stderr)
                putArray("exitCodes", codes)
                putDouble("durationMillis", result.durationMillis.toDouble())
                putInt("appUid", Process.myUid())
            }
        }
    }

    override fun cancelPipeline(promise: Promise) {
        promise.resolve(session.cancel())
    }

    override fun invalidate() {
        closed.set(true)
        session.close()
        worker.shutdownNow()
        super.invalidate()
    }

    private fun begin(promise: Promise): Boolean {
        if (closed.get()) {
            promise.reject("E_WORKSPACE_CLOSED", "工作目录模块已关闭。")
            return false
        }
        if (!busy.compareAndSet(false, true)) {
            promise.reject("E_WORKSPACE_BUSY", "请等待当前工作目录操作结束。")
            return false
        }
        return true
    }

    private fun submit(promise: Promise, action: () -> Any) {
        try {
            worker.execute {
                try {
                    check(!closed.get()) { "工作目录模块已关闭。" }
                    promise.resolve(action())
                } catch (error: Exception) {
                    promise.reject("E_WORKSPACE_OPERATION", error.message, error)
                } finally {
                    busy.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            session.cancel()
            busy.set(false)
            promise.reject("E_WORKSPACE_CLOSED", "工作目录模块已关闭。", error)
        }
    }

    companion object {
        const val NAME = "PocketRunWorkspace"
    }
}
