package com.example.meshmash.mesh

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

data class MeshUploadResult(
    val requestId: String,
    val accepted: Boolean,
    val duplicate: Boolean,
)

class MeshApiException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Client for the versioned SOS request endpoint documented by the MeshMash backend.
 *
 * Calls are synchronous and must run off the main thread. The API key is requested only when a
 * call starts, so callers can obtain a short-lived value from a trusted backend. Never put the
 * production API key in source code, resources, BuildConfig, or the APK.
 */
class MeshRequestApiClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val apiKeyProvider: () -> String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    private val baseUrl = baseUrl.trimEnd('/').also { value ->
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Mesh API base URL must be a valid HTTPS URL"
        }
    }

    /** Public liveness check. It deliberately does not send the API key. */
    fun isHealthy(): Boolean {
        val connection = openConnection("$baseUrl/health", "GET")
        return try {
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    /** Uploads exactly one request with the backend's required idempotency header. */
    @Throws(MeshApiException::class)
    fun upload(request: MeshRequest): MeshUploadResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw MeshApiException(null, "Mesh API key is unavailable")
        }

        val requestId = request.requestId.toString()
        val body = MeshRequestPostBody.toJsonString(request)
            .toByteArray(StandardCharsets.UTF_8)
        val connection = openConnection("$baseUrl$MESH_REQUEST_PATH", "POST").apply {
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("Idempotency-Key", requestId)
            setFixedLengthStreamingMode(body.size)
        }

        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseBody = connection.responseBody(status)
            if (status !in 200..299) {
                val serverMessage = runCatching {
                    JSONObject(responseBody).optString("message")
                }.getOrNull().orEmpty()
                val detail = serverMessage.ifBlank { "HTTP $status" }
                throw MeshApiException(status, "Mesh request upload failed: $detail")
            }
            parseUploadResponse(responseBody, requestId)
        } catch (error: MeshApiException) {
            throw error
        } catch (error: Exception) {
            throw MeshApiException(null, "Mesh request upload failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            useCaches = false
        }

    private fun HttpURLConnection.responseBody(status: Int): String {
        val stream = if (status in 200..299) inputStream else errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://meshmash-pushpulllegs.onrender.com"
        const val MESH_REQUEST_PATH = "/api/v1/mesh/requests"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 20_000

        internal fun parseUploadResponse(body: String, expectedRequestId: String): MeshUploadResult {
            val json = try {
                JSONObject(body)
            } catch (error: Exception) {
                throw MeshApiException(null, "Mesh API returned invalid JSON", error)
            }
            val responseRequestId = json.optString("requestId")
            if (responseRequestId != expectedRequestId) {
                throw MeshApiException(null, "Mesh API returned a mismatched requestId")
            }
            val accepted = json.optBoolean("accepted", false)
            if (!accepted) {
                throw MeshApiException(null, "Mesh API did not accept the request")
            }
            return MeshUploadResult(
                requestId = responseRequestId,
                accepted = true,
                duplicate = json.optBoolean("duplicate", false),
            )
        }
    }
}
