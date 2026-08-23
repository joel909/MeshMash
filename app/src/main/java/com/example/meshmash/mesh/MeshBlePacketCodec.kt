package com.example.meshmash.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Compact JSON envelope that keeps normal disaster requests within the proven 500-byte BLE path. */
object MeshBlePacketCodec {
    fun encode(request: MeshRequest): String {
        val json = JSONObject().apply {
            put("v", VERSION)
            put("i", encodeUuid(request.requestId))
            put("o", encodeUuid(request.originDeviceId))
            put("c", request.category)
            put("p", request.priority.storageValue)
            put("t", request.createdAtMillis.toString(36))
            put("r", request.receivedAtMillis.toString(36))
            put("f", request.forwardCount)
            put("d", Base64.getUrlEncoder().withoutPadding().encodeToString(request.payload))
            request.location?.let { location ->
                put(
                    "l",
                    JSONArray().apply {
                        put(location.latitudeE7)
                        put(location.longitudeE7)
                        put(location.accuracyMeters.toDouble())
                        put(location.capturedAtMillis.toString(36))
                    },
                )
            }
            request.requester?.let { requester ->
                put(
                    "u",
                    JSONArray().apply {
                        put(requester.fullName)
                        put(requester.phoneNumber)
                        put(requester.personalIdType ?: JSONObject.NULL)
                        put(requester.personalIdValue ?: JSONObject.NULL)
                    },
                )
            }
        }
        return json.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= BlePacketLimits.MAX_BYTES) {
                "Request is too large for the BLE test transport"
            }
        }
    }

    fun decode(value: String): MeshRequest {
        val json = JSONObject(value)
        require(json.getInt("v") == VERSION)
        val location = json.optJSONArray("l")?.let {
            MeshLocation(
                latitudeE7 = it.getInt(0),
                longitudeE7 = it.getInt(1),
                accuracyMeters = it.getDouble(2).toFloat(),
                capturedAtMillis = it.getString(3).toLong(36),
            )
        }
        val requester = json.optJSONArray("u")?.let {
            RequesterIdentity(
                fullName = it.getString(0),
                phoneNumber = it.getString(1),
                personalIdType = if (it.isNull(2)) null else it.getString(2),
                personalIdValue = if (it.isNull(3)) null else it.getString(3),
            )
        }
        return MeshRequest(
            requestId = decodeUuid(json.getString("i")),
            originDeviceId = decodeUuid(json.getString("o")),
            category = json.getString("c"),
            payload = Base64.getUrlDecoder().decode(json.getString("d")),
            requester = requester,
            location = location,
            priority = RequestPriority.entries.firstOrNull {
                it.storageValue == json.getInt("p")
            } ?: RequestPriority.NORMAL,
            createdAtMillis = json.getString("t").toLong(36),
            receivedAtMillis = json.getString("r").toLong(36),
            forwardCount = json.optInt("f", 0),
        )
    }

    private fun encodeUuid(value: java.util.UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MeshIdentifiers.toBytes(value))

    private fun decodeUuid(value: String): java.util.UUID =
        MeshIdentifiers.fromBytes(Base64.getUrlDecoder().decode(value))

    private const val VERSION = 1
}

object BlePacketLimits {
    const val MAX_BYTES = 500
}
