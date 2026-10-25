package com.watchnav.brouter

import android.content.Context
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import com.watchnav.routing.OfflineRouter
import com.watchnav.routing.RouteRequest
import com.watchnav.routing.RouteResult
import com.watchnav.routing.RoutingProfile
import java.io.File
import java.io.InputStream
import kotlin.math.floor
import kotlin.system.measureTimeMillis

class BRouterOfflineRouter(context: Context) : OfflineRouter {
    private val applicationContext = context.applicationContext
    private val routingDirectory = File(applicationContext.filesDir, "routing")
    private val segmentDirectory = File(routingDirectory, "segments4")
    private val profileDirectory = File(routingDirectory, "profiles2")

    override suspend fun route(request: RouteRequest): RouteResult {
        val requiredRegions = requiredRegions(request)
        val missing = requiredRegions.filterNotTo(linkedSetOf()) { regionFile(it).isFile }
        if (missing.isNotEmpty()) return RouteResult.MissingRegion(missing)

        return runCatching {
            ensureRoutingAssets(request.profile)
            lateinit var result: RouteResult
            val elapsed = measureTimeMillis {
                result = runEngine(request)
                if (result is RouteResult.Failure && shouldRetryWithWideWaypointSearch(result)) {
                    result = runEngine(request, WIDE_WAYPOINT_CATCHING_RANGE_METERS)
                }
            }
            if (result is RouteResult.Success) {
                (result as RouteResult.Success).copy(calculationMillis = elapsed)
            } else {
                result
            }
        }.getOrElse { error ->
            RouteResult.Failure(
                reason = error.message ?: error.javaClass.simpleName,
                recoverable = true,
            )
        }
    }

    override suspend fun nearestRoutablePoint(
        point: GeoPoint,
        profile: RoutingProfile,
    ): GeoPoint? = point.takeIf { regionFile(regionId(it)).isFile }

    fun segmentDirectory(): File = segmentDirectory

    fun installedRegionIds(): Set<String> = segmentDirectory.listFiles()
        ?.filter { it.isFile && it.extension.equals("rd5", ignoreCase = true) }
        ?.mapTo(linkedSetOf()) { it.nameWithoutExtension }
        ?: emptySet()

    fun regionFile(regionId: String): File = File(segmentDirectory, "$regionId.rd5")

