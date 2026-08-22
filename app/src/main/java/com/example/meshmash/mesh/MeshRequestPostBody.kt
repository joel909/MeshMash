package com.example.meshmash.mesh

import org.json.JSONObject
import java.util.Base64

/** Builds the one-request-per-POST JSON body used by the future server uploader. */
object MeshRequestPostBody {
    fun toJson(request: MeshRequest): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("requestId", request.requestId.toString())
        put("originDeviceId", request.originDeviceId.toString())
        put("category", request.category)
        put("priority", request.priority.name)
        put("createdAtMillis", request.createdAtMillis)
        put("requester", request.requester?.toJson() ?: JSONObject.NULL)
        put("location", request.location?.toJson() ?: JSONObject.NULL)
        put("payloadEncoding", "base64")
        put("payload", Base64.getEncoder().encodeToString(request.payload))
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

    const val SCHEMA_VERSION = 1
}
