package com.example.earthexplorer.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

/** Public camera position and media endpoints, never an inferred or simulated observation. */
data class WebcamCamera(
    val id: String,
    val title: String,
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val provider: String,
    val sourceUrl: String,
    val imageUrl: String,
    val videoUrl: String? = null,
    /** The provider's published description of what the camera looks at, if any. */
    val view: String? = null,
    /** Estimated compass bearing of the view in degrees (0 = north), never provider telemetry. */
    val headingDegrees: Double? = null,
) {
    fun toFeature(catalogTimeMillis: Long = System.currentTimeMillis()): GlobeFeature = GlobeFeature(
        id = id,
        layer = FeatureLayer.WEBCAMS,
        latitude = latitude,
        longitude = longitude,
        title = title,
        subtitle = "$city · $country",
        observedAtMillis = catalogTimeMillis,
        headingDegrees = headingDegrees ?: 0.0,
        details = listOf(
            FeatureDetail("View", view ?: "Not published"),
            FeatureDetail("Heading", headingDegrees?.let { "%.0f° %s · estimated".format(java.util.Locale.US, it, compassName(it)) }
                ?: "Unknown"),
            FeatureDetail("Feed", if (videoUrl != null) "Recent clip · updated periodically" else "Snapshot · refreshed periodically"),
            FeatureDetail("Location", "$city, $country"),
            FeatureDetail("Camera provider", provider),
            FeatureDetail("Timestamp", "Catalog check, not image capture time; media age appears under the video."),
        ),
        source = if (id.startsWith("tfl-")) "Powered by TfL Open Data" else provider,
        sourceUrl = sourceUrl,
    )
}

internal fun compassName(degrees: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}

/** A rough compass bearing from the provider's view text; null when the text names no direction. */
internal fun estimateHeading(view: String?): Double? {
    val text = view?.uppercase(java.util.Locale.ENGLISH) ?: return null
    for (word in Regex("[A-Z/]+").findAll(text).map { it.value }) when (word) {
        "NORTH", "N/B", "NORTHBOUND" -> return 0.0
        "NORTHEAST", "NE" -> return 45.0
        "EAST", "E/B", "EASTBOUND" -> return 90.0
        "SOUTHEAST", "SE" -> return 135.0
        "SOUTH", "S/B", "SOUTHBOUND" -> return 180.0
        "SOUTHWEST", "SW" -> return 225.0
        "WEST", "W/B", "WESTBOUND" -> return 270.0
        "NORTHWEST", "NW" -> return 315.0
    }
    return null
}

/** Only official public traffic-camera media hosts and path shapes may reach a player. */
object WebcamMediaPolicy {
    private val tflPath = Regex("/jamcams\\.tfl\\.gov\\.uk/[0-9]{5}\\.[0-9]{5}\\.(jpg|mp4)")
    private val austinPath = Regex("/image/[0-9]+\\.jpg")

    fun isAllowedUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawQuery == null && uri.rawFragment == null && when (uri.host) {
                "s3-eu-west-1.amazonaws.com" -> tflPath.matches(uri.rawPath.orEmpty())
                "cctv.austinmobility.io" -> austinPath.matches(uri.rawPath.orEmpty())
                else -> false
            }
    } catch (_: java.net.URISyntaxException) {
        false
    }
}

/** Small examples from the same public catalog providers used by God's Eye View. */
object WebcamLayerStore {
    private const val CACHE_MILLIS = 15 * 60_000L
    private const val MIN_ATTEMPT_MILLIS = 60_000L
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val NOTE = "Big Ben and Austin examples · official camera positions · clips or refreshed snapshots, not continuous live video"
    internal const val TFL_CATALOG_URL = "https://api.tfl.gov.uk/Place/Type/JamCam"
    internal const val AUSTIN_CATALOG_URL = "https://data.austintexas.gov/resource/b4k4-adkb.json?%24limit=2000"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _state = MutableStateFlow(LayerFeed(note = NOTE))
    private val _cameras = MutableStateFlow<List<WebcamCamera>>(emptyList())
    private val connections = mutableSetOf<HttpURLConnection>()
    private var enabled = false
    private var generation = 0L
    private var job: Job? = null
    private var cacheNanos: Long? = null
    private var lastAttemptNanos: Long? = null

    val state: StateFlow<LayerFeed> = _state.asStateFlow()
    val cameras: StateFlow<List<WebcamCamera>> = _cameras.asStateFlow()

