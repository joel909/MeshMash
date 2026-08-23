package com.example.meshmash.mesh

import java.util.UUID

data class MeshUploadFailure(
    val requestId: UUID,
    val reason: String,
)

data class MeshUploadSummary(
    val attempted: Int,
    val uploaded: Int,
    val duplicates: Int,
    val failures: List<MeshUploadFailure>,
)

data class StoredMeshUploadResult(
    val requestId: UUID,
    val uploaded: Boolean,
    val duplicate: Boolean = false,
    val failureReason: String? = null,
)

/** Coordinates durable storage and the one-request-per-POST server contract. */
class MeshRequestUploader(
    private val store: MeshRequestStore,
    private val apiClient: MeshRequestApiClient,
) {
    /** Uploads one request and deletes it locally only after server acceptance. */
    fun uploadRequest(requestId: UUID): StoredMeshUploadResult {
        val request = store.get(requestId) ?: return StoredMeshUploadResult(
            requestId = requestId,
            uploaded = false,
            failureReason = "Request is not present in local storage",
        )
        MeshUploadStatusTracker.uploadStarted(requestId.toString())
        return try {
            val result = apiClient.upload(request)
            if (store.delete(requestId)) {
                StoredMeshUploadResult(requestId, uploaded = true, duplicate = result.duplicate)
            } else {
                StoredMeshUploadResult(
                    requestId,
                    uploaded = false,
                    duplicate = result.duplicate,
                    failureReason = "Server accepted request, but local deletion failed",
                )
            }
        } catch (error: Exception) {
            StoredMeshUploadResult(
                requestId,
                uploaded = false,
                failureReason = error.message ?: "Unknown upload error",
            )
        } finally {
            MeshUploadStatusTracker.uploadFinished(requestId.toString())
        }
    }

    /**
     * Uploads active requests sequentially to reduce network load. Accepted duplicates count as
     * delivered because the server already has the same requestId. Run this method off main.
     */
    fun uploadActiveRequests(limit: Int = DEFAULT_UPLOAD_LIMIT): MeshUploadSummary {
        require(limit in 1..MAX_UPLOAD_LIMIT)
        val requests = store.getByStatus(RequestStatus.ACTIVE, limit)
        var uploaded = 0
        var duplicates = 0
        val failures = mutableListOf<MeshUploadFailure>()

        for (request in requests) {
            val result = uploadRequest(request.requestId)
            if (result.duplicate) duplicates += 1
            if (result.uploaded) {
                uploaded += 1
            } else {
                failures += MeshUploadFailure(
                    request.requestId,
                    result.failureReason ?: "Unknown upload error",
                )
            }
        }

        return MeshUploadSummary(
            attempted = requests.size,
            uploaded = uploaded,
            duplicates = duplicates,
            failures = failures,
        )
    }

    companion object {
        private const val DEFAULT_UPLOAD_LIMIT = 100
        private const val MAX_UPLOAD_LIMIT = 1_000
    }
}
