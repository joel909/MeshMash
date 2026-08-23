package com.example.meshmash.mesh

enum class MeshReportCategory(val wireValue: String) {
    MEDICAL("medical"),
    WATER("water"),
    FOOD("food"),
    SHELTER("shelter"),
    MISSING_PEOPLE("missing_people"),
    OTHER("other"),
}

data class MeshReportInput(
    val details: String,
    val priority: RequestPriority,
    val requester: RequesterIdentity,
    val location: MeshLocation,
)

data class MeshReportSubmission(
    val request: MeshRequest,
    val serverResult: StoredMeshUploadResult,
) {
    /** False means the request is still safely stored and can be retried later. */
    val deliveredToServer: Boolean
        get() = serverResult.uploaded
}

/**
 * Reusable actions matching the six category cards on the main screen.
 * Every public submission creates one request and performs at most one individual HTTP POST.
 * Run these synchronous functions off the Android main thread.
 */
class MeshReportActions(
    private val store: MeshRequestStore,
    private val uploader: MeshRequestUploader,
) {
    fun submitMedical(input: MeshReportInput) = submit(MeshReportCategory.MEDICAL, input)

    fun submitWater(input: MeshReportInput) = submit(MeshReportCategory.WATER, input)

    fun submitFood(input: MeshReportInput) = submit(MeshReportCategory.FOOD, input)

    fun submitShelter(input: MeshReportInput) = submit(MeshReportCategory.SHELTER, input)

    fun submitMissingPeople(input: MeshReportInput) =
        submit(MeshReportCategory.MISSING_PEOPLE, input)

    fun submitOther(input: MeshReportInput) = submit(MeshReportCategory.OTHER, input)

    fun submit(
        category: MeshReportCategory,
        input: MeshReportInput,
    ): MeshReportSubmission {
        val details = input.details.trim()
        require(details.isNotEmpty()) { "Incident details cannot be empty" }
        val request = store.createAndStore(
            category = category.wireValue,
            payload = details.toByteArray(Charsets.UTF_8),
            requester = input.requester,
            location = input.location,
            priority = input.priority,
        )
        return MeshReportSubmission(
            request = request,
            serverResult = uploader.uploadRequest(request.requestId),
        )
    }
}