    fun importRegion(regionId: String, input: InputStream): File {
        require(regionId.matches(Regex("[EW]\\d{1,3}_[NS]\\d{1,2}"))) {
            "Routing filename must look like E120_N30.rd5"
        }
        segmentDirectory.mkdirs()
        val target = regionFile(regionId)
        val temporary = File(segmentDirectory, "$regionId.rd5.tmp")
        var total = 0L
        temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAXIMUM_REGION_BYTES) { "Routing region is larger than 512 MB" }
                output.write(buffer, 0, count)
            }
        }
        require(total > 0) { "Routing region is empty" }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete()) { "Unable to finish importing routing region" }
        }
        return target
    }

    private fun requiredRegions(request: RouteRequest): Set<String> = buildSet {
        add(regionId(request.origin))
        request.via.forEach { add(regionId(it)) }
        add(regionId(request.destination))
    }

    private fun waypoints(request: RouteRequest): List<OsmNodeNamed> =
        (listOf(request.origin) + request.via + request.destination).mapIndexed { index, point ->
            OsmNodeNamed().apply {
                ilon = LONGITUDE_OFFSET + (point.longitude * COORDINATE_SCALE).toInt()
                ilat = LATITUDE_OFFSET + (point.latitude * COORDINATE_SCALE).toInt()
                name = if (index == 0) "from" else if (index == request.via.size + 1) "to" else "via$index"
            }
        }

    private fun ensureRoutingAssets(profile: RoutingProfile) {
        profileDirectory.mkdirs()
        copyAssetIfMissing(LOOKUPS_ASSET, File(profileDirectory, LOOKUPS_ASSET))
        installProfile(profile)
    }

    private fun installProfile(profile: RoutingProfile) {
        val assetName = profileAsset(profile)
        val source = applicationContext.assets.open(assetName).bufferedReader().use { it.readText() }
        val configured = configureProfile(source, profile)
        val destination = profileFile(profile)
        if (!destination.isFile || destination.readText() != configured) {
            destination.writeText(configured)
        }
    }

    private fun runEngine(request: RouteRequest, waypointCatchingRangeMeters: Double? = null): RouteResult {
        val routingContext = RoutingContext().apply {
            localFunction = profileFile(request.profile).absolutePath
            memoryclass = MEMORY_CLASS_MB
        }
        val engine = RoutingEngine(
            null,
            null,
            segmentDirectory,
            waypoints(request),
            routingContext,
        ).apply {
            quite = true
            if (waypointCatchingRangeMeters != null) {
                routingContext.waypointCatchingRange = waypointCatchingRangeMeters
            }
        }
        engine.doRun(MAXIMUM_ROUTING_MILLIS)
        val error = engine.errorMessage
        val track = engine.foundTrack
        if (error != null) return RouteResult.Failure(error, recoverable = true)
        if (track == null || track.nodes.size < 2) {
            return RouteResult.Failure("BRouter returned no route", recoverable = true)
        }
        val points = track.nodes.map { node ->
            CoursePoint(
                GeoPoint(
                    latitude = (node.iLat - LATITUDE_OFFSET) / COORDINATE_SCALE,
                    longitude = (node.iLon - LONGITUDE_OFFSET) / COORDINATE_SCALE,
                    elevationMeters = node.sElev.takeUnless { it == Short.MIN_VALUE }?.let { it / 4.0 },
                ),
            )
        }
        return RouteResult.Success(Course("Offline route", points), calculationMillis = 0L)
    }

    private fun shouldRetryWithWideWaypointSearch(result: RouteResult.Failure): Boolean {
        val reason = result.reason.lowercase()
        return reason.contains("position not mapped") ||
            reason.contains("no track found") ||
            reason.contains("island detected") ||
            reason.contains("returned no route")
    }

    private fun copyAssetIfMissing(assetName: String, destination: File) {
        if (destination.isFile) return
        applicationContext.assets.open(assetName).use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }

    private fun profileFile(profile: RoutingProfile): File =
        File(profileDirectory, profileAsset(profile))

    private fun profileAsset(profile: RoutingProfile): String = when (profile) {
        RoutingProfile.HIKE -> "hiking-mountain.brf"
        RoutingProfile.RUN -> "shortest.brf"
        RoutingProfile.BIKE -> "trekking.brf"
    }

    companion object {
        private const val COORDINATE_SCALE = 1_000_000.0
        private const val LONGITUDE_OFFSET = 180_000_000
        private const val LATITUDE_OFFSET = 90_000_000
        private const val MAXIMUM_ROUTING_MILLIS = 45_000L
        private const val MEMORY_CLASS_MB = 64
        private const val WIDE_WAYPOINT_CATCHING_RANGE_METERS = 1_000.0
        private const val MAXIMUM_REGION_BYTES = 512L * 1024 * 1024
        private const val LOOKUPS_ASSET = "lookups.dat"

        fun regionId(point: GeoPoint): String {
            val west = floor(point.longitude / 5.0).toInt() * 5
            val south = floor(point.latitude / 5.0).toInt() * 5
            return "${if (west < 0) 'W' else 'E'}${kotlin.math.abs(west)}_" +
                "${if (south < 0) 'S' else 'N'}${kotlin.math.abs(south)}"
        }
    }
}

/** Applies WatchNav's bounded mountain-routing policy to the bundled BRouter profile. */
internal fun configureProfile(source: String, profile: RoutingProfile): String =
    if (profile == RoutingProfile.HIKE) {
        source
            // Terrain is a route cost, not merely post-route decoration. This activates the
            // profile's uphill/downhill penalties while retaining BRouter's bounded memory.
            .replace(
                Regex("assign\\s+consider_elevation\\s*=\\s*false"),
                "assign   consider_elevation     = true",
            )
            .replace(
                Regex("assign\\s+hiking_routes_preference\\s+0\\.20"),
                "assign   hiking_routes_preference 0.35",
            )
    } else {
        source
    }
