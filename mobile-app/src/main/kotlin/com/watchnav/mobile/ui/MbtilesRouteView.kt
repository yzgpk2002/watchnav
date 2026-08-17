package com.watchnav.mobile.ui

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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Lightweight offline raster MBTiles viewer with pan, pinch zoom and a purple course overlay. */
class MbtilesRouteView(context: Context, file: File, private val course: Course?) : View(context), AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }
    private var minZoom = metadataInt("minzoom") ?: 0
    private var maxZoom = metadataInt("maxzoom") ?: 18
    private var coverageMinZoom = minZoom
    private var zoom = initialZoom().coerceIn(minZoom, maxZoom)
    private var centerLon = course?.points?.map { it.position.longitude }?.average() ?: metadataCenter().first
    private var centerLat = course?.points?.map { it.position.latitude }?.average() ?: metadataCenter().second
    private val coverageBounds = metadataBounds()
    private val tms = !metadata("scheme").equals("xyz", ignoreCase = true)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileDestination = RectF()
    private val coursePath = Path()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(159, 72, 255); style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = resources.displayMetrics.scaledDensity * 14f
    }
    private val attribution = metadata("attribution").orEmpty()
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.scaledDensity * 9f
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238); style = Paint.Style.FILL
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val world = worldSize()
            val x = lonToWorld(centerLon, zoom) + distanceX
            val y = latToWorld(centerLat, zoom) + distanceY
            centerLon = worldToLon(x.coerceIn(0.0, world), zoom)
            centerLat = worldToLat(y.coerceIn(0.0, world), zoom)
            clampCenterToCoverage()
            invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(zoom + 1); return true }
    })
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulated = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { accumulated = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulated *= detector.scaleFactor
            if (accumulated > 1.35f) { setZoom(zoom + 1); accumulated = 1f }
            if (accumulated < .74f) { setZoom(zoom - 1); accumulated = 1f }
            return true
        }
    })

    init {
        setBackgroundColor(Color.rgb(3, 8, 10))
        contentDescription = "历史卫星影像离线地图"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return scale.onTouchEvent(event) or gestures.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCoverageMinimumZoom(w, h)
        clampCenterToCoverage()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = lonToWorld(centerLon, zoom)
        val centerY = latToWorld(centerLat, zoom)
        val left = centerX - width / 2.0
        val top = centerY - height / 2.0
        val n = 1 shl zoom.coerceAtMost(30)
        val firstX = floor(left / TILE).toInt()
        val lastX = floor((left + width) / TILE).toInt()
        val firstY = floor(top / TILE).toInt()
        val lastY = floor((top + height) / TILE).toInt()
        var visible = 0
        for (rawX in firstX..lastX) for (y in firstY..lastY) {
            if (y !in 0 until n) continue
            val x = rawX.mod(n)
            val key = "$zoom/$x/$y"
            val bitmap = cache.get(key)
            val drawX = (rawX * TILE - left).toFloat()
            val drawY = (y * TILE - top).toFloat()
            if (bitmap != null && !bitmap.isRecycled) {
                tileDestination.set(drawX, drawY, drawX + TILE.toFloat(), drawY + TILE.toFloat())
                canvas.drawBitmap(bitmap, null, tileDestination, tilePaint)
                visible++
            } else requestTile(key, zoom, x, y, n)
        }
        drawCourse(canvas, left, top)
        if (visible == 0) canvas.drawText("正在读取离线影像…", 24f, height - 30f, statusPaint)
        if (attribution.isNotBlank()) {
            canvas.drawText(attribution.take(72), 12f, height - 34f, attributionPaint)
        }
    }

    private fun drawCourse(canvas: Canvas, left: Double, top: Double) {
        val points = course?.points ?: return
        if (points.size < 2) return
        coursePath.rewind()
        points.forEachIndexed { index, point ->
            val x = (lonToWorld(point.position.longitude, zoom) - left).toFloat()
            val y = (latToWorld(point.position.latitude, zoom) - top).toFloat()
            if (index == 0 || index in course.breakBeforePointIndices) coursePath.moveTo(x, y) else coursePath.lineTo(x, y)
        }
        canvas.drawPath(coursePath, routePaint)
        val privateRadius = resources.displayMetrics.density * 5f
        val publicRadius = resources.displayMetrics.density * 3f
        course.landmarks.forEach { point ->
            val isPublicFacility = point.type == "synced-public"
            if (isPublicFacility && zoom < 14) return@forEach
            val x = (lonToWorld(point.position.longitude, zoom) - left).toFloat()
            val y = (latToWorld(point.position.latitude, zoom) - top).toFloat()
            canvas.drawCircle(x, y, if (isPublicFacility) publicRadius else privateRadius, markerPaint)
        }
    }

    private fun requestTile(key: String, z: Int, x: Int, y: Int, n: Int) {
        if (!pending.add(key)) return
        worker.execute {
            val row = if (tms) n - 1 - y else y
            val bytes = runCatching {
                database.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                    arrayOf(z.toString(), x.toString(), row.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            }.getOrNull()
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { cache.put(key, it) }
            pending.remove(key)
            postInvalidateOnAnimation()
        }
    }

    private fun setZoom(value: Int) {
        zoom = value.coerceIn(maxOf(minZoom, coverageMinZoom), maxZoom)
        clampCenterToCoverage()
        invalidate()
    }

    private fun updateCoverageMinimumZoom(viewportWidth: Int, viewportHeight: Int) {
        val bounds = coverageBounds ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        coverageMinZoom = (minZoom..maxZoom).firstOrNull { candidate ->
            lonToWorld(bounds.east, candidate) - lonToWorld(bounds.west, candidate) >= viewportWidth &&
                latToWorld(bounds.south, candidate) - latToWorld(bounds.north, candidate) >= viewportHeight
        } ?: maxZoom
        zoom = zoom.coerceIn(maxOf(minZoom, coverageMinZoom), maxZoom)
    }

    private fun clampCenterToCoverage() {
        val bounds = coverageBounds ?: return
        if (width <= 0 || height <= 0) return
        val west = lonToWorld(bounds.west, zoom)
        val east = lonToWorld(bounds.east, zoom)
        val north = latToWorld(bounds.north, zoom)
        val south = latToWorld(bounds.south, zoom)
        val halfWidth = width / 2.0
        val halfHeight = height / 2.0
        val requestedX = lonToWorld(centerLon, zoom)
        val requestedY = latToWorld(centerLat, zoom)
        val minimumX = west + halfWidth
        val maximumX = east - halfWidth
        val minimumY = north + halfHeight
        val maximumY = south - halfHeight
        centerLon = worldToLon(
            if (minimumX <= maximumX) requestedX.coerceIn(minimumX, maximumX) else (west + east) / 2.0,
            zoom,
        )
        centerLat = worldToLat(
            if (minimumY <= maximumY) requestedY.coerceIn(minimumY, maximumY) else (north + south) / 2.0,
            zoom,
        )
    }
    private fun worldSize() = TILE * 2.0.pow(zoom)
    private fun lonToWorld(lon: Double, z: Int) = (lon + 180.0) / 360.0 * TILE * 2.0.pow(z)
    private fun latToWorld(lat: Double, z: Int): Double {
        val safe = lat.coerceIn(-85.05112878, 85.05112878)
        val radians = Math.toRadians(safe)
        return (1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0 * TILE * 2.0.pow(z)
    }
    private fun worldToLon(x: Double, z: Int) = x / (TILE * 2.0.pow(z)) * 360.0 - 180.0
    private fun worldToLat(y: Double, z: Int): Double {
        val normalized = PI * (1.0 - 2.0 * y / (TILE * 2.0.pow(z)))
        return Math.toDegrees(atan(sinh(normalized)))
    }
    private fun initialZoom(): Int {
        val points = course?.points.orEmpty()
        if (points.isEmpty()) return metadataCenter().third
        val span = maxOf(
            points.maxOf { it.position.longitude } - points.minOf { it.position.longitude },
            points.maxOf { it.position.latitude } - points.minOf { it.position.latitude },
        ).coerceAtLeast(.0001)
        return (ln(360.0 / span) / ln(2.0)).toInt().coerceIn(minZoom, maxZoom)
    }

    private fun metadata(name: String): String? = runCatching {
        database.rawQuery("SELECT value FROM metadata WHERE name=? LIMIT 1", arrayOf(name))
            .use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    private fun metadataInt(name: String) = metadata(name)?.toIntOrNull()
    private fun metadataCenter(): Triple<Double, Double, Int> {
        metadata("center")?.split(',')?.let { parts ->
            if (parts.size >= 2) return Triple(parts[0].toDouble(), parts[1].toDouble(), parts.getOrNull(2)?.toIntOrNull() ?: 10)
        }
        metadata("bounds")?.split(',')?.let { parts ->
            if (parts.size == 4) return Triple((parts[0].toDouble() + parts[2].toDouble()) / 2, (parts[1].toDouble() + parts[3].toDouble()) / 2, 10)
        }
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

    override fun close() {
        worker.shutdownNow()
        cache.evictAll()
        database.close()
    }

    private data class RasterBounds(val south: Double, val west: Double, val north: Double, val east: Double)
    private companion object { const val TILE = 256.0 }
}
