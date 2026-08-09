package com.watchnav.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.watchnav.packages.MapDownloadRegion
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

enum class MapLayerKind(
    val attribution: String,
    val maximumPreviewZoom: Int,
) {
    NORMAL("© OpenStreetMap contributors", 19),
    CONTOUR("© OpenStreetMap contributors, SRTM · OpenTopoMap CC-BY-SA", 17),
    SATELLITE("Tiles © Esri and imagery providers", 19),
}

class MapRegionSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val executor = Executors.newFixedThreadPool(2)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val memoryCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val cacheDirectory = File(context.cacheDir, "map-preview-tiles")
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                region = region.resized(detector.scaleFactor)
                fitPreviewToSelection()
                onRegionChanged?.invoke(region)
                invalidate()
                return true
            }
        },
    )

    var onRegionChanged: ((MapDownloadRegion) -> Unit)? = null
    var region = MapDownloadRegion(0.0, 0.0, 20.0)
        private set
    var layer = MapLayerKind.NORMAL
        private set
    private var previewZoom = 2

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        cacheDirectory.mkdirs()
    }

    fun centerOn(latitude: Double, longitude: Double) {
        region = MapDownloadRegion(latitude, longitude, region.radiusKilometers)
        fitPreviewToSelection()
        onRegionChanged?.invoke(region)
        invalidate()
    }

    fun setLayer(value: MapLayerKind) {
        if (layer == value) return
        layer = value
        previewZoom = previewZoom.coerceAtMost(value.maximumPreviewZoom)
        invalidate()
    }

    fun resizeSelection(factor: Float) {
        region = region.resized(factor)
        fitPreviewToSelection()
        onRegionChanged?.invoke(region)
        invalidate()
    }

    fun handleRotaryScroll(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL ||
            event.source and InputDevice.SOURCE_ROTARY_ENCODER != InputDevice.SOURCE_ROTARY_ENCODER
        ) return false
        val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (scroll == 0f) return false

        // Clockwise/positive is zoom-in: choose a smaller package around the same center.
        resizeSelection(if (scroll > 0f) ROTARY_ZOOM_FACTOR_IN else ROTARY_ZOOM_FACTOR_OUT)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        handleRotaryScroll(event) || super.onGenericMotionEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1)
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fitPreviewToSelection()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(layerBackground())
        drawTiles(canvas)
        drawSelection(canvas)
        drawAttribution(canvas)
    }

    override fun onDetachedFromWindow() {
        executor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun drawTiles(canvas: Canvas) {
        val zoom = previewZoom.coerceIn(1, layer.maximumPreviewZoom)
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val centerX = longitudeToWorldX(region.centerLongitude, scale)
        val centerY = latitudeToWorldY(region.centerLatitude, scale)
        val left = centerX - width / 2.0
        val top = centerY - height / 2.0
        val firstX = floor(left / TILE_SIZE).toInt()
        val lastX = floor((left + width) / TILE_SIZE).toInt()
        val firstY = floor(top / TILE_SIZE).toInt()
        val lastY = floor((top + height) / TILE_SIZE).toInt()
        val tileCount = 1 shl zoom

        for (rawX in firstX..lastX) {
            val x = ((rawX % tileCount) + tileCount) % tileCount
            for (y in firstY..lastY) {
                if (y !in 0 until tileCount) continue
                val key = "${layer.name}/$zoom/$x/$y"
                val bitmap = synchronized(memoryCache) { memoryCache.get(key) }
                val drawLeft = (rawX * TILE_SIZE - left).toFloat()
                val drawTop = (y * TILE_SIZE - top).toFloat()
                if (bitmap != null) {
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(drawLeft, drawTop, drawLeft + TILE_SIZE, drawTop + TILE_SIZE),
                        bitmapPaint,
                    )
                } else {
                    drawTilePlaceholder(canvas, drawLeft, drawTop)
                    loadTile(key, zoom, x, y)
                }
            }
        }
    }

    private fun fitPreviewToSelection() {
        if (width <= 0 || height <= 0) return
        val bounds = region.bounds
        val worldSize = TILE_SIZE.toDouble()
        val selectionWidth = kotlin.math.abs(
            longitudeToWorldX(bounds.east, worldSize) - longitudeToWorldX(bounds.west, worldSize),
        ).coerceAtLeast(0.000001)
        val selectionHeight = kotlin.math.abs(
            latitudeToWorldY(bounds.south, worldSize) - latitudeToWorldY(bounds.north, worldSize),
        ).coerceAtLeast(0.000001)
        val availableWidth = width * SELECTION_VIEWPORT_FRACTION
        val availableHeight = height * SELECTION_VIEWPORT_FRACTION
        val scale = min(availableWidth / selectionWidth, availableHeight / selectionHeight)
        previewZoom = floor(ln(scale) / ln(2.0)).toInt()
            .coerceIn(1, layer.maximumPreviewZoom)
    }

    private fun drawSelection(canvas: Canvas) {
        val bounds = region.bounds
        val zoom = previewZoom.coerceIn(1, layer.maximumPreviewZoom)
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val centerX = longitudeToWorldX(region.centerLongitude, scale)
        val centerY = latitudeToWorldY(region.centerLatitude, scale)
        val screenLeft = longitudeToWorldX(bounds.west, scale) - centerX + width / 2.0
        val screenRight = longitudeToWorldX(bounds.east, scale) - centerX + width / 2.0
        val screenTop = latitudeToWorldY(bounds.north, scale) - centerY + height / 2.0
        val screenBottom = latitudeToWorldY(bounds.south, scale) - centerY + height / 2.0

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(42, 255, 184, 77)
        canvas.drawRect(
            screenLeft.toFloat(),
            screenTop.toFloat(),
            screenRight.toFloat(),
            screenBottom.toFloat(),
            paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.rgb(255, 184, 77)
        canvas.drawRect(
            screenLeft.toFloat(),
            screenTop.toFloat(),
            screenRight.toFloat(),
            screenBottom.toFloat(),
            paint,
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(width / 2f, height / 2f, 6f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.BLACK
        canvas.drawCircle(width / 2f, height / 2f, 7f, paint)
    }

    private fun drawAttribution(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 0, 0, 0)
        canvas.drawRect(0f, height - 23f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.textSize = 10f
        canvas.drawText(layer.attribution, 6f, height - 7f, paint)
    }

    private fun drawTilePlaceholder(canvas: Canvas, left: Float, top: Float) {
        paint.style = Paint.Style.FILL
        paint.color = layerBackground()
        canvas.drawRect(left, top, left + TILE_SIZE, top + TILE_SIZE, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(80, 255, 255, 255)
        canvas.drawRect(left, top, left + TILE_SIZE, top + TILE_SIZE, paint)
    }

    private fun loadTile(key: String, zoom: Int, x: Int, y: Int) {
        if (!loading.add(key)) return
        executor.execute {
            try {
                val file = File(cacheDirectory, "$key.tile")
                val bitmap = if (file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MILLIS) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    downloadTile(tileUrl(layer, zoom, x, y), file)
                }
                if (bitmap != null) {
                    synchronized(memoryCache) { memoryCache.put(key, bitmap) }
                    postInvalidateOnAnimation()
                }
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun downloadTile(initialUrl: URL, destination: File): Bitmap? {
        var url = initialUrl
        repeat(4) {
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "WatchNav/0.9 (interactive map preview)")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAXIMUM_TILE_BYTES) return null
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { it.write(bytes) }
                        return bitmap
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                    -> {
                        val next = URL(url, connection.getHeaderField("Location") ?: return null)
                        if (next.protocol != "https" || next.userInfo != null) return null
                        url = next
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun tileUrl(layer: MapLayerKind, zoom: Int, x: Int, y: Int): URL = when (layer) {
        MapLayerKind.NORMAL -> URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
        MapLayerKind.CONTOUR -> URL("https://tile.opentopomap.org/$zoom/$x/$y.png")
        MapLayerKind.SATELLITE -> URL(
            "https://services.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/$zoom/$y/$x",
        )
    }

    private fun layerBackground(): Int = when (layer) {
        MapLayerKind.NORMAL -> Color.rgb(224, 232, 224)
        MapLayerKind.CONTOUR -> Color.rgb(226, 218, 188)
        MapLayerKind.SATELLITE -> Color.rgb(24, 36, 42)
    }

    private fun longitudeToWorldX(longitude: Double, scale: Double): Double =
        (longitude + 180.0) / 360.0 * scale

    private fun latitudeToWorldY(latitude: Double, scale: Double): Double {
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val radians = clamped * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * scale
    }

    private companion object {
        const val TILE_SIZE = 256f
        const val MAXIMUM_TILE_BYTES = 1024 * 1024
        const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val ROTARY_ZOOM_FACTOR_IN = 0.82f
        const val ROTARY_ZOOM_FACTOR_OUT = 1.22f
        const val SELECTION_VIEWPORT_FRACTION = 0.68
    }
}
