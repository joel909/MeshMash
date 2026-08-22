package com.example.meshmash.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal

/**
 * One-shot foreground location access. The caller must request [REQUIRED_PERMISSIONS] from an
 * Activity in response to a user action before invoking either location function.
 */
class MeshLocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    fun hasPreciseLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean = locationManager.isLocationEnabled

    /**
     * Gets the system's best current location, normally combining GPS, Wi-Fi, and cell signals.
     * Returns a signal that the caller can cancel when its screen or operation ends.
     */
    fun getCurrentLocation(
        onResult: (Result<MeshLocation>) -> Unit,
    ): CancellationSignal = requestLocation(LocationManager.FUSED_PROVIDER, onResult)

    /** Requests a GPS-only fix. This can be slow or unavailable indoors. */
    fun getCurrentGpsLocation(
        onResult: (Result<MeshLocation>) -> Unit,
    ): CancellationSignal = requestLocation(LocationManager.GPS_PROVIDER, onResult)

    @SuppressLint("MissingPermission") // Checked immediately before LocationManager is called.
    private fun requestLocation(
        provider: String,
        onResult: (Result<MeshLocation>) -> Unit,
    ): CancellationSignal {
        val cancellationSignal = CancellationSignal()
        if (!hasPreciseLocationPermission()) {
            onResult(Result.failure(LocationPermissionException()))
            return cancellationSignal
        }
        if (!locationManager.isLocationEnabled) {
            onResult(Result.failure(LocationDisabledException()))
            return cancellationSignal
        }
        if (!locationManager.hasProvider(provider) || !locationManager.isProviderEnabled(provider)) {
            onResult(Result.failure(LocationProviderUnavailableException(provider)))
            return cancellationSignal
        }

        try {
            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                appContext.mainExecutor,
            ) { location ->
                if (location == null) {
                    onResult(Result.failure(LocationUnavailableException()))
                } else {
                    onResult(Result.success(location.toMeshLocation()))
                }
            }
        } catch (error: SecurityException) {
            onResult(Result.failure(LocationPermissionException(error)))
        }
        return cancellationSignal
    }

    private fun Location.toMeshLocation() = MeshLocation.fromDegrees(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.coerceAtLeast(0f),
        capturedAtMillis = time,
    )

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}

class LocationPermissionException(cause: Throwable? = null) :
    IllegalStateException("Precise location permission has not been granted", cause)

class LocationDisabledException :
    IllegalStateException("Device location services are turned off")

class LocationProviderUnavailableException(provider: String) :
    IllegalStateException("Location provider is unavailable: $provider")

class LocationUnavailableException :
    IllegalStateException("A current location fix was not available")
