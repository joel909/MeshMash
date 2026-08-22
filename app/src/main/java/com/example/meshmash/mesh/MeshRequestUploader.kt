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

/** Coordinates durable storage and the one-request-per-POST server contract. */
class MeshRequestUploader(
    private val store: MeshRequestStore,
    private val apiClient: MeshRequestApiClient,
) {
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
            try {
                val result = apiClient.upload(request)
                if (result.duplicate) duplicates += 1
                if (store.updateStatus(request.requestId, RequestStatus.DELIVERED)) {
                    uploaded += 1
                } else {
                    failures += MeshUploadFailure(
                        request.requestId,
                        "Server accepted request, but local status update failed",
                    )
                }
            } catch (error: Exception) {
                failures += MeshUploadFailure(
                    request.requestId,
                    error.message ?: "Unknown upload error",
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
