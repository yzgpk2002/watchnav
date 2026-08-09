package com.watchnav.mapsforge

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import com.watchnav.core.Course
import com.watchnav.core.CourseRenderSimplifier
import com.watchnav.core.PreparedCourse
import com.watchnav.session.NavigationStateHub
import com.watchnav.session.NavigationTelemetry
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.rendertheme.StreamRenderTheme
import java.io.File

@SuppressLint("ViewConstructor")
class OfflineMapsforgeView(
    context: Context,
    mapFile: File,
) : FrameLayout(context) {
    private val graphicFactory = AndroidGraphicFactory.INSTANCE
    private val mapView: MapView
    private val mapSource: MapsforgeMapSource
    private val locationLayer: Circle
    private var preparedCourse: PreparedCourse? = null
    private var renderSegments: List<IntArray> = emptyList()
    private val routeLayers = ArrayList<Polyline>()
    private val completedLayers = ArrayList<Polyline>()
    private var followLocation = true

    init {
        AndroidGraphicFactory.createInstance(context.applicationContext)
        mapView = MapView(context).apply {
            setBuiltInZoomControls(false)
            setZoomLevelMin(5)
            setZoomLevelMax(22)
        }
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapSource = MapsforgeMapSource(mapFile)
        val tileCache = AndroidUtil.createTileCache(
            context,
            "watchnav-map-cache",
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

        locationLayer = Circle(
            null,
            8f,
            fill(255, 184, 77, 220),
            stroke(255, 255, 255, 2f),
        )
        mapView.layerManager.layers.add(locationLayer)
    }

    fun setCourse(course: Course) {
        preparedCourse = PreparedCourse.prepare(course)
        val retained = CourseRenderSimplifier.retainedPointIndices(course)
        renderSegments = splitAtCourseBreaks(retained, course)
        routeLayers.forEach(mapView.layerManager.layers::remove)
        completedLayers.forEach(mapView.layerManager.layers::remove)
        routeLayers.clear()
        completedLayers.clear()
        mapView.layerManager.layers.remove(locationLayer)
        renderSegments.forEach { segment ->
            routeLayers += Polyline(stroke(100, 106, 113, 8f), graphicFactory).also { layer ->
                layer.setPoints(segment.map { index ->
                    course.points[index].position.let { LatLong(it.latitude, it.longitude) }
                })
                mapView.layerManager.layers.add(layer)
            }
            completedLayers += Polyline(stroke(85, 226, 141, 9f), graphicFactory).also { layer ->
                mapView.layerManager.layers.add(layer)
            }
        }
        mapView.layerManager.layers.add(locationLayer)
        course.points.firstOrNull()?.position?.let { point ->
            mapView.setCenter(LatLong(point.latitude, point.longitude))
            mapView.setZoomLevel(14)
        }
        mapView.repaint()
    }

    fun setTelemetry(telemetry: NavigationTelemetry) {
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
            val latLong = LatLong(point.latitude, point.longitude)
            locationLayer.setLatLong(latLong)
            locationLayer.setRadius((telemetry.horizontalAccuracyMeters ?: 8.0).toFloat().coerceAtLeast(8f))
            if (followLocation && telemetry.phase.isActivelyNavigating()) mapView.setCenter(latLong)
        }
        mapView.repaint()
    }

    fun toggleFollow(): Boolean {
        followLocation = !followLocation
        if (followLocation) {
            NavigationStateHub.current.position?.let { point ->
                mapView.setCenter(LatLong(point.latitude, point.longitude))
            }
        }
        return followLocation
    }

    fun zoomBy(delta: Int) {
        val current = mapView.model.mapViewPosition.zoomLevel.toInt()
        mapView.setZoomLevel((current + delta).coerceIn(5, 22).toByte())
    }

    fun destroy() {
        mapView.destroyAll()
        mapSource.close()
        AndroidGraphicFactory.clearResourceMemoryCache()
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

    private fun com.watchnav.session.SessionPhase.isActivelyNavigating(): Boolean =
        this == com.watchnav.session.SessionPhase.NAVIGATING ||
            this == com.watchnav.session.SessionPhase.OFF_COURSE
}
