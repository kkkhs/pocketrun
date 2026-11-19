package io.github.kkkhs.pocketrun

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Same no-replace contract; Android needs rename, not app-data hard links. */
internal object AtomicSamplePublisher {
    private val android = System.getProperty("java.vm.name") == "Dalvik"

    init {
        if (android) System.loadLibrary("pocketrun_fs")
    }

    fun publish(source: File, target: File): Boolean {
        if (android) return renameNoReplace(source.absolutePath, target.absolutePath)
        return try {
            Files.createLink(target.toPath(), source.toPath())
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }
    }

    private external fun renameNoReplace(source: String, target: String): Boolean
}
