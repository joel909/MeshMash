package com.example.meshmash.mesh

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

data class MeshUploadProgress(
    val activeUploadCount: Int,
    val requestId: String?,
) {
    val isUploading: Boolean
        get() = activeUploadCount > 0
}

/** Process-local upload observer used by the status banner without coupling it to the uploader. */
object MeshUploadStatusTracker {
    private val activeUploads = AtomicInteger(0)
    private val listeners = CopyOnWriteArraySet<(MeshUploadProgress) -> Unit>()

    fun observe(listener: (MeshUploadProgress) -> Unit): Closeable {
        listeners += listener
        listener(MeshUploadProgress(activeUploads.get(), null))
        return Closeable { listeners -= listener }
    }

    internal fun uploadStarted(requestId: String) {
        publish(MeshUploadProgress(activeUploads.incrementAndGet(), requestId))
    }

    internal fun uploadFinished(requestId: String) {
        val remaining = activeUploads.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        publish(MeshUploadProgress(remaining, requestId))
    }

    private fun publish(progress: MeshUploadProgress) {
        listeners.forEach { listener -> listener(progress) }
    }
}
