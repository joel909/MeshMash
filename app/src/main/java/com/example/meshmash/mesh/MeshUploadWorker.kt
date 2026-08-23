package com.example.meshmash.mesh

import android.content.Context
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.meshmash.BuildConfig
import java.util.concurrent.TimeUnit

/** Uploads all queued requests when Android reports an internet connection. */
class MeshUploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        if (BuildConfig.MESH_API_KEY.isBlank()) return Result.failure()

        val store = MeshRequestStore(applicationContext)
        return try {
            // Older builds retained server-confirmed records instead of deleting them.
            store.deleteByStatus(RequestStatus.DELIVERED)
            val uploader = MeshRequestUploader(
                store = store,
                apiClient = MeshRequestApiClient(apiKeyProvider = { BuildConfig.MESH_API_KEY }),
            )
            while (!isStopped) {
                val summary = uploader.uploadActiveRequests(limit = UPLOAD_BATCH_SIZE)
                if (summary.uploaded > 0) showUploadSuccess(summary.uploaded)
                if (summary.failures.isNotEmpty()) return Result.retry()
                if (summary.attempted < UPLOAD_BATCH_SIZE) return Result.success()
            }
            Result.retry()
        } finally {
            store.close()
        }
    }

    private fun showUploadSuccess(uploadedCount: Int) {
        val label = if (uploadedCount == 1) "report" else "reports"
        applicationContext.mainExecutor.execute {
            Toast.makeText(
                applicationContext,
                "$uploadedCount $label uploaded successfully",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val UPLOAD_BATCH_SIZE = 100
    }
}

object MeshUploadScheduler {
    private const val UNIQUE_UPLOAD_WORK = "mesh-request-api-upload"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MeshUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_UPLOAD_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
