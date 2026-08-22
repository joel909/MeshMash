package com.example.meshmash

import com.example.meshmash.mesh.MeshForwardingPolicy
import com.example.meshmash.mesh.MeshIdentifiers
import com.example.meshmash.mesh.MeshLocation
import com.example.meshmash.mesh.MeshRequest
import com.example.meshmash.mesh.RequestPriority
import com.example.meshmash.mesh.RequestStatus
import com.example.meshmash.mesh.RequesterIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MeshRequestTest {
    @Test
    fun uuidBinaryRoundTripUses16Bytes() {
        val id = MeshIdentifiers.generateUuid()
        val bytes = MeshIdentifiers.toBytes(id)

        assertEquals(16, bytes.size)
        assertEquals(id, MeshIdentifiers.fromBytes(bytes))
        assertEquals(4, id.version())
    }

    @Test
    fun newEmergencyRequestHasShorterIntervalThanOldRequest() {
        val now = 10_000_000L
        val newRequest = request(createdAt = now - 60_000, priority = RequestPriority.EMERGENCY)
        val oldRequest = request(createdAt = now - 2 * 60 * 60 * 1_000)

        assertTrue(
            MeshForwardingPolicy.intervalMillis(newRequest, now) <
                MeshForwardingPolicy.intervalMillis(oldRequest, now),
        )
    }

    @Test
    fun resolvedRequestsAreNeverForwarded() {
        val request = request(createdAt = 0, status = RequestStatus.RESOLVED)
        assertFalse(MeshForwardingPolicy.shouldForward(request, 1_000_000))
    }

    @Test
    fun locationUsesCompactE7Coordinates() {
        val location = MeshLocation.fromDegrees(
            latitude = 12.9715987,
            longitude = 77.594566,
            accuracyMeters = 8f,
            capturedAtMillis = 123L,
        )

        assertEquals(12.9715987, location.latitude, 0.0000001)
        assertEquals(77.594566, location.longitude, 0.0000001)
    }

    @Test
    fun requesterIdentityCanContainPhoneAndPersonalId() {
        val requester = RequesterIdentity(
            fullName = "Asha Kumar",
            phoneNumber = "+919876543210",
            personalIdType = "hospital_id",
            personalIdValue = "H-12345",
        )

        assertEquals("+919876543210", requester.phoneNumber)
        assertEquals("H-12345", requester.personalIdValue)
    }

    private fun request(
        createdAt: Long,
        priority: RequestPriority = RequestPriority.NORMAL,
        status: RequestStatus = RequestStatus.ACTIVE,
    ) = MeshRequest(
        requestId = UUID.randomUUID(),
        originDeviceId = UUID.randomUUID(),
        category = "medical",
        payload = "help".toByteArray(),
        requester = null,
        location = null,
        priority = priority,
        createdAtMillis = createdAt,
        receivedAtMillis = createdAt,
        status = status,
    )
}
