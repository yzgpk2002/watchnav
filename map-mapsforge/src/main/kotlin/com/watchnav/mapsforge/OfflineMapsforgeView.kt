package com.watchnav.mapsforge

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import com.watchnav.core.Course
import com.watchnav.core.CourseRenderSimplifier
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationStateHub
import com.watchnav.session.NavigationTelemetry
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.rendertheme.StreamRenderTheme
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

data class MapViewport(
    val latitude: Double,
    val longitude: Double,
    val zoomLevel: Byte,
    val followingLocation: Boolean,
)

@SuppressLint("ViewConstructor")
class OfflineMapsforgeView(
    context: Context,
    mapFiles: List<File>,
    cacheIdentity: String,
    private var navigationMode: Boolean = false,
) : FrameLayout(context) {
    private val graphicFactory = AndroidGraphicFactory.INSTANCE
    private val mapView: MapView
    private val mapSource: MapsforgeMapSource
    private val locationLayer: Circle
    private val locationArrowLayer: Polyline
    private val locationVerticalLayer: Polyline
    private val explorationLayer: Polyline
    private var preparedCourse: PreparedCourse? = null
    private var renderSegments: List<IntArray> = emptyList()
    private val routeLayers = ArrayList<Polyline>()
    private val completedLayers = ArrayList<Polyline>()
    private val landmarkLayers = ArrayList<Circle>()
    private var followLocation = true
    private var currentTelemetry = NavigationTelemetry()
    private var headingDegrees: Double? = null
    private var fullMapZoomLevel: Byte = 14
    private var displayRadiusLimitMeters: Double? = null
    private var displayRadiusCenter: LatLong? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val releaseOnlyPan = mapFiles.any { it.length() >= LARGE_MAP_RELEASE_ONLY_BYTES }
    private var pinchStartDistance = 0f
    private var pinchStartZoom: Byte = 14
    private var minimumZoomLevel: Byte = 5
    private var destroyed = false
    private var activeMapBounds: BoundingBox? = null

    init {
        AndroidGraphicFactory.createInstance(context.applicationContext)
        mapView = MapView(context).apply {
            setBuiltInZoomControls(false)
            setZoomLevelMin(5)
            setZoomLevelMax(22)
            model.frameBufferModel.overdrawFactor = 1.0
            setOnTouchListener { _, event ->
                if (releaseOnlyPan) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.x
                            touchDownY = event.y
                            followLocation = false
                            pinchStartDistance = 0f
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (event.pointerCount >= 2) {
                                pinchStartDistance = pointerDistance(event)
                                pinchStartZoom = model.mapViewPosition.zoomLevel
                            }
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            applyReleaseOnlyPinch(event)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> return@setOnTouchListener true
                        MotionEvent.ACTION_UP -> {
                            if (pinchStartDistance <= 0f) {
                                panViewportOnce(touchDownX - event.x, event.y - touchDownY)
                            }
                            pinchStartDistance = 0f
                            post(::clampViewportToMap)
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            pinchStartDistance = 0f
                            post(::clampViewportToMap)
                            return@setOnTouchListener true
                        }
                    }
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touchDownX = event.x; touchDownY = event.y }
                    MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2 ||
                        kotlin.math.abs(event.x - touchDownX) + kotlin.math.abs(event.y - touchDownY) > 12f
                    ) {
                        followLocation = false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> post(::clampViewportToMap)
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE) post(::clampViewportToMap)
                false
            }
        }
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapSource = MapsforgeMapSource(mapFiles)
        applyMapLimit(mapSource.boundingBox)
        val tileCache = AndroidUtil.createTileCache(
            context,
            cacheNamespace(cacheIdentity, navigationMode),
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor,
            true,
        )
        val theme = StreamRenderTheme("", resources.openRawResource(R.raw.watchnav_theme))
        val renderer = AndroidUtil.createTileRendererLayer(
            tileCache,
            mapView.model.mapViewPosition,
            mapSource.dataStore,
            theme,
            false,
            true,
            false,
        )
        mapView.layerManager.layers.add(renderer)
        mapSource.boundingBox?.let { post(::updateMinimumZoomForActiveBounds) }

        locationLayer = Circle(
            null,
            10f,
            fill(198, 92, 255, 35),
            stroke(198, 92, 255, 4f),
        )
        mapView.layerManager.layers.add(locationLayer)
        locationArrowLayer = Polyline(stroke(198, 92, 255, 4f), graphicFactory)
        mapView.layerManager.layers.add(locationArrowLayer)
        locationVerticalLayer = Polyline(stroke(198, 92, 255, 4f), graphicFactory)
        mapView.layerManager.layers.add(locationVerticalLayer)
        explorationLayer = Polyline(stroke(188, 92, 255, 7f), graphicFactory)
        mapView.layerManager.layers.add(explorationLayer)
        mapView.setZoomLevel(if (navigationMode) NAVIGATION_ZOOM else INITIAL_LOCATION_ZOOM)
    }

    constructor(context: Context, mapFile: File, navigationMode: Boolean = false) : this(
        context = context,
        mapFiles = listOf(mapFile),
        cacheIdentity = "legacy-${mapFile.length()}-${mapFile.lastModified()}",
        navigationMode = navigationMode,
    )

    fun setCourse(course: Course) {
        if (destroyed) return
        preparedCourse = PreparedCourse.prepare(course)
        val retained = CourseRenderSimplifier.retainedPointIndices(course)
        renderSegments = splitAtCourseBreaks(retained, course)
        routeLayers.forEach(mapView.layerManager.layers::remove)
        completedLayers.forEach(mapView.layerManager.layers::remove)
        landmarkLayers.forEach(mapView.layerManager.layers::remove)
        routeLayers.clear()
        completedLayers.clear()
        landmarkLayers.clear()
        mapView.layerManager.layers.remove(locationLayer)
        mapView.layerManager.layers.remove(locationArrowLayer)
        mapView.layerManager.layers.remove(locationVerticalLayer)
        renderSegments.forEach { segment ->
            routeLayers += Polyline(stroke(151, 71, 255, 8f), graphicFactory).also { layer ->
                layer.setPoints(segment.map { index ->
                    course.points[index].position.let { LatLong(it.latitude, it.longitude) }
                })
                mapView.layerManager.layers.add(layer)
            }
            completedLayers += Polyline(stroke(218, 112, 255, 9f), graphicFactory).also { layer ->
                mapView.layerManager.layers.add(layer)
            }
        }
        course.landmarks.forEach { landmark ->
            landmarkLayers += Circle(
                LatLong(landmark.position.latitude, landmark.position.longitude),
                10f,
                fill(255, 214, 64, 245),
                stroke(30, 20, 0, 2f),
            ).also(mapView.layerManager.layers::add)
        }
        mapView.layerManager.layers.add(locationLayer)
        mapView.layerManager.layers.add(locationArrowLayer)
        mapView.layerManager.layers.add(locationVerticalLayer)
        if (navigationMode) {
            course.points.firstOrNull()?.position?.let { point ->
                mapView.setCenter(LatLong(point.latitude, point.longitude))
                mapView.setZoomLevel(NAVIGATION_ZOOM)
            }
        } else {
            fitCourse(course)
        }
        mapView.repaint()
    }

    fun clearCourse() {
        if (destroyed) return
        preparedCourse = null
        renderSegments = emptyList()
        routeLayers.forEach(mapView.layerManager.layers::remove)
        completedLayers.forEach(mapView.layerManager.layers::remove)
        landmarkLayers.forEach(mapView.layerManager.layers::remove)
        routeLayers.clear()
        completedLayers.clear()
        landmarkLayers.clear()
        explorationLayer.setPoints(emptyList())
        mapView.repaint()
    }

    fun setExplorationTrail(points: List<com.watchnav.core.GeoPoint>) {
        if (destroyed) return
        explorationLayer.setPoints(points.map { LatLong(it.latitude, it.longitude) })
        mapView.repaint()
    }

    private fun fitCourse(course: Course) {
        if (course.points.isEmpty()) return
        val bounds = BoundingBox(
            course.points.minOf { it.position.latitude },
            course.points.minOf { it.position.longitude },
            course.points.maxOf { it.position.latitude },
            course.points.maxOf { it.position.longitude },
        ).extendMargin(1.18f)
        post {
            if (width <= 0 || height <= 0) return@post
            val zoom = LatLongUtils.zoomForBounds(
                Dimension(width, height),
                bounds,
                mapView.model.displayModel.tileSize,
            ).coerceIn(minimumZoomLevel, 18.toByte())
            mapView.setCenter(bounds.centerPoint)
            mapView.setZoomLevel(zoom)
            fullMapZoomLevel = zoom
            mapView.repaint()
        }
    }

    fun setTelemetry(telemetry: NavigationTelemetry) {
        if (destroyed) return
        currentTelemetry = telemetry
        val prepared = preparedCourse
        if (prepared != null) {
            completedLayers.zip(renderSegments).forEach { (layer, segment) ->
                val completedPoints = segment.takeWhile { index ->
                    prepared.cumulativeDistanceMeters[index] <= telemetry.progressMeters
                }.map { index ->
                    prepared.course.points[index].position.let { LatLong(it.latitude, it.longitude) }
                }
                layer.setPoints(completedPoints)
            }
        }
        telemetry.position?.let { point ->
            applyDisplayRadiusLimit(point.latitude, point.longitude)
            val latLong = LatLong(point.latitude, point.longitude)
            locationLayer.setLatLong(latLong)
            locationLayer.setRadius((LOCATION_RADIUS_PIXELS * metersPerPixel(point.latitude)).toFloat())
            val crosshair = crosshairPoints(point.latitude, point.longitude)
            locationArrowLayer.setPoints(crosshair.first)
            locationVerticalLayer.setPoints(crosshair.second)
            if (followLocation) {
                mapView.setCenter(latLong)
                if (!navigationMode && mapView.model.mapViewPosition.zoomLevel < MINIMUM_LOCATION_ZOOM) {
                    mapView.setZoomLevel(INITIAL_LOCATION_ZOOM)
                    fullMapZoomLevel = INITIAL_LOCATION_ZOOM
                }
            }
        }
        mapView.repaint()
    }

    fun setDisplayRadiusLimitMeters(limitMeters: Double?) {
        displayRadiusLimitMeters = limitMeters?.coerceAtLeast(1_000.0)
        if (displayRadiusLimitMeters == null) {
            displayRadiusCenter = null
            applyMapLimit(mapSource.boundingBox)
        } else {
            currentTelemetry.position?.let { applyDisplayRadiusLimit(it.latitude, it.longitude) }
        }
        mapView.repaint()
    }

    private fun applyDisplayRadiusLimit(latitude: Double, longitude: Double) {
        val radius = displayRadiusLimitMeters ?: return
        val previousCenter = displayRadiusCenter
        if (previousCenter != null && approximateDistanceMeters(
                previousCenter.latitude,
                previousCenter.longitude,
                latitude,
                longitude,
            ) < RADIUS_WINDOW_RECENTER_METERS
        ) return
        displayRadiusCenter = LatLong(latitude, longitude)
        val latitudeDelta = radius / 111_320.0
        val longitudeDelta = radius / (111_320.0 * max(.15, cos(Math.toRadians(latitude))))
        val radiusBounds = BoundingBox(
                (latitude - latitudeDelta).coerceAtLeast(-85.0),
                (longitude - longitudeDelta).coerceAtLeast(-180.0),
                (latitude + latitudeDelta).coerceAtMost(85.0),
                (longitude + longitudeDelta).coerceAtMost(180.0),
            )
        applyMapLimit(intersection(radiusBounds, mapSource.boundingBox) ?: mapSource.boundingBox ?: radiusBounds)
    }

    fun setHeadingDegrees(value: Double) {
        if (destroyed) return
        headingDegrees = value
        // The map page now uses a north-independent target reticle. Heading remains available to
        // the dedicated navigation page and does not rotate or resize this location marker.
    }

    fun setNavigationMode(enabled: Boolean) {
        if (navigationMode == enabled) return
        if (enabled) fullMapZoomLevel = mapView.model.mapViewPosition.zoomLevel
        navigationMode = enabled
        if (enabled) {
            followLocation = true
            mapView.setZoomLevel(NAVIGATION_ZOOM)
            currentTelemetry.position?.let { mapView.setCenter(LatLong(it.latitude, it.longitude)) }
        } else {
            mapView.setZoomLevel(fullMapZoomLevel)
        }
        mapView.repaint()
    }

    fun toggleFollow(): Boolean {
        if (navigationMode) return true
        followLocation = !followLocation
        if (followLocation) {
            NavigationStateHub.current.position?.let { point ->
                mapView.setCenter(LatLong(point.latitude, point.longitude))
            }
        }
        return followLocation
    }

    fun viewport(): MapViewport? = runCatching {
        val center = mapView.model.mapViewPosition.center
        MapViewport(center.latitude, center.longitude, mapView.model.mapViewPosition.zoomLevel, followLocation)
    }.getOrNull()

    fun restoreViewport(value: MapViewport) {
        followLocation = value.followingLocation
        if (destroyed) return
        val requestedZoom = value.zoomLevel.coerceIn(minimumZoomLevel, 22.toByte())
        val restoredZoom = if (followLocation && requestedZoom < MINIMUM_LOCATION_ZOOM) {
            INITIAL_LOCATION_ZOOM
        } else {
            requestedZoom
        }
        val followedPosition = currentTelemetry.position.takeIf { followLocation }
        mapView.setCenter(
            followedPosition?.let { LatLong(it.latitude, it.longitude) }
                ?: LatLong(value.latitude, value.longitude),
        )
        mapView.setZoomLevel(restoredZoom)
        fullMapZoomLevel = restoredZoom
        mapView.repaint()
    }

    fun zoomBy(delta: Int) {
        if (destroyed) return
        val current = mapView.model.mapViewPosition.zoomLevel.toInt()
        mapView.setZoomLevel((current + delta).coerceIn(minimumZoomLevel.toInt(), 22).toByte())
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        mapView.destroyAll()
        mapSource.close()
    }

    private fun splitAtCourseBreaks(indices: IntArray, course: Course): List<IntArray> {
        val segments = ArrayList<MutableList<Int>>()
        indices.forEach { index ->
            if (segments.isEmpty() || index in course.breakBeforePointIndices) segments.add(ArrayList())
            segments.last() += index
        }
        return segments.filter { it.size >= 2 }.map { it.toIntArray() }
    }

    private fun stroke(red: Int, green: Int, blue: Int, width: Float) =
        graphicFactory.createPaint().apply {
            color = graphicFactory.createColor(255, red, green, blue)
            strokeWidth = width
            setStyle(Style.STROKE)
        }

    private fun fill(red: Int, green: Int, blue: Int, alpha: Int) =
        graphicFactory.createPaint().apply {
            color = graphicFactory.createColor(alpha, red, green, blue)
            setStyle(Style.FILL)
        }

    private fun crosshairPoints(latitude: Double, longitude: Double): Pair<List<LatLong>, List<LatLong>> {
        val metersPerPixel = metersPerPixel(latitude)
        fun offset(distanceMeters: Double, degrees: Double): LatLong {
            val angle = Math.toRadians(degrees)
            val lat = latitude + Math.cos(angle) * distanceMeters / 111_320.0
            val lon = longitude + Math.sin(angle) * distanceMeters /
                (111_320.0 * Math.cos(Math.toRadians(latitude)).coerceAtLeast(0.15))
            return LatLong(lat, lon)
        }
        val outer = CROSSHAIR_OUTER_PIXELS * metersPerPixel
        val inner = CROSSHAIR_INNER_PIXELS * metersPerPixel
        return Pair(
            listOf(offset(outer, 270.0), offset(inner, 270.0), offset(inner, 90.0), offset(outer, 90.0)),
            listOf(offset(outer, 0.0), offset(inner, 0.0), offset(inner, 180.0), offset(outer, 180.0)),
        )
    }

    private fun metersPerPixel(latitude: Double): Double {
        val mapSize = MercatorProjection.getMapSize(
            mapView.model.mapViewPosition.zoomLevel,
            mapView.model.displayModel.tileSize,
        ).toDouble()
        return (2.0 * PI * 6_378_137.0 * cos(Math.toRadians(latitude)).coerceAtLeast(.15)) / mapSize
    }

    private fun panViewportOnce(horizontalPixels: Float, northPixels: Float) {
        if (destroyed || kotlin.math.abs(horizontalPixels) + kotlin.math.abs(northPixels) < 8f) return
        val center = mapView.model.mapViewPosition.center
        val metersPerPixel = metersPerPixel(center.latitude)
        val latitude = center.latitude + northPixels * metersPerPixel / 111_320.0
        val longitude = center.longitude + horizontalPixels * metersPerPixel /
            (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(.15))
        mapView.setCenter(LatLong(latitude.coerceIn(-85.0, 85.0), longitude.coerceIn(-180.0, 180.0)))
        clampViewportToMap()
        mapView.repaint()
    }

    private fun pointerDistance(event: MotionEvent): Float = if (event.pointerCount >= 2) {
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    } else 0f

    private fun applyReleaseOnlyPinch(event: MotionEvent) {
        if (pinchStartDistance <= 0f || event.pointerCount < 2) return
        val ratio = (pointerDistance(event) / pinchStartDistance).coerceAtLeast(.1f)
        val zoomDelta = (ln(ratio.toDouble()) / ln(2.0)).roundToInt()
        mapView.setZoomLevel(
            (pinchStartZoom.toInt() + zoomDelta).coerceIn(minimumZoomLevel.toInt(), 22).toByte(),
        )
        mapView.repaint()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (destroyed) true else try {
        super.dispatchTouchEvent(event)
    } catch (_: IllegalArgumentException) {
        // Mapsforge can reject a gesture when its viewport changes in the same frame as a
        // navigation update. Keep the app alive and let the next gesture start cleanly.
        true
    } catch (_: IndexOutOfBoundsException) {
        true
    } catch (_: IllegalStateException) {
        true
    }

    private fun applyMapLimit(bounds: BoundingBox?) {
        activeMapBounds = bounds
        mapView.model.mapViewPosition.setMapLimit(bounds ?: BoundingBox(-85.0, -180.0, 85.0, 180.0))
        post(::updateMinimumZoomForActiveBounds)
        post(::clampViewportToMap)
    }

    /**
     * The lowest zoom must still cover the complete rectangular display. Mapsforge's
     * zoomForBounds fits the whole package *inside* the display and can therefore expose blank
     * margins. Here the package covers the display instead; its edges can never be panned into
     * the centre and the minimum zoom shows the largest safe overview.
     */
    private fun updateMinimumZoomForActiveBounds() {
        if (destroyed || width <= 0 || height <= 0) return
        val bounds = activeMapBounds ?: return
        minimumZoomLevel = minimumCoveringZoom(bounds, width, height)
        mapView.setZoomLevelMin(minimumZoomLevel)
        if (mapView.model.mapViewPosition.zoomLevel < minimumZoomLevel) {
            mapView.setZoomLevel(minimumZoomLevel)
        }
        clampViewportToMap()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        post(::updateMinimumZoomForActiveBounds)
    }

    private fun minimumCoveringZoom(bounds: BoundingBox, viewportWidth: Int, viewportHeight: Int): Byte {
        val centerLatitude = bounds.centerPoint.latitude
        val widthMeters = (bounds.maxLongitude - bounds.minLongitude) * 111_320.0 *
            cos(Math.toRadians(centerLatitude)).coerceAtLeast(.15)
        val heightMeters = (bounds.maxLatitude - bounds.minLatitude) * 111_320.0
        for (candidate in 5..22) {
            val mapSize = MercatorProjection.getMapSize(
                candidate.toByte(),
                mapView.model.displayModel.tileSize,
            ).toDouble()
            val metersPerPixel = (2.0 * PI * 6_378_137.0 *
                cos(Math.toRadians(centerLatitude)).coerceAtLeast(.15)) / mapSize
            if (widthMeters / metersPerPixel >= viewportWidth &&
                heightMeters / metersPerPixel >= viewportHeight
            ) return candidate.toByte()
        }
        return 22
    }

    private fun approximateDistanceMeters(
        firstLatitude: Double,
        firstLongitude: Double,
        secondLatitude: Double,
        secondLongitude: Double,
    ): Double {
        val north = (secondLatitude - firstLatitude) * 111_320.0
        val east = (secondLongitude - firstLongitude) * 111_320.0 *
            cos(Math.toRadians((firstLatitude + secondLatitude) / 2.0)).coerceAtLeast(.15)
        return kotlin.math.sqrt(north * north + east * east)
    }

    /** Mapsforge constrains the centre point, not the viewport edges. Keep the whole circular
     * display inside the package coverage so a pan cannot reveal an unbounded blank canvas. */
    private fun clampViewportToMap() {
        if (destroyed || width <= 0 || height <= 0) return
        val bounds = activeMapBounds ?: return
        val center = mapView.model.mapViewPosition.center
        val halfHeightMeters = metersPerPixel(center.latitude) * height / 2.0
        val halfWidthMeters = metersPerPixel(center.latitude) * width / 2.0
        val latitudeInset = halfHeightMeters / 111_320.0
        val longitudeInset = halfWidthMeters /
            (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(.15))
        val latitude = clampAxis(
            center.latitude,
            bounds.minLatitude + latitudeInset,
            bounds.maxLatitude - latitudeInset,
            bounds.centerPoint.latitude,
        )
        val longitude = clampAxis(
            center.longitude,
            bounds.minLongitude + longitudeInset,
            bounds.maxLongitude - longitudeInset,
            bounds.centerPoint.longitude,
        )
        if (kotlin.math.abs(latitude - center.latitude) > 1e-8 ||
            kotlin.math.abs(longitude - center.longitude) > 1e-8
        ) {
            mapView.setCenter(LatLong(latitude, longitude))
            mapView.repaint()
        }
    }

    private fun clampAxis(value: Double, minimum: Double, maximum: Double, fallback: Double): Double =
        if (minimum <= maximum) value.coerceIn(minimum, maximum) else fallback

    private fun intersection(first: BoundingBox, second: BoundingBox?): BoundingBox? {
        second ?: return first
        val south = maxOf(first.minLatitude, second.minLatitude)
        val west = maxOf(first.minLongitude, second.minLongitude)
        val north = minOf(first.maxLatitude, second.maxLatitude)
        val east = minOf(first.maxLongitude, second.maxLongitude)
        return if (south < north && west < east) BoundingBox(south, west, north, east) else null
    }

    private companion object {
        const val NAVIGATION_ZOOM: Byte = 17
        const val MINIMUM_LOCATION_ZOOM: Byte = 10
        const val INITIAL_LOCATION_ZOOM: Byte = 14
        const val LOCATION_RADIUS_PIXELS = 8.0
        const val CROSSHAIR_OUTER_PIXELS = 16.0
        const val CROSSHAIR_INNER_PIXELS = 7.0
        const val RADIUS_WINDOW_RECENTER_METERS = 250.0
        const val LARGE_MAP_RELEASE_ONLY_BYTES = 128L * 1024L * 1024L

        fun cacheNamespace(identity: String, navigationMode: Boolean): String {
            val safe = identity.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(96)
            return "watchnav-${if (navigationMode) "nav" else "map"}-$safe"
        }
    }

    private fun com.watchnav.session.SessionPhase.isActivelyNavigating(): Boolean =
        this == com.watchnav.session.SessionPhase.NAVIGATING ||
            this == com.watchnav.session.SessionPhase.OFF_COURSE
}