    fun find(id: String): WebcamCamera? = _cameras.value.firstOrNull { it.id == id }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            if (!value) {
                generation++
                job?.cancel()
                job = null
                connections.toList().forEach { it.disconnect() }
                connections.clear()
                _state.value = _state.value.copy(loading = false)
                return
            }
            val cacheAge = cacheNanos?.let { (System.nanoTime() - it) / 1_000_000L }
            if (cacheAge != null && cacheAge < CACHE_MILLIS) return
        }
        refresh()
    }

    /** One catalog request per provider; no background polling or media prefetch. */
    fun refresh() {
        val nextJob = synchronized(lock) {
            if (!enabled || job != null) return
            val now = System.nanoTime()
            val elapsed = lastAttemptNanos?.let { (now - it) / 1_000_000L }
            if (elapsed != null && elapsed < MIN_ATTEMPT_MILLIS) {
                _state.value = _state.value.copy(note = "$NOTE · catalog refresh available shortly")
                return
            }
            lastAttemptNanos = now
            val requestGeneration = ++generation
            _state.value = _state.value.copy(loading = true, error = null, note = "Checking public webcam catalogs…")
            scope.launch(start = CoroutineStart.LAZY) { load(requestGeneration) }.also { job = it }
        }
        nextJob.start()
    }

    private suspend fun load(requestGeneration: Long) {
        try {
            val results = supervisorScope {
                listOf(
                    async { fetchCatalog(requestGeneration, "tfl-", "TfL", TFL_CATALOG_URL, WebcamCatalogParser::parseTfl) },
                    async { fetchCatalog(requestGeneration, "austin-", "Austin", AUSTIN_CATALOG_URL, WebcamCatalogParser::parseAustin) },
                ).awaitAll()
            }
            currentCoroutineContext().ensureActive()
            // Selection changes originate on the UI thread. Reconcile there so an old
            // completed request cannot clear a different camera selected concurrently.
            withContext(Dispatchers.Main.immediate) {
                synchronized(lock) {
                    if (!enabled || generation != requestGeneration) return@withContext
                    val now = System.currentTimeMillis()
                    val previous = _cameras.value
                    val cameras = results.flatMap { result ->
                        result.cameras ?: previous.filter { it.id.startsWith(result.prefix) }
                    }
                    val errors = results.mapNotNull { it.error }
                    _cameras.value = cameras
                    _state.value = LayerFeed(
                        features = cameras.map { camera ->
                            val result = results.first { camera.id.startsWith(it.prefix) }
                            val priorFeature = _state.value.features.firstOrNull { it.id == camera.id }
                            if (result.cameras == null && priorFeature != null) priorFeature else camera.toFeature(now)
                        },
                        updatedAtMillis = if (results.any { it.cameras != null }) now else _state.value.updatedAtMillis,
                        error = errors.takeIf { it.isNotEmpty() }?.joinToString(" "),
                        note = NOTE + if (errors.isNotEmpty() && cameras.isNotEmpty()) " · some earlier catalog entries retained" else "",
                    )
                    val selected = WebcamSelection.camera.value
                    val refreshed = refreshedWebcamSelection(selected, cameras,
                        results.filter { it.cameras != null }.map { it.prefix }.toSet())
                    if (refreshed != selected) {
                        if (refreshed == null) WebcamSelection.clear() else WebcamSelection.open(refreshed)
                    }
                    if (errors.isEmpty()) cacheNanos = System.nanoTime()
                }
            }
        } finally {
            synchronized(lock) {
                if (generation == requestGeneration) {
                    job = null
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }

    private suspend fun fetchCatalog(
        requestGeneration: Long,
        prefix: String,
        provider: String,
        endpoint: String,
        parse: (String) -> List<WebcamCamera>,
    ): CatalogResult = try {
        CatalogResult(prefix, parse(download(requestGeneration, endpoint)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        CatalogResult(prefix, error = "$provider camera catalog is unavailable.")
    } catch (_: JSONException) {
        CatalogResult(prefix, error = "$provider returned an unreadable camera catalog.")
    }

    private suspend fun download(requestGeneration: Long, endpoint: String): String {
        val request = URL(endpoint).openConnection() as HttpURLConnection
        request.connectTimeout = 12_000
        request.readTimeout = 12_000
        request.instanceFollowRedirects = false
        request.useCaches = false
        request.setRequestProperty("User-Agent", "EarthExplorer-PICO/1.0")
        request.setRequestProperty("Accept", "application/json")
        synchronized(lock) {
            if (!enabled || generation != requestGeneration) {
                request.disconnect()
                throw CancellationException("Webcam layer disabled")
            }
            connections.add(request)
        }
        try {
            currentCoroutineContext().ensureActive()
            if (request.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Camera catalog unavailable")
            if (request.contentLengthLong > MAX_BYTES) throw IOException("Camera catalog exceeds size limit")
            val output = ByteArrayOutputStream()
            request.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("Camera catalog exceeds size limit")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            request.disconnect()
            synchronized(lock) { connections.remove(request) }
        }
    }

    private data class CatalogResult(val prefix: String, val cameras: List<WebcamCamera>? = null, val error: String? = null)
}

/** Provider failures cannot invalidate a camera; a successful removal can. */
internal fun refreshedWebcamSelection(
    selected: WebcamCamera?,
    cameras: List<WebcamCamera>,
    successfulProviderPrefixes: Set<String>,
): WebcamCamera? {
    if (selected == null || successfulProviderPrefixes.none { selected.id.startsWith(it) }) return selected
    return cameras.firstOrNull { it.id == selected.id }
}

/** Fixed example IDs, but names, availability, media and coordinates come from real catalogs. */
internal object WebcamCatalogParser {
    /** Parliament Square, the JamCam closest to Big Ben (about 120 m from the Elizabeth Tower). */
    private val tflExamples = listOf("00001.06501")
    /** Estimated bearings for cameras whose published view text names no direction. */
    private val tflHeadings = mapOf("00001.06501" to 110.0)
    private val tflViews = mapOf("00001.06501" to "St Margaret's Street, looking towards Big Ben")
    private val austinExamples = listOf("133", "135", "562")

    fun parseTfl(payload: String): List<WebcamCamera> {
        val rows = JSONArray(payload)
        if (rows.length() > 5000) throw IOException("TfL catalog exceeds camera limit")
        val cameras = mutableMapOf<String, WebcamCamera>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rawId = row.optString("id").removePrefix("JamCams_")
            if (rawId !in tflExamples) continue
            val properties = mutableMapOf<String, String>()
            row.optJSONArray("additionalProperties")?.let { values ->
                for (propertyIndex in 0 until values.length()) {
                    val property = values.optJSONObject(propertyIndex) ?: continue
                    properties[property.optString("key")] = property.optString("value")
                }
            }
            if (!properties["available"].equals("true", ignoreCase = true)) continue
            val latitude = row.optDouble("lat", Double.NaN)
            val longitude = row.optDouble("lon", Double.NaN)
            if (latitude !in 51.0..52.0 || longitude !in -1.0..1.0) continue
            val image = properties["imageUrl"].orEmpty()
            if (!isTflMediaUrl(image, rawId, "jpg")) continue
            val video = properties["videoUrl"]?.takeIf { isTflMediaUrl(it, rawId, "mp4") }
            val title = row.optString("commonName").trim().take(160).takeIf { it.isNotEmpty() } ?: continue
            val publishedView = properties["view"]?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
            val view = tflViews[rawId] ?: publishedView
            cameras[rawId] = WebcamCamera(
                id = "tfl-$rawId", title = title, city = "London", country = "United Kingdom",
                latitude = latitude, longitude = longitude, provider = "Transport for London",
                sourceUrl = "https://tfl.gov.uk/traffic/status/", imageUrl = image, videoUrl = video,
                view = view, headingDegrees = tflHeadings[rawId] ?: estimateHeading(publishedView),
            )
        }
        return tflExamples.mapNotNull(cameras::get)
    }

    fun parseAustin(payload: String): List<WebcamCamera> {
        val rows = JSONArray(payload)
        if (rows.length() > 5000) throw IOException("Austin catalog exceeds camera limit")
        val cameras = mutableMapOf<String, WebcamCamera>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rawId = row.optString("camera_id")
            if (rawId !in austinExamples || row.optString("camera_status") != "TURNED_ON") continue
            val location = row.optJSONObject("location") ?: continue
            if (location.optString("type") != "Point") continue
            val coordinates = location.optJSONArray("coordinates") ?: continue
            val longitude = coordinates.optDouble(0, Double.NaN)
            val latitude = coordinates.optDouble(1, Double.NaN)
            if (latitude !in 29.0..31.0 || longitude !in -99.0..-96.0) continue
            val image = row.optString("screenshot_address")
            if (!isPinnedMediaUrl(image, "cctv.austinmobility.io", "/image/$rawId.jpg")) continue
            val title = row.optString("location_name").trim().take(160).takeIf { it.isNotEmpty() } ?: continue
            cameras[rawId] = WebcamCamera(
                id = "austin-$rawId", title = title, city = "Austin", country = "United States",
                latitude = latitude, longitude = longitude, provider = "Austin Transportation & Public Works",
                sourceUrl = "https://data.austintexas.gov/Transportation-and-Mobility/Traffic-Cameras/b4k4-adkb",
                imageUrl = image, view = title, headingDegrees = estimateHeading(title),
            )
        }
        return austinExamples.mapNotNull(cameras::get)
    }

    private fun isTflMediaUrl(url: String, id: String, extension: String): Boolean =
        isPinnedMediaUrl(url, "s3-eu-west-1.amazonaws.com", "/jamcams.tfl.gov.uk/$id.$extension")

    private fun isPinnedMediaUrl(value: String, host: String, path: String): Boolean = try {
        val uri = URI(value)
        WebcamMediaPolicy.isAllowedUrl(value) && uri.host == host && uri.rawPath == path
    } catch (_: java.net.URISyntaxException) {
        false
    }
}
