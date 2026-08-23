package com.example.meshmash.mesh

import org.json.JSONObject

/** Builds the one-request-per-POST JSON body used by the future server uploader. */
object MeshRequestPostBody {
    fun toJson(request: MeshRequest): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("requestId", request.requestId.toString())
        put("originDeviceId", request.originDeviceId.toString())
        put("category", request.category)
        put("priority", request.priority.wireValue)
        put("createdAtMillis", request.createdAtMillis)
        put("requester", (request.requester ?: UNKNOWN_REQUESTER).toJson())
        put("location", request.location?.toJson() ?: unknownLocation(request.createdAtMillis))
        put("payloadEncoding", PAYLOAD_ENCODING)
        put("payload", request.payload.toString(Charsets.UTF_8))
        put(
            "relayMetadata",
            JSONObject().apply {
                put("receivedAtMillis", request.receivedAtMillis)
                put(
                    "lastForwardedAtMillis",
                    request.lastForwardedAtMillis ?: JSONObject.NULL,
                )
                put("forwardCount", request.forwardCount)
                put("status", request.status.name)
            },
        )
    }

    fun toJsonString(request: MeshRequest): String = toJson(request).toString()

    private fun RequesterIdentity.toJson() = JSONObject().apply {
        put("fullName", fullName)
        put("phoneNumber", phoneNumber)
        put("personalIdType", personalIdType ?: JSONObject.NULL)
        put("personalIdValue", personalIdValue ?: JSONObject.NULL)
    }

    private fun MeshLocation.toJson() = JSONObject().apply {
        put("latitudeE7", latitudeE7)
        put("longitudeE7", longitudeE7)
        put("accuracyMeters", accuracyMeters.toDouble())
        put("capturedAtMillis", capturedAtMillis)
    }

    private fun unknownLocation(createdAtMillis: Long) = JSONObject().apply {
        put("latitudeE7", 0)
        put("longitudeE7", 0)
        put("accuracyMeters", 0.0)
        put("capturedAtMillis", createdAtMillis)
    }

    const val SCHEMA_VERSION = 1
    const val PAYLOAD_ENCODING = "utf8"
    private val UNKNOWN_REQUESTER = RequesterIdentity(
        fullName = "Not provided",
        phoneNumber = "Not provided",
    )
}
