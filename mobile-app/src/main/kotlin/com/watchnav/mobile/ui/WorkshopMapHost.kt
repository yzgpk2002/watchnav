package com.watchnav.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.watchnav.core.Course
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.OfflineMapsforgeView
import com.watchnav.mobile.R
import java.io.File
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polyline

class WorkshopMapHost(context: Context) : FrameLayout(context) {
    private val fallback = ImageView(context).apply {
        setImageResource(R.drawable.route_workshop_map)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "离线水域地图"
    }
    private val preview = CoursePreviewView(context)
    private var offlineMap: OfflineMapsforgeView? = null
    private var onlineMap: OnlineCourseMapView? = null
    private var rasterMap: MbtilesRouteView? = null
    private var selectedRaster: File? = null
    private var currentCourse: Course? = null

    init {
        setBackgroundColor(Color.rgb(3, 8, 10))
        rebuildMap()
    }

    private fun rebuildMap() {
        val previousViewport = offlineMap?.viewport()
        offlineMap?.destroy()
        offlineMap = null
        onlineMap?.destroy()
        onlineMap = null
        rasterMap?.close()
        rasterMap = null
        removeAllViews()
        val repository = MapsforgeMapRepository(context)
        val stack = repository.activeStack()
        val course = currentCourse
        val raster = selectedRaster?.takeIf(File::isFile)
        if (raster != null && raster.extension.equals("mbtiles", true)) {
            runCatching { MbtilesRouteView(context, raster, course) }.onSuccess { view ->
                rasterMap = view
                addView(view, LayoutParams(-1, -1))
            }.onFailure {
                selectedRaster = null
                addView(fallback, LayoutParams(-1, -1)); addView(preview, LayoutParams(-1, -1))
            }
        } else if (!stack.isEmpty && (course == null || repository.activeMapCovers(course))) {
            runCatching { OfflineMapsforgeView(context, stack.files, stack.cacheIdentity) }.onSuccess { view ->
                offlineMap = view
                addView(view, LayoutParams(-1, -1))
                currentCourse?.let(view::setCourse)
                previousViewport?.let { viewport -> view.post { view.restoreViewport(viewport) } }
            }.onFailure {
                addView(fallback, LayoutParams(-1, -1))
                addView(preview, LayoutParams(-1, -1))
            }
        } else if (course != null) {
            addView(fallback, LayoutParams(-1, -1))
            runCatching { OnlineCourseMapView(context) }.onSuccess { view ->
                onlineMap = view
                addView(view, LayoutParams(-1, -1))
                view.setCourse(course)
            }.onFailure { addView(preview, LayoutParams(-1, -1)) }
        } else {
            addView(fallback, LayoutParams(-1, -1))
            addView(preview, LayoutParams(-1, -1))
        }
    }

    fun setCourse(course: Course?) {
        if (course == null) {
            if (currentCourse == null) return
            currentCourse = null
            rebuildMap()
            return
        }
        val shouldRebuild = rasterMap != null || currentCourse == null ||
            (offlineMap != null && !MapsforgeMapRepository(context).activeMapCovers(course)) ||
            (onlineMap != null && MapsforgeMapRepository(context).activeMapCovers(course))
        currentCourse = course
        if (shouldRebuild) rebuildMap()
        else offlineMap?.setCourse(course) ?: onlineMap?.setCourse(course) ?: preview.setCourse(course)
    }

    fun reloadOfflineMap() = rebuildMap()

    fun showRaster(file: File) {
        require(file.isFile && file.extension.equals("mbtiles", true))
        selectedRaster = file
        rebuildMap()
    }

    fun showVector() {
        selectedRaster = null
        rebuildMap()
    }

    fun destroy() {
        offlineMap?.destroy()
        onlineMap?.destroy()
        rasterMap?.close()
    }
}

private class OnlineCourseMapView(context: Context) : FrameLayout(context) {
    private val graphicFactory = AndroidGraphicFactory.INSTANCE
    private val mapView: MapView
    private val downloadLayer: TileDownloadLayer
    private val routeLayers = ArrayList<Polyline>()
    private val markerLayers = ArrayList<Circle>()

