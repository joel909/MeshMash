package com.example.meshmash.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks whether a specific HTTPS endpoint is actually reachable and watches for changes.
 *
 * Network capability flags alone are insufficient: a Wi-Fi network may exist without being able
 * to reach the target domain. Callbacks are always delivered on Android's main thread for UI use.
 */
class InternetReachabilityMonitor(
    context: Context,
    endpoint: URI = DEFAULT_ENDPOINT,
) : Closeable {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val endpointUrl = endpoint.also {
        require(it.scheme == "https" && !it.host.isNullOrBlank()) {
            "Reachability endpoint must be a valid HTTPS URI"
        }
    }.toURL()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mesh-internet-monitor").apply { isDaemon = true }
    }
    private val checkInProgress = AtomicBoolean(false)

    @Volatile
    private var listener: ((Boolean) -> Unit)? = null
    private var scheduledCheck: ScheduledFuture<*>? = null
    private var callbackRegistered = false
    @Volatile
    private var lastResult: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestImmediateCheck()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                requestImmediateCheck()
            } else {
                publish(false)
            }
        }

        override fun onLost(network: Network) {
            requestImmediateCheck()
        }
    }

    /**
     * Starts continuous monitoring. The listener receives the initial result and later changes.
     * Calling this again replaces the listener without registering another network callback.
     */
    @Synchronized
    fun startMonitoring(
        intervalMillis: Long = DEFAULT_CHECK_INTERVAL_MS,
        onStatusChanged: (Boolean) -> Unit,
    ) {
        require(intervalMillis >= MINIMUM_CHECK_INTERVAL_MS) {
            "Check interval must be at least $MINIMUM_CHECK_INTERVAL_MS ms"
        }
        listener = onStatusChanged
        lastResult = null
        if (!callbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        }
        scheduledCheck?.cancel(false)
        scheduledCheck = worker.scheduleWithFixedDelay(
            { checkAndPublish() },
            0,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Performs one asynchronous check and returns its result on the main thread. */
    fun checkNow(onResult: (Boolean) -> Unit) {
        worker.execute {
            val reachable = isReachableBlocking()
            appContext.mainExecutor.execute { onResult(reachable) }
        }
    }

    /**
     * Returns true only when [endpointUrl] produces an HTTP response.
     * This performs network I/O and must never be called from the main thread.
     */
    fun isReachableBlocking(): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isReachableBlocking cannot run on the main thread; use checkNow instead"
        }
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false

        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(endpointUrl) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    fun stopMonitoring() {
        scheduledCheck?.cancel(false)
        scheduledCheck = null
        if (callbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            callbackRegistered = false
        }
        listener = null
        lastResult = null
    }

    override fun close() {
        stopMonitoring()
        worker.shutdownNow()
    }

    private fun requestImmediateCheck() {
        runCatching { worker.execute { checkAndPublish() } }
    }

    private fun checkAndPublish() {
        if (!checkInProgress.compareAndSet(false, true)) return
        try {
            publish(isReachableBlocking())
        } finally {
            checkInProgress.set(false)
        }
    }

    private fun publish(reachable: Boolean) {
        if (lastResult == reachable) return
        lastResult = reachable
        val currentListener = listener ?: return
        appContext.mainExecutor.execute { currentListener(reachable) }
    }

    companion object {
        val DEFAULT_ENDPOINT: URI = URI.create(
            MeshRequestApiClient.DEFAULT_BASE_URL + MeshRequestApiClient.HEALTH_PATH,
        )
        const val DEFAULT_CHECK_INTERVAL_MS = 30_000L
        private const val MINIMUM_CHECK_INTERVAL_MS = 5_000L
        private const val CONNECTION_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
    }
}
