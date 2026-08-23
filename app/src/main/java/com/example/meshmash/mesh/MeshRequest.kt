package com.example.meshmash.mesh

import java.nio.ByteBuffer
import java.util.UUID

enum class RequestPriority(val storageValue: Int, val wireValue: String) {
    LOW(0, "NORMAL"),
    MEDIUM(1, "MEDIUM"),
    HIGH(2, "HIGH"),
    CRITICAL(3, "CRITICAL"),

    // Legacy names retained so requests already stored by earlier builds still decode.
    NORMAL(0, "NORMAL"),
    IMPORTANT(2, "HIGH"),
    EMERGENCY(3, "CRITICAL"),
}

enum class RequestStatus {
    ACTIVE,
    DELIVERED,
    RESOLVED,
}

data class RequesterIdentity(
    val fullName: String,
    val phoneNumber: String,
    val personalIdType: String? = null,
    val personalIdValue: String? = null,
) {
    init {
        require(fullName.isNotBlank() && fullName.length <= MAX_NAME_LENGTH) {
            "Full name must contain 1 to $MAX_NAME_LENGTH characters"
        }
        require(phoneNumber.isNotBlank() && phoneNumber.length <= MAX_PHONE_LENGTH) {
            "Phone number must contain 1 to $MAX_PHONE_LENGTH characters"
        }
        require((personalIdType == null) == (personalIdValue == null)) {
            "Personal ID type and value must either both be present or both be absent"
        }
        require(personalIdType == null || personalIdType.isNotBlank()) {
            "Personal ID type cannot be blank"
        }
        require(personalIdValue == null || personalIdValue.isNotBlank()) {
            "Personal ID value cannot be blank"
        }
        require((personalIdType?.length ?: 0) <= MAX_ID_TYPE_LENGTH)
        require((personalIdValue?.length ?: 0) <= MAX_ID_VALUE_LENGTH)
    }

    fun normalized() = copy(
        fullName = fullName.trim(),
        phoneNumber = phoneNumber.trim(),
        personalIdType = personalIdType?.trim(),
        personalIdValue = personalIdValue?.trim(),
    )

    companion object {
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_PHONE_LENGTH = 32
        private const val MAX_ID_TYPE_LENGTH = 40
        private const val MAX_ID_VALUE_LENGTH = 120
    }
}

/** Compact request location; E7 coordinates are convenient for a later binary packet format. */
data class MeshLocation(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyMeters: Float,
    val capturedAtMillis: Long,
) {
    init {
        require(latitudeE7 in -900_000_000..900_000_000) { "Invalid latitude" }
        require(longitudeE7 in -1_800_000_000..1_800_000_000) { "Invalid longitude" }
        require(accuracyMeters >= 0f) { "Accuracy cannot be negative" }
    }

    val latitude: Double
        get() = latitudeE7 / COORDINATE_SCALE
    val longitude: Double
        get() = longitudeE7 / COORDINATE_SCALE

    companion object {
        private const val COORDINATE_SCALE = 10_000_000.0

        fun fromDegrees(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float,
            capturedAtMillis: Long,
        ) = MeshLocation(
            latitudeE7 = (latitude * COORDINATE_SCALE).toInt(),
            longitudeE7 = (longitude * COORDINATE_SCALE).toInt(),
            accuracyMeters = accuracyMeters,
            capturedAtMillis = capturedAtMillis,
        )
    }
}

/**
 * The durable application-level request. The payload can later contain JSON, protobuf, or another
 * agreed wire format; the mesh metadata remains independent of the UI.
 */
data class MeshRequest(
    val requestId: UUID,
    val originDeviceId: UUID,
    val category: String,
    val payload: ByteArray,
    val requester: RequesterIdentity?,
    val location: MeshLocation?,
    val priority: RequestPriority,
    val createdAtMillis: Long,
    val receivedAtMillis: Long,
    val lastForwardedAtMillis: Long? = null,
    val forwardCount: Int = 0,
    val status: RequestStatus = RequestStatus.ACTIVE,
)

object MeshIdentifiers {
    /** Generates an offline-safe random UUIDv4. */
    fun generateUuid(): UUID = UUID.randomUUID()

    /** Packs a UUID into its 16-byte binary representation for BLE transmission. */
    fun toBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(UUID_BYTES)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    fun fromBytes(bytes: ByteArray): UUID {
        require(bytes.size == UUID_BYTES) { "A UUID must contain exactly $UUID_BYTES bytes" }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    const val UUID_BYTES = 16
}

/**
 * New requests spread aggressively; old requests remain stored but consume less radio time.
 * Delivered and resolved requests are retained for duplicate detection and are never forwarded.
 */
object MeshForwardingPolicy {
    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE

    fun intervalMillis(request: MeshRequest, nowMillis: Long): Long {
        val age = (nowMillis - request.createdAtMillis).coerceAtLeast(0)
        val baseInterval = when {
            age < 5 * MINUTE -> 10 * SECOND
            age < 15 * MINUTE -> 30 * SECOND
            age < HOUR -> 2 * MINUTE
            age < 6 * HOUR -> 10 * MINUTE
            else -> 30 * MINUTE
        }
        return when (request.priority) {
            RequestPriority.CRITICAL,
            RequestPriority.EMERGENCY,
            -> (baseInterval / 2).coerceAtLeast(10 * SECOND)
            RequestPriority.HIGH,
            RequestPriority.IMPORTANT,
            -> (baseInterval * 3 / 4).coerceAtLeast(10 * SECOND)
            RequestPriority.MEDIUM -> (baseInterval * 7 / 8).coerceAtLeast(10 * SECOND)
            RequestPriority.LOW,
            RequestPriority.NORMAL,
            -> baseInterval
        }
    }

    fun shouldForward(request: MeshRequest, nowMillis: Long): Boolean {
        if (request.status != RequestStatus.ACTIVE) return false
        val lastForwarded = request.lastForwardedAtMillis ?: return true
        return nowMillis - lastForwarded >= intervalMillis(request, nowMillis)
    }
}
