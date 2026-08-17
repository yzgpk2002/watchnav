package com.watchnav.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.watchnav.core.Course
import com.watchnav.core.GeoPoint
import com.watchnav.session.NavigationTelemetry
import com.watchnav.mapsforge.MapViewport
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sinh
import kotlin.math.tan

/** Low-memory MBTiles satellite viewer; live fixes always restore position-centered navigation. */
class WatchMbtilesMapView(context: Context, file: File) : View(context), AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, new: Bitmap?) {
            if (old !== new && !old.isRecycled) old.recycle()
        }
    }
    private val minZoom = metadata("minzoom")?.toIntOrNull() ?: 0
    private val maxZoom = metadata("maxzoom")?.toIntOrNull() ?: 18
    private var coverageMinZoom = minZoom
    private var zoom = metadataCenter().third.coerceIn(minZoom, maxZoom)
    private var centerLon = metadataCenter().first
    private var centerLat = metadataCenter().second
    private val coverageBounds = metadataBounds()
    private var position: GeoPoint? = null
    private var heading = 0.0
    private var course: Course? = null
    private var explorationTrail: List<GeoPoint> = emptyList()
    private var displayRadiusLimitMeters: Double? = null
    private var radiusCenter: GeoPoint? = null
    private var navigationMode = false
    private var fullMapZoom = zoom
    private var followLocation = true
    private var closed = false
    private val tms = !metadata("scheme").equals("xyz", true)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileDestination = RectF()
    private val routePath = Path()
    private val explorationPath = Path()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(183, 91, 255); style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 96, 255); style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        setShadowLayer(resources.displayMetrics.density * 3f, 0f, 0f, Color.WHITE)
    }
    private val positionCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 96, 255); style = Paint.Style.FILL
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            followLocation = false
            val world = worldSize()
            centerLon = worldToLon((lonToWorld(centerLon, zoom) + dx).coerceIn(0.0, world), zoom)
            centerLat = worldToLat((latToWorld(centerLat, zoom) + dy).coerceIn(0.0, world), zoom)
            clampCenter()
            invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomBy(1); return true }
    })
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulated = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { accumulated = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulated *= detector.scaleFactor
            if (accumulated > 1.35f) { zoomBy(1); accumulated = 1f }
            if (accumulated < .74f) { zoomBy(-1); accumulated = 1f }
            return true
        }
    })

    init { setLayerType(LAYER_TYPE_SOFTWARE, null); setBackgroundColor(Color.BLACK) }

    fun setCourse(value: Course) { course = value; invalidate() }
    fun clearCourse() { course = null; explorationTrail = emptyList(); invalidate() }
    fun setExplorationTrail(points: List<GeoPoint>) { explorationTrail = points; invalidate() }
    fun setTelemetry(value: NavigationTelemetry) {
        if (closed) return
        value.position?.let {
            position = it
            radiusCenter = it
            if (followLocation) {
                centerLat = it.latitude
                centerLon = it.longitude
            }
            clampCenter()
        }
        value.bearingDegrees?.let { heading = it }
        invalidate()
    }
    fun setHeadingDegrees(value: Double) { heading = value; invalidate() }
    fun zoomBy(delta: Int) {
        if (closed) return
        val minimum = minimumZoom()
        zoom = (zoom + delta).coerceIn(minimum, maxZoom)
        clampCenter()
        invalidate()
    }
    fun toggleFollow(): Boolean {
        followLocation = !followLocation
        if (followLocation) position?.let {
            centerLat = it.latitude
            centerLon = it.longitude
            clampCenter()
        }
        invalidate()
        return followLocation
    }

    fun setDisplayRadiusLimitMeters(limitMeters: Double?) {
        displayRadiusLimitMeters = limitMeters?.coerceAtLeast(1_000.0)
        val minimum = minimumZoom()
        zoom = zoom.coerceIn(minimum, maxZoom)
        clampCenter()
        invalidate()
    }

    fun setNavigationMode(enabled: Boolean) {
        if (navigationMode == enabled) return
        navigationMode = enabled
        if (enabled) {
            fullMapZoom = zoom
            zoom = NAVIGATION_ZOOM.coerceIn(minimumZoom(), maxZoom)
            followLocation = true
            position?.let { centerLat = it.latitude; centerLon = it.longitude }
        } else {
            zoom = fullMapZoom.coerceIn(minimumZoom(), maxZoom)
        }
        clampCenter()
        invalidate()
    }

    private fun clampCenter() {
        clampCenterToRadius()
        clampCenterToCoverage()
    }

    private fun clampCenterToRadius() {
        val origin = radiusCenter ?: return
        val radius = displayRadiusLimitMeters ?: return
        val northMeters = (centerLat - origin.latitude) * 111_320.0
        val eastMeters = (centerLon - origin.longitude) * 111_320.0 * max(.15, cos(Math.toRadians(origin.latitude)))
        val distance = sqrt(northMeters * northMeters + eastMeters * eastMeters)
        if (distance <= radius || distance == 0.0) return
        val scale = radius / distance
        centerLat = origin.latitude + northMeters * scale / 111_320.0
        centerLon = origin.longitude + eastMeters * scale /
            (111_320.0 * max(.15, cos(Math.toRadians(origin.latitude))))
    }

    private fun clampCenterToCoverage() {
        val bounds = coverageBounds ?: return
        val west = lonToWorld(bounds.west, zoom)
        val east = lonToWorld(bounds.east, zoom)
        val north = latToWorld(bounds.north, zoom)
        val south = latToWorld(bounds.south, zoom)
        val halfWidth = width.coerceAtLeast(1) / 2.0
        val halfHeight = height.coerceAtLeast(1) / 2.0
        val minimumX = west + halfWidth
        val maximumX = east - halfWidth
        val minimumY = north + halfHeight
        val maximumY = south - halfHeight
        val requestedX = lonToWorld(centerLon, zoom)
        val requestedY = latToWorld(centerLat, zoom)
        val clampedX = if (minimumX <= maximumX) requestedX.coerceIn(minimumX, maximumX) else (west + east) / 2.0
        val clampedY = if (minimumY <= maximumY) requestedY.coerceIn(minimumY, maximumY) else (north + south) / 2.0
        centerLon = worldToLon(clampedX, zoom)
        centerLat = worldToLat(clampedY, zoom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCoverageMinimumZoom(w, h)
        clampCenter()
    }

    fun viewport(): MapViewport = MapViewport(centerLat, centerLon, zoom.toByte(), followLocation)

    fun restoreViewport(value: MapViewport) {
        if (closed) return
        followLocation = value.followingLocation
        centerLat = if (followLocation) position?.latitude ?: value.latitude else value.latitude
        centerLon = if (followLocation) position?.longitude ?: value.longitude else value.longitude
        zoom = value.zoomLevel.toInt().coerceIn(minimumZoom(), maxZoom)
        fullMapZoom = zoom
        clampCenter()
        invalidate()
    }

    private fun minimumZoom() = max(minZoom, coverageMinZoom)

    private fun updateCoverageMinimumZoom(viewportWidth: Int, viewportHeight: Int) {
        val bounds = coverageBounds ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        coverageMinZoom = (minZoom..maxZoom).firstOrNull { candidate ->
            lonToWorld(bounds.east, candidate) - lonToWorld(bounds.west, candidate) >= viewportWidth &&
                latToWorld(bounds.south, candidate) - latToWorld(bounds.north, candidate) >= viewportHeight
        } ?: maxZoom
        zoom = zoom.coerceIn(minimumZoom(), maxZoom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closed) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        return try {
            scale.onTouchEvent(event) or gestures.onTouchEvent(event)
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: IllegalStateException) {
            true
        } catch (_: IndexOutOfBoundsException) {
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = lonToWorld(centerLon, zoom); val cy = latToWorld(centerLat, zoom)
        val left = cx - width / 2.0; val top = cy - height / 2.0
        val n = 1 shl zoom.coerceAtMost(30)
        for (rawX in floor(left / TILE).toInt()..floor((left + width) / TILE).toInt()) {
            for (y in floor(top / TILE).toInt()..floor((top + height) / TILE).toInt()) {
                if (y !in 0 until n) continue
                if (rawX !in 0 until n) continue
                val x = rawX; val key = "$zoom/$x/$y"
                val drawX = (rawX * TILE - left).toFloat(); val drawY = (y * TILE - top).toFloat()
                cache.get(key)?.takeUnless(Bitmap::isRecycled)?.let { bitmap ->
                    tileDestination.set(drawX, drawY, drawX + TILE.toFloat(), drawY + TILE.toFloat())
                    canvas.drawBitmap(bitmap, null, tileDestination, tilePaint)
                } ?: requestTile(key, zoom, x, y, n)
            }
        }
        course?.points?.takeIf { it.size >= 2 }?.let { points ->
            routePath.rewind()
            points.forEachIndexed { index, point ->
                val x = (lonToWorld(point.position.longitude, zoom) - left).toFloat()
                val y = (latToWorld(point.position.latitude, zoom) - top).toFloat()
                if (index == 0 || index in course.orEmptyBreaks()) routePath.moveTo(x, y) else routePath.lineTo(x, y)
            }
            canvas.drawPath(routePath, routePaint)
        }
        explorationTrail.takeIf { it.size >= 2 }?.let { points ->
            explorationPath.rewind()
            points.forEachIndexed { index, point ->
                val x = (lonToWorld(point.longitude, zoom) - left).toFloat()
                val y = (latToWorld(point.latitude, zoom) - top).toFloat()
                if (index == 0) explorationPath.moveTo(x, y) else explorationPath.lineTo(x, y)
            }
            canvas.drawPath(explorationPath, routePaint)
        }
        position?.let { point ->
            val x = (lonToWorld(point.longitude, zoom) - left).toFloat()
            val y = (latToWorld(point.latitude, zoom) - top).toFloat()
            val radius = resources.displayMetrics.density * 10f
            val tick = resources.displayMetrics.density * 5f
            canvas.drawCircle(x, y, radius, positionPaint)
            canvas.drawLine(x - radius - tick, y, x - radius + tick, y, positionPaint)
            canvas.drawLine(x + radius - tick, y, x + radius + tick, y, positionPaint)
            canvas.drawLine(x, y - radius - tick, x, y - radius + tick, positionPaint)
            canvas.drawLine(x, y + radius - tick, x, y + radius + tick, positionPaint)
            canvas.drawCircle(x, y, resources.displayMetrics.density * 2.8f, positionCenterPaint)
        }
    }

    private fun Course?.orEmptyBreaks() = this?.breakBeforePointIndices ?: emptySet()
    private fun requestTile(key: String, z: Int, x: Int, y: Int, n: Int) {
        if (!pending.add(key)) return
        worker.execute {
            val row = if (tms) n - 1 - y else y
            runCatching { database.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                arrayOf(z.toString(), x.toString(), row.toString()),
            ).use { if (it.moveToFirst()) it.getBlob(0) else null } }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { cache.put(key, it) }
            pending.remove(key); postInvalidateOnAnimation()
        }
    }
    private fun metadata(name: String): String? = runCatching { database.rawQuery(
        "SELECT value FROM metadata WHERE name=? LIMIT 1", arrayOf(name),
    ).use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
    private fun metadataCenter(): Triple<Double, Double, Int> {
        metadata("center")?.split(',')?.let { p -> if (p.size >= 2) return Triple(p[0].toDouble(), p[1].toDouble(), p.getOrNull(2)?.toIntOrNull() ?: 10) }
        return Triple(0.0, 0.0, 2)
    }
    private fun metadataBounds(): RasterBounds? = metadata("bounds")?.split(',')?.let { parts ->
        if (parts.size < 4) return@let null
        val west = parts[0].toDoubleOrNull() ?: return@let null
        val south = parts[1].toDoubleOrNull() ?: return@let null
        val east = parts[2].toDoubleOrNull() ?: return@let null
        val north = parts[3].toDoubleOrNull() ?: return@let null
        RasterBounds(south, west, north, east).takeIf { south < north && west < east }
    }
    private fun worldSize() = TILE * 2.0.pow(zoom)
    private fun lonToWorld(lon: Double, z: Int) = (lon + 180.0) / 360.0 * TILE * 2.0.pow(z)
    private fun latToWorld(lat: Double, z: Int): Double { val r = Math.toRadians(lat.coerceIn(-85.051, 85.051)); return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * TILE * 2.0.pow(z) }
    private fun worldToLon(x: Double, z: Int) = x / (TILE * 2.0.pow(z)) * 360.0 - 180.0
    private fun worldToLat(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (TILE * 2.0.pow(z))))))
    override fun close() {
        if (closed) return
        closed = true
        worker.shutdownNow()
        cache.evictAll()
        runCatching(database::close)
    }
    private data class RasterBounds(val south: Double, val west: Double, val north: Double, val east: Double)
    private companion object {
        const val TILE = 256.0
        const val NAVIGATION_ZOOM = 17
    }
}