    init {
        AndroidGraphicFactory.createInstance(context.applicationContext)
        mapView = MapView(context).apply {
            setBuiltInZoomControls(false)
            setZoomLevelMin(3)
            setZoomLevelMax(20)
            model.frameBufferModel.overdrawFactor = 1.0
        }
        addView(mapView, LayoutParams(-1, -1))
        val tileCache = AndroidUtil.createTileCache(
            context,
            "watchnav-phone-online-cache",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor,
            true,
        )
        val source = OpenStreetMapMapnik.INSTANCE.apply {
            setUserAgent("WatchNav/0.16 Android companion")
            setTimeoutConnect(15_000)
            setTimeoutRead(25_000)
        }
        downloadLayer = TileDownloadLayer(
            tileCache,
            mapView.model.mapViewPosition,
            source,
            graphicFactory,
        )
        mapView.layerManager.layers.add(downloadLayer)
        downloadLayer.start()
    }

    fun setCourse(course: Course) {
        routeLayers.forEach(mapView.layerManager.layers::remove)
        markerLayers.forEach(mapView.layerManager.layers::remove)
        routeLayers.clear()
        markerLayers.clear()
        if (course.points.size < 2) return
        val route = Polyline(stroke(159, 72, 255, 9f), graphicFactory).apply {
            setPoints(course.points.map { LatLong(it.position.latitude, it.position.longitude) })
        }
        routeLayers += route
        mapView.layerManager.layers.add(route)
        listOf(course.points.first(), course.points.last()).forEachIndexed { index, point ->
            markerLayers += Circle(
                LatLong(point.position.latitude, point.position.longitude),
                9f,
                fill(if (index == 0) 55 else 255, if (index == 0) 215 else 255, if (index == 0) 117 else 255),
                stroke(255, 255, 255, 2f),
            ).also(mapView.layerManager.layers::add)
        }
        course.landmarks.forEach { point ->
            markerLayers += Circle(
                LatLong(point.position.latitude, point.position.longitude),
                7f,
                fill(34, 211, 238),
                stroke(255, 255, 255, 1.5f),
            ).also(mapView.layerManager.layers::add)
        }
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
            ).coerceIn(3, 18)
            mapView.setCenter(bounds.centerPoint)
            mapView.setZoomLevel(zoom)
            mapView.repaint()
        }
    }

    fun destroy() {
        downloadLayer.onDestroy()
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    private fun stroke(red: Int, green: Int, blue: Int, width: Float) =
        graphicFactory.createPaint().apply {
            color = graphicFactory.createColor(255, red, green, blue)
            strokeWidth = width
            setStyle(Style.STROKE)
        }

    private fun fill(red: Int, green: Int, blue: Int) = graphicFactory.createPaint().apply {
        color = graphicFactory.createColor(235, red, green, blue)
        setStyle(Style.FILL)
    }
}

private class CoursePreviewView(context: Context) : View(context) {
    private var course: Course? = null
    private val routePath = Path()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(159, 72, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(resources.displayMetrics.density * 4f, 0f, 0f, Color.rgb(115, 20, 230))
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 212, 117)
        style = Paint.Style.FILL
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setCourse(value: Course) {
        course = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val points = course?.points ?: return
        if (points.size < 2) return
        val minLat = points.minOf { it.position.latitude }
        val maxLat = points.maxOf { it.position.latitude }
        val minLon = points.minOf { it.position.longitude }
        val maxLon = points.maxOf { it.position.longitude }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-8)
        val lonSpan = (maxLon - minLon).coerceAtLeast(1e-8)
        val pad = resources.displayMetrics.density * 42f
        fun x(lon: Double) = pad + ((lon - minLon) / lonSpan * (width - pad * 2)).toFloat()
        fun y(lat: Double) = pad + ((maxLat - lat) / latSpan * (height - pad * 2)).toFloat()
        routePath.rewind()
        points.forEachIndexed { index, point ->
            val px = x(point.position.longitude)
            val py = y(point.position.latitude)
            if (index == 0 || index in (course?.breakBeforePointIndices ?: emptySet())) routePath.moveTo(px, py)
            else routePath.lineTo(px, py)
        }
        canvas.drawPath(routePath, routePaint)
        val radius = resources.displayMetrics.density * 6f
        points.first().position.let { canvas.drawCircle(x(it.longitude), y(it.latitude), radius, startPaint) }
        points.last().position.let { canvas.drawCircle(x(it.longitude), y(it.latitude), radius, nodePaint) }
    }
}
