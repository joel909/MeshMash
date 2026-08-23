package com.example.meshmash

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var marker: Marker
    private var currentLat = 34.0522
    private var currentLon = -118.2437

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize OSMDroid configuration BEFORE inflating layout
        val osmPath = File(cacheDir, "osmdroid")
        if (!osmPath.exists()) osmPath.mkdirs()
        Configuration.getInstance().osmdroidBasePath = osmPath
        Configuration.getInstance().osmdroidTileCache = File(osmPath, "tiles")
        Configuration.getInstance().userAgentValue = "MeshMashAppClient/1.0"

        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 2. Setup OpenStreetMap
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        mapView.controller.setZoom(16.0)

        val point = GeoPoint(currentLat, currentLon)
        mapView.controller.setCenter(point)

        marker = Marker(mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = "Report Incident Location"
        mapView.overlays.add(marker)

        // 3. Fix scroll conflict inside NestedScrollView
        findViewById<NestedScrollView>(R.id.nestedScrollView)?.let { scroll ->
            mapView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        scroll.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scroll.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }

        // 4. Recalibrate Action
        findViewById<MaterialButton>(R.id.btnRecalibrate).setOnClickListener {
            currentLat += Random.nextDouble(-0.003, 0.003)
            currentLon += Random.nextDouble(-0.003, 0.003)

            val updatedPoint = GeoPoint(currentLat, currentLon)
            marker.position = updatedPoint
            mapView.controller.animateTo(updatedPoint)
            Toast.makeText(this, "Map Recalibrated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}