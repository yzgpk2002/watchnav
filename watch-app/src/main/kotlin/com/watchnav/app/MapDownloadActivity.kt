package com.watchnav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.watchnav.packages.BbbikeExtractInstaller
import com.watchnav.packages.ContourMapCatalog
import com.watchnav.packages.DirectMapPackageInstaller
import com.watchnav.packages.ExtractProgress
import com.watchnav.packages.ExtractStage
import com.watchnav.packages.MapDownloadRegion
import com.watchnav.session.CourseFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapDownloadActivity : ComponentActivity() {
    private lateinit var mapView: MapRegionSelectionView
    private lateinit var regionText: TextView
    private lateinit var providerText: TextView
    private lateinit var statusText: TextView
    private lateinit var downloadButton: Button
    private var selectedLayer = MapLayerKind.NORMAL
    private var locating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) locateCurrentPosition() else useCourseFallback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        mapView.onRegionChanged = { region -> renderRegion(region) }
        renderRegion(mapView.region)
        requestLocation()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::mapView.isInitialized && mapView.handleRotaryScroll(event)) true
        else super.dispatchGenericMotionEvent(event)

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(22))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            setText(R.string.map_download_title)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(85, 226, 141))
        }, matchWrap())

        mapView = MapRegionSelectionView(this).apply { minimumHeight = dp(250) }
        root.addView(
            mapView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(270)),
        )

        root.addView(TextView(this).apply {
            setText(R.string.map_rotary_hint)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(4, dp(5), 4, dp(4))
        }, matchWrap())

        val layerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        layerRow.addView(layerButton(R.string.map_layer_normal, MapLayerKind.NORMAL), weightedButton())
        layerRow.addView(layerButton(R.string.map_layer_contour, MapLayerKind.CONTOUR), weightedButton())
        layerRow.addView(layerButton(R.string.map_layer_satellite, MapLayerKind.SATELLITE), weightedButton())
        root.addView(layerRow, matchWrap())

        regionText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(4, dp(5), 4, dp(2))
        }
        root.addView(regionText, matchWrap())
        providerText = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(4, dp(2), 4, dp(4))
        }
        root.addView(providerText, matchWrap())

        val areaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        areaRow.addView(Button(this).apply {
            setText(R.string.map_region_smaller)
            setOnClickListener { mapView.resizeSelection(0.7f) }
        }, weightedButton())
        areaRow.addView(Button(this).apply {
            setText(R.string.map_region_larger)
            setOnClickListener { mapView.resizeSelection(1.4f) }
        }, weightedButton())
        root.addView(areaRow, matchWrap())

        root.addView(Button(this).apply {
            setText(R.string.map_recenter)
            setOnClickListener { requestLocation() }
        }, wrapWrap())

        downloadButton = Button(this).apply {
            setText(R.string.download_selected_map)
            setOnClickListener { downloadSelectedLayer() }
        }
        root.addView(downloadButton, wrapWrap())
        statusText = TextView(this).apply {
            setText(R.string.map_locating)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(4, dp(6), 4, dp(6))
        }
        root.addView(statusText, matchWrap())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root, matchWrap())
        }
    }

    private fun layerButton(label: Int, layer: MapLayerKind) = Button(this).apply {
        setText(label)
        textSize = 11f
        setOnClickListener {
            selectedLayer = layer
            mapView.setLayer(layer)
            renderProviderDetails()
        }
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            locateCurrentPosition()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun locateCurrentPosition() {
        if (locating) return
        locating = true
        statusText.setText(R.string.map_locating)
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = runCatching {
            manager.getProviders(true).mapNotNull(manager::getLastKnownLocation).maxByOrNull(Location::getTime)
        }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time < LAST_LOCATION_MAX_AGE_MILLIS) {
            applyLocation(last)
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            locating = false
            if (last == null) useCourseFallback()
            return
        }
        runCatching {
            manager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location ->
                locating = false
                if (location != null) applyLocation(location) else if (last == null) useCourseFallback()
            }
        }.onFailure {
            locating = false
            if (last == null) useCourseFallback()
        }
    }

    private fun applyLocation(location: Location) {
        mapView.centerOn(location.latitude, location.longitude)
        statusText.text = getString(
            R.string.map_region_summary,
            location.latitude,
            location.longitude,
            mapView.region.radiusKilometers,
            mapView.region.approximateAreaSquareKilometers,
        )
    }

    private fun useCourseFallback() {
        lifecycleScope.launch {
            val point = withContext(Dispatchers.IO) {
                runCatching { CourseFileStore(this@MapDownloadActivity).load()?.course?.points?.firstOrNull()?.position }
                    .getOrNull()
            }
            if (point != null) mapView.centerOn(point.latitude, point.longitude)
            statusText.setText(R.string.map_location_unavailable)
        }
    }

    private fun renderRegion(region: MapDownloadRegion) {
        regionText.text = getString(
            R.string.map_region_summary,
            region.centerLatitude,
            region.centerLongitude,
            region.radiusKilometers,
            region.approximateAreaSquareKilometers,
        )
        renderProviderDetails()
    }

    private fun renderProviderDetails() {
        if (!::providerText.isInitialized || !::mapView.isInitialized) return
        providerText.text = when (selectedLayer) {
            MapLayerKind.NORMAL -> "BBBike · Mapsforge · \u00a9 OpenStreetMap contributors"
            MapLayerKind.CONTOUR -> {
                val item = ContourMapCatalog.covering(
                    mapView.region.centerLatitude,
                    mapView.region.centerLongitude,
                )
                if (item == null) {
                    getString(R.string.contour_package_missing)
                } else {
                    getString(
                        R.string.contour_package_summary,
                        item.displayName,
                        item.approximateBytes / MEGABYTE,
                    )
                }
            }
            MapLayerKind.SATELLITE -> getString(R.string.satellite_offline_requires_key)
        }
    }

    private fun downloadSelectedLayer() {
        when (selectedLayer) {
            MapLayerKind.NORMAL -> downloadNormalMap()
            MapLayerKind.CONTOUR -> downloadContourMap()
            MapLayerKind.SATELLITE -> statusText.setText(R.string.satellite_offline_requires_key)
        }
    }

    private fun downloadNormalMap() {
        startDownload {
            BbbikeExtractInstaller(this).requestAndInstall(mapView.region.bounds) { progress ->
                runOnUiThread { renderExtractProgress(progress) }
            }
        }
    }

    private fun downloadContourMap() {
        val item = ContourMapCatalog.covering(
            mapView.region.centerLatitude,
            mapView.region.centerLongitude,
        ) ?: run {
            statusText.setText(R.string.contour_package_missing)
            return
        }
        startDownload {
            DirectMapPackageInstaller(this).install(item) { downloaded, total ->
                runOnUiThread {
                    statusText.text = if (total > 0) {
                        getString(R.string.map_extract_downloading, downloaded / MEGABYTE, total / MEGABYTE)
                    } else {
                        getString(R.string.map_extract_downloading_unknown, downloaded / MEGABYTE)
                    }
                }
            }
        }
    }

    private fun startDownload(block: () -> java.io.File) {
        downloadButton.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    statusText.setText(R.string.map_download_ready)
                    setResult(RESULT_OK)
                }
                .onFailure { error ->
                    statusText.text = getString(
                        R.string.map_download_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            downloadButton.isEnabled = true
        }
    }

    private fun renderExtractProgress(progress: ExtractProgress) {
        statusText.text = when (progress.stage) {
            ExtractStage.SUBMITTING -> getString(R.string.map_extract_submitting)
            ExtractStage.WAITING -> getString(R.string.map_extract_waiting)
            ExtractStage.RETRYING -> getString(
                R.string.map_extract_retrying,
                progress.retryAttempt,
                progress.retryDelayMillis / 1000,
            )
            ExtractStage.VERIFYING -> getString(R.string.map_extract_verifying)
            ExtractStage.DOWNLOADING -> if (progress.totalBytes > 0) {
                getString(
                    R.string.map_extract_downloading,
                    progress.downloadedBytes / MEGABYTE,
                    progress.totalBytes / MEGABYTE,
                )
            } else {
                getString(R.string.map_extract_downloading_unknown, progress.downloadedBytes / MEGABYTE)
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedButton() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LAST_LOCATION_MAX_AGE_MILLIS = 30L * 60 * 1000
        const val MEGABYTE = 1024.0 * 1024.0
    }
}
