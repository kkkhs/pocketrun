package io.github.kkkhs.pocketrun

import java.io.File
import java.io.IOException

/** Two bounded cache slots: DisplayPanel replacement can never delete TaskHost's published frame. */
internal class FrameCacheSlots {
    private var displayFile: File? = null
    private var taskHostFile: File? = null

    @Synchronized
    fun replaceDisplay(file: File) {
        deleteOwned(displayFile)
        displayFile = file
    }

    @Synchronized
    fun replaceTaskHost(file: File) {
        deleteOwned(taskHostFile)
        taskHostFile = file
    }

    @Synchronized
    fun clear() {
        val files = listOfNotNull(displayFile, taskHostFile)
        displayFile = null
        taskHostFile = null
        var failure: IOException? = null
        for (file in files) {
            try { deleteOwned(file) }
            catch (error: IOException) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun deleteOwned(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            throw IOException("Unable to delete owned screenshot cache ${file.name}.")
        }
    }
}
