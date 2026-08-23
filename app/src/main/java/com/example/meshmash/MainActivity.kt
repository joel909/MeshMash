package com.example.meshmash

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.example.meshmash.mesh.MeshLocation
import com.example.meshmash.mesh.MeshReportCategory
import com.example.meshmash.mesh.MeshRequestStore
import com.example.meshmash.mesh.MeshStoreForwardManager
import com.example.meshmash.mesh.InternetReachabilityMonitor
import com.example.meshmash.mesh.MeshUploadScheduler
import com.example.meshmash.mesh.MeshUploadStatusTracker
import com.example.meshmash.mesh.RequestPriority
import com.example.meshmash.mesh.RequestStatus
import java.io.Closeable

class MainActivity : AppCompatActivity() {

    enum class ReportCategory {
        MEDICAL, WATER, FOOD, SHELTER, MISSING_PEOPLE, OTHER
    }

    enum class PriorityLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    private var selectedCategory: ReportCategory? = null
    private var selectedPriority: PriorityLevel? = null
    private var currentLat = 34.0522
    private var currentLon = -118.2437
    private var pendingSyncCount = 0
    private var sendAfterNearbyPermissionGranted = false
    private var latestGpsLocation: MeshLocation? = null
    private var gpsCancellationSignal: CancellationSignal? = null
    private var gpsLoadingDialog: AlertDialog? = null
    private lateinit var meshManager: MeshStoreForwardManager
    private lateinit var apiReachabilityMonitor: InternetReachabilityMonitor
    private var uploadStatusObservation: Closeable? = null
    private var apiOnline = false

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasNearbyPermissions()) {
            meshManager.start()
            if (sendAfterNearbyPermissionGranted) {
                sendAfterNearbyPermissionGranted = false
                sendCurrentReport()
            }
        } else {
            sendAfterNearbyPermissionGranted = false
            Toast.makeText(this, "Nearby devices permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasLocationPermission()) {
            fetchCurrentGps()
        } else {
            finishGpsLoading("Location permission is required to send mesh requests")
        }
    }

    private lateinit var nestedScrollView: NestedScrollView
    private lateinit var sectionPriority: LinearLayout
    private lateinit var sectionIncidentDetails: LinearLayout
    private lateinit var priorityToggleGroup: MaterialButtonToggleGroup
    private lateinit var tvCoordinates: TextView
    private lateinit var tvAccuracyAlt: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvReportId: TextView
    private lateinit var etIncidentNotes: TextInputEditText

    private lateinit var btnLow: MaterialButton
    private lateinit var btnMed: MaterialButton
    private lateinit var btnHigh: MaterialButton
    private lateinit var btnCritical: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        apiReachabilityMonitor = InternetReachabilityMonitor(this)
        meshManager = MeshStoreForwardManager(
            context = this,
            onStatus = { message ->
                Log.i(BLE_LOG_TAG, message)
                if (message.startsWith("No ") || message.contains("failed", ignoreCase = true)) {
                    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
                }
            },
            onNewIssue = {
                runOnUiThread {
                    Toast.makeText(this, "Request received", Toast.LENGTH_LONG).show()
                }
            },
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        refreshPendingCount()
        uploadStatusObservation = MeshUploadStatusTracker.observe { progress ->
            if (!progress.isUploading) runOnUiThread(::refreshPendingCount)
        }
        setupCategoryInteractivity()
        setupPriorityToggleInteractivity()
        setupRecalibrateInteractivity()
        setupSaveButtonInteractivity()
        setupStatusBannerInteractivity()
        apiReachabilityMonitor.startMonitoring { online ->
            apiOnline = online
            renderApiConnectivity(online)
            if (online) MeshUploadScheduler.enqueue(this, restartImmediately = true)
        }
        setupShowIssuesButton()
        if (hasNearbyPermissions()) meshManager.start()
        startGpsLoading()
    }

    private fun initViews() {
        nestedScrollView = findViewById(R.id.nestedScrollView)
        sectionPriority = findViewById(R.id.sectionPriority)
        sectionIncidentDetails = findViewById(R.id.sectionIncidentDetails)
        priorityToggleGroup = findViewById(R.id.priorityToggleGroup)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        tvAccuracyAlt = findViewById(R.id.tvAccuracyAlt)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvReportId = findViewById(R.id.tvReportId)
        etIncidentNotes = findViewById(R.id.etIncidentNotes)

        btnLow = findViewById(R.id.btnLow)
        btnMed = findViewById(R.id.btnMedium)
        btnHigh = findViewById(R.id.btnHigh)
        btnCritical = findViewById(R.id.btnCritical)
    }

    private fun setupCategoryInteractivity() {
        val cardMedical = findViewById<MaterialCardView>(R.id.cardMedical)
        val cardWater = findViewById<MaterialCardView>(R.id.cardWater)
        val cardFood = findViewById<MaterialCardView>(R.id.cardFood)
        val cardShelter = findViewById<MaterialCardView>(R.id.cardShelter)
        val cardMissingPeople = findViewById<MaterialCardView>(R.id.cardMissingPeople)
        val cardOther = findViewById<MaterialCardView>(R.id.cardOther)

        val cards = listOf(
            ReportCategory.MEDICAL to cardMedical,
            ReportCategory.WATER to cardWater,
            ReportCategory.FOOD to cardFood,
            ReportCategory.SHELTER to cardShelter,
            ReportCategory.MISSING_PEOPLE to cardMissingPeople,
            ReportCategory.OTHER to cardOther,
        )

        cards.forEach { (category, card) ->
            card.setOnClickListener {
                selectedCategory = category
                renderCategoryCards(cards)

                // 1. Reset downstream priority state & selection
                selectedPriority = null
                priorityToggleGroup.clearChecked()
                resetPriorityButtonStyles()

                // 2. Hide Incident Details section & clear previous notes
                sectionIncidentDetails.visibility = View.GONE
                etIncidentNotes.text?.clear()

                // 3. Reveal Step 2 (Priority Level) and scroll to it
                sectionPriority.visibility = View.VISIBLE
                nestedScrollView.post {
                    nestedScrollView.smoothScrollTo(0, sectionPriority.top)
                }
            }
        }
    }

    private fun renderCategoryCards(cards: List<Pair<ReportCategory, MaterialCardView>>) {
        val activeBg = Color.parseColor("#0C4A6E")
        val activeStroke = Color.parseColor("#38BDF8")
        val inactiveBg = Color.parseColor("#18181B")
        val inactiveStroke = Color.parseColor("#27272A")

        val density = resources.displayMetrics.density
        val activeWidth = (2 * density).toInt()
        val inactiveWidth = (1 * density).toInt()

        for ((category, card) in cards) {
            if (category == selectedCategory) {
                card.setCardBackgroundColor(activeBg)
                card.strokeColor = activeStroke
                card.strokeWidth = activeWidth
            } else {
                card.setCardBackgroundColor(inactiveBg)
                card.strokeColor = inactiveStroke
                card.strokeWidth = inactiveWidth
            }
        }
    }

    private fun resetPriorityButtonStyles() {
        val unselectedBg = ColorStateList.valueOf(Color.parseColor("#18181B"))
        val unselectedStroke = ColorStateList.valueOf(Color.parseColor("#27272A"))
        val unselectedText = Color.parseColor("#A1A1AA")

        val allButtons = listOf(btnLow, btnMed, btnHigh, btnCritical)
        allButtons.forEach { btn ->
            btn.backgroundTintList = unselectedBg
            btn.setTextColor(unselectedText)
            btn.strokeColor = unselectedStroke
        }
    }

    private fun setupPriorityToggleInteractivity() {
        resetPriorityButtonStyles()

        fun updatePriorityButtonStyles(checkedId: Int) {
            resetPriorityButtonStyles()

            when (checkedId) {
                R.id.btnLow -> {
                    selectedPriority = PriorityLevel.LOW
                    val color = ColorStateList.valueOf(Color.parseColor("#15803D"))
                    btnLow.backgroundTintList = color
                    btnLow.setTextColor(Color.WHITE)
                    btnLow.strokeColor = color
                }
                R.id.btnMedium -> {
                    selectedPriority = PriorityLevel.MEDIUM
                    val color = ColorStateList.valueOf(Color.parseColor("#0284C7"))
                    btnMed.backgroundTintList = color
                    btnMed.setTextColor(Color.WHITE)
                    btnMed.strokeColor = color
                }
                R.id.btnHigh -> {
                    selectedPriority = PriorityLevel.HIGH
                    val color = ColorStateList.valueOf(Color.parseColor("#D97706"))
                    btnHigh.backgroundTintList = color
                    btnHigh.setTextColor(Color.WHITE)
                    btnHigh.strokeColor = color
                }
                R.id.btnCritical -> {
                    selectedPriority = PriorityLevel.CRITICAL
                    val color = ColorStateList.valueOf(Color.parseColor("#DC2626"))
                    btnCritical.backgroundTintList = color
                    btnCritical.setTextColor(Color.WHITE)
                    btnCritical.strokeColor = color
                }
            }
        }

        priorityToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updatePriorityButtonStyles(checkedId)

                // Reveal Step 3: Incident Details
                if (sectionIncidentDetails.visibility != View.VISIBLE) {
                    sectionIncidentDetails.visibility = View.VISIBLE
                }

                nestedScrollView.post {
                    nestedScrollView.smoothScrollTo(0, sectionIncidentDetails.top)
                }
            }
        }
    }

    private fun setupRecalibrateInteractivity() {
        val btnRecalibrate = findViewById<MaterialButton>(R.id.btnRecalibrate)
        btnRecalibrate.setOnClickListener { startGpsLoading() }
    }

    private fun setupSaveButtonInteractivity() {
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        btnSave.setOnClickListener { sendCurrentReport() }
    }

    private fun sendCurrentReport() {
        val category = selectedCategory
        val priority = selectedPriority
        val details = etIncidentNotes.text?.toString()?.trim().orEmpty()
        if (category == null || priority == null || details.isEmpty()) {
            Toast.makeText(this, "Select a category, priority, and add details", Toast.LENGTH_LONG).show()
            return
        }
        val gpsLocation = latestGpsLocation
        if (gpsLocation == null) {
            Toast.makeText(this, "GPS is still loading. Please wait.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasNearbyPermissions()) {
            sendAfterNearbyPermissionGranted = true
            nearbyPermissionLauncher.launch(BLE_PERMISSIONS)
            return
        }
        if (!meshManager.isBluetoothEnabled) {
            Toast.makeText(this, "Turn on Bluetooth, then tap again", Toast.LENGTH_LONG).show()
            return
        }

        meshManager.start()
        val request = meshManager.createAndBroadcast(
            category = MeshReportCategory.valueOf(category.name),
            details = details,
            priority = RequestPriority.valueOf(priority.name),
            location = gpsLocation,
        )
        refreshPendingCount()
        tvReportId.text = "Broadcasting for 25 seconds: ${request.requestId}"
        Toast.makeText(this, "Updating across mesh network now", Toast.LENGTH_LONG).show()
        etIncidentNotes.text?.clear()
    }

    private fun hasNearbyPermissions(): Boolean = BLE_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGpsLoading() {
        latestGpsLocation = null
        gpsLoadingDialog?.dismiss()
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(48, 24, 48, 24)
        }
        gpsLoadingDialog = AlertDialog.Builder(this)
            .setTitle("Fetching GPS")
            .setMessage("Getting your current coordinates…")
            .setView(progress)
            .setCancelable(false)
            .show()
        if (hasLocationPermission()) {
            fetchCurrentGps()
        } else {
            locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    private fun fetchCurrentGps() {
        val locationManager = getSystemService(LocationManager::class.java)
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            finishGpsLoading("Turn on GPS and tap Recalibrate")
            return
        }

        gpsCancellationSignal?.cancel()
        val signal = CancellationSignal()
        gpsCancellationSignal = signal
        val handler = Handler(Looper.getMainLooper())
        val fallback = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        val timeout = Runnable {
            if (gpsCancellationSignal === signal) {
                signal.cancel()
                gpsCancellationSignal = null
                if (fallback != null) completeGpsLoading(fallback)
                else finishGpsLoading("Could not fetch GPS. Tap Recalibrate and try again.")
            }
        }
        handler.postDelayed(timeout, GPS_TIMEOUT_MS)
        try {
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                signal,
                mainExecutor,
            ) { location ->
                if (gpsCancellationSignal !== signal) return@getCurrentLocation
                gpsCancellationSignal = null
                handler.removeCallbacks(timeout)
                if (location != null) completeGpsLoading(location)
                else if (fallback != null) completeGpsLoading(fallback)
                else finishGpsLoading("Could not fetch GPS. Tap Recalibrate and try again.")
            }
        } catch (_: SecurityException) {
            handler.removeCallbacks(timeout)
            gpsCancellationSignal = null
            finishGpsLoading("Location permission is required to send mesh requests")
        }
    }

    private fun completeGpsLoading(location: Location) {
        val capturedAt = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        latestGpsLocation = MeshLocation.fromDegrees(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.coerceAtLeast(0f),
            capturedAtMillis = capturedAt,
        )
        currentLat = location.latitude
        currentLon = location.longitude
        val latitudeDirection = if (currentLat >= 0) "N" else "S"
        val longitudeDirection = if (currentLon >= 0) "E" else "W"
        tvCoordinates.text = String.format(
            "%.4f° %s, %.4f° %s",
            kotlin.math.abs(currentLat),
            latitudeDirection,
            kotlin.math.abs(currentLon),
            longitudeDirection,
        )
        tvAccuracyAlt.text = String.format("Accuracy: ±%.0fm  •  GPS ready", location.accuracy)
        gpsLoadingDialog?.dismiss()
        gpsLoadingDialog = null
    }

    private fun finishGpsLoading(message: String) {
        latestGpsLocation = null
        gpsLoadingDialog?.dismiss()
        gpsLoadingDialog = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setupStatusBannerInteractivity() {
        val bannerOffline = findViewById<MaterialCardView>(R.id.bannerOffline)
        bannerOffline.setOnClickListener {
            val message = if (apiOnline) {
                "API is online; pending reports upload automatically"
            } else {
                "$pendingSyncCount reports waiting for the API connection"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderApiConnectivity(online: Boolean) {
        val banner = findViewById<MaterialCardView>(R.id.bannerOffline)
        val icon = findViewById<ImageView>(R.id.ivConnectionStatus)
        val title = findViewById<TextView>(R.id.tvConnectionTitle)
        val description = findViewById<TextView>(R.id.tvConnectionDescription)
        if (online) {
            val green = Color.parseColor("#22C55E")
            banner.setCardBackgroundColor(Color.parseColor("#0D2818"))
            banner.strokeColor = green
            icon.imageTintList = ColorStateList.valueOf(green)
            tvPendingCount.setTextColor(green)
            title.text = "Connected to network"
            description.text = "Uploading data to server"
        } else {
            val orange = Color.parseColor("#FFB74D")
            banner.setCardBackgroundColor(Color.parseColor("#2A1B0A"))
            banner.strokeColor = orange
            icon.imageTintList = ColorStateList.valueOf(orange)
            tvPendingCount.setTextColor(orange)
            title.text = "Offline Mode Active"
            description.text = "Reports stay on this device until the API is healthy."
        }
    }

    private fun refreshPendingCount() {
        pendingSyncCount = MeshRequestStore(this).use { store ->
            store.countByStatus(RequestStatus.ACTIVE)
        }
        tvPendingCount.text = "$pendingSyncCount Pending"
    }

    private fun setupShowIssuesButton() {
        findViewById<MaterialToolbar>(R.id.toolbar).menu.add("Show all issues").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, ReceivedIssuesActivity::class.java))
                true
            }
        }
    }

    override fun onDestroy() {
        gpsCancellationSignal?.cancel()
        gpsLoadingDialog?.dismiss()
        uploadStatusObservation?.close()
        apiReachabilityMonitor.close()
        meshManager.close()
        super.onDestroy()
    }

    companion object {
        private const val BLE_LOG_TAG = "MeshMashBLE"
        private const val GPS_TIMEOUT_MS = 20_000L
        private val BLE_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
