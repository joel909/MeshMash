package com.example.meshmash

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlin.random.Random

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
    private var pendingSyncCount = 3

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupCategoryInteractivity()
        setupPriorityToggleInteractivity()
        setupRecalibrateInteractivity()
        setupSaveButtonInteractivity()
        setupStatusBannerInteractivity()
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
            ReportCategory.OTHER to cardOther
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
        btnRecalibrate.setOnClickListener {
            currentLat += (Random.nextDouble(-0.005, 0.005))
            currentLon += (Random.nextDouble(-0.005, 0.005))

            val accuracy = Random.nextInt(2, 6)
            val altitude = Random.nextInt(75, 95)

            val latStr = String.format("%.4f° N", currentLat)
            val lonStr = String.format("%.4f° W", kotlin.math.abs(currentLon))
            tvCoordinates.text = "$latStr, $lonStr"
            tvAccuracyAlt.text = "Accuracy: ±${accuracy}m  •  Altitude: ${altitude}m"

            Toast.makeText(this, "GPS Recalibrated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSaveButtonInteractivity() {
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        btnSave.setOnClickListener {
            val reportCategoryName = selectedCategory?.name ?: "UNKNOWN"
            val reportPriorityName = selectedPriority?.name ?: "NORMAL"

            val nextIdNum = Random.nextInt(100, 999)
            val reportId = "FR-$nextIdNum"

            pendingSyncCount++
            tvPendingCount.text = "$pendingSyncCount Pending"
            tvReportId.text = "Will queue locally as ID: $reportId"

            val summary = "Saved offline: [$reportId]\nCategory: $reportCategoryName\nPriority: $reportPriorityName"
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()

            etIncidentNotes.text?.clear()
        }
    }

    private fun setupStatusBannerInteractivity() {
        val bannerOffline = findViewById<MaterialCardView>(R.id.bannerOffline)
        bannerOffline.setOnClickListener {
            Toast.makeText(this, "$pendingSyncCount reports waiting for BLE Mesh connection", Toast.LENGTH_SHORT).show()
        }
    }
}