package com.example.meshmash

import com.example.meshmash.mesh.MeshRequestApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshRequestApiClientTest {
    @Test
    fun usesDocumentedVersionedEndpoint() {
        assertEquals(
            "/api/v1/mesh/requests",
            MeshRequestApiClient.MESH_REQUEST_PATH,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCleartextBaseUrl() {
        MeshRequestApiClient(
            baseUrl = "http://example.com",
            apiKeyProvider = { "test-key" },
        )
    }
}
