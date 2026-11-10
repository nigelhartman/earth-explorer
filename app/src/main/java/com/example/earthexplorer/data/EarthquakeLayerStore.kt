package com.example.earthexplorer.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/** USGS event observations; the feed is not an earthquake or tsunami warning service. */
object EarthquakeLayerStore {
    const val SOURCE_URL = "https://earthquake.usgs.gov/fdsnws/event/1/"
    private const val POLL_MILLIS = 60 * 60_000L
    private const val MIN_REFRESH_MILLIS = 60_000L
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val NOTE = "USGS · M5.4+ · past 12 months · up to 600 events · color indicates depth"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LayerFeed(note = NOTE))
    private var job: Job? = null
    private var enabled = false
    private var version = 0L
    private var lastAttemptNanos: Long? = null

    /** The catalog plus an optional presentation scenario, which is never written back to the catalog. */
    private val scenario = MutableStateFlow<GlobeFeature?>(null)
    val state: StateFlow<LayerFeed> = combine(_state, scenario) { feed, extra ->
        if (extra == null) feed else feed.copy(features = listOf(extra) + feed.features.filter { it.id != extra.id })
    }.stateIn(scope, SharingStarted.Eagerly, _state.value)

    fun setScenario(feature: GlobeFeature?) { scenario.value = feature }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        ++version
        job?.cancel()
        job = null
        if (enabled) {
            _state.value = _state.value.copy(loading = _state.value.features.isEmpty())
            startPolling()
        } else _state.value = _state.value.copy(loading = false)
    }

    @Synchronized
    fun refresh() {
        if (!enabled) return
        ++version
        job?.cancel()
        startPolling(force = true)
    }

    /** The reference Ring of Fire case uses this historical catalog, not a live warning feed. */
    fun queryUrl(nowMillis: Long): String {
        val start = Instant.ofEpochMilli(EarthquakeFeedParser.windowStartMillis(nowMillis))
            .atZone(ZoneOffset.UTC).toLocalDate()
        return "${SOURCE_URL}query?format=geojson&starttime=$start&minmagnitude=5.4&limit=600&orderby=time"
    }

    private fun startPolling(force: Boolean = false) {
        val requestVersion = version
        job = scope.launch {
            val waitMillis = synchronized(this@EarthquakeLayerStore) {
                val elapsed = lastAttemptNanos?.let { (System.nanoTime() - it) / 1_000_000L }
                val interval = if (force || _state.value.error != null || _state.value.updatedAtMillis == null)
                    MIN_REFRESH_MILLIS else POLL_MILLIS
                if (elapsed == null) 0L else (interval - elapsed).coerceAtLeast(0L)
            }
            delay(waitMillis)
            while (isActive) {
                synchronized(this@EarthquakeLayerStore) {
                    if (requestVersion != version || !enabled) return@launch
                    lastAttemptNanos = System.nanoTime()
                }
                publish(requestVersion) { it.copy(loading = true, error = null) }
                try {
                    val feed = EarthquakeFeedParser.parse(fetch(), System.currentTimeMillis())
                    currentCoroutineContext().ensureActive()
                    publish(requestVersion) { feed.copy(note = NOTE) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    reportFailure(requestVersion, "USGS is unavailable. The last successful update is shown.")
                } catch (_: JSONException) {
                    reportFailure(requestVersion, "USGS returned an unreadable feed. The last update is shown.")
                }
                delay(POLL_MILLIS)
            }
        }
    }

    private fun reportFailure(requestVersion: Long, message: String) {
        val oldest = EarthquakeFeedParser.windowStartMillis(System.currentTimeMillis())
        publish(requestVersion) {
            it.copy(loading = false, features = it.features.filter { feature -> feature.observedAtMillis >= oldest },
                error = if (it.updatedAtMillis == null) "USGS is unavailable. No earthquake observations loaded." else message)
        }
    }

    @Synchronized
    private fun publish(requestVersion: Long, update: (LayerFeed) -> LayerFeed) {
        if (enabled && requestVersion == version) _state.value = update(_state.value)
    }

    private suspend fun fetch(): String {
        val connection = URL(queryUrl(System.currentTimeMillis())).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/geo+json, application/json")
            connection.setRequestProperty("User-Agent", "EarthExplorer-PICO/1.0")
            currentCoroutineContext().ensureActive()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("USGS HTTP response unavailable")
            if (connection.contentLengthLong > MAX_BYTES) throw IOException("USGS feed exceeds size limit")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw IOException("USGS feed exceeds size limit")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}

/** Kept separate from polling so recorded GeoJSON can exercise the actual conversion. */
object EarthquakeFeedParser {
    private val utcFormat = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss 'UTC'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC)

    fun windowStartMillis(nowMillis: Long): Long = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneOffset.UTC).toLocalDate().minusYears(1).atStartOfDay(ZoneOffset.UTC)
        .toInstant().toEpochMilli()

    fun parse(json: String, nowMillis: Long): LayerFeed {
        val document = JSONObject(json)
        if (document.optString("type") != "FeatureCollection") throw JSONException("Expected USGS FeatureCollection")
        val items = document.getJSONArray("features")
        val features = ArrayList<GlobeFeature>()
        val oldest = windowStartMillis(nowMillis)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val properties = item.optJSONObject("properties") ?: continue
            if (properties.optString("type") != "earthquake" || properties.optString("status") == "deleted") continue
            val geometry = item.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 3) continue
            val longitude = coordinates.optDouble(0, Double.NaN)
            val latitude = coordinates.optDouble(1, Double.NaN)
            val depth = coordinates.optDouble(2, Double.NaN)
            val magnitude = properties.optDouble("mag", Double.NaN)
            val time = properties.optLong("time", 0L)
            val id = item.optString("id").trim()
            if (id.isEmpty() || id == "null" || !latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 || !magnitude.isFinite() ||
                magnitude !in 5.4..10.0 || time <= 0L || time < oldest ||
                time > nowMillis + 5 * 60_000L) continue
            val place = properties.optString("place").takeUnless { it.isBlank() || it == "null" }
                ?: "Earthquake epicenter"
            val magnitudeText = String.format(Locale.ROOT, "%.1f", magnitude)
            val details = buildList {
                val magnitudeType = properties.optString("magType").takeUnless { it.isBlank() || it == "null" }
                add(FeatureDetail("Magnitude", "$magnitudeText${magnitudeType?.let { " $it" } ?: ""}"))
                add(FeatureDetail("Depth", if (depth.isFinite()) String.format(Locale.ROOT, "%.1f km", depth) else "Not reported"))
                add(FeatureDetail("Occurred", utcFormat.format(Instant.ofEpochMilli(time))))
                add(FeatureDetail("Review", when (properties.optString("status")) {
                    "reviewed" -> "Reviewed by USGS / contributing network"
                    "automatic" -> "Automatic · not yet reviewed"
                    else -> "Not reported"
                }))
                if (properties.optInt("tsunami", 0) == 1) {
                    add(FeatureDetail("Tsunami flag", "USGS ocean-event flag; not a tsunami warning"))
                    add(FeatureDetail("Official alerts", "tsunami.gov"))
                }
                add(FeatureDetail("Event ID", id))
            }
            val sourceUrl = properties.optString("url").takeIf {
                it.startsWith("https://earthquake.usgs.gov/")
            }
            features += GlobeFeature(
                id = "earthquake:$id", layer = FeatureLayer.EARTHQUAKES,
                latitude = latitude, longitude = longitude, title = "M $magnitudeText · $place",
                subtitle = "Earthquake · ${utcFormat.format(Instant.ofEpochMilli(time))}",
                observedAtMillis = time, details = details, source = "USGS Earthquake Hazards Program",
                sourceUrl = sourceUrl, magnitude = magnitude,
                depthKilometers = depth.takeIf { it.isFinite() },
            )
        }
        val generated = document.optJSONObject("metadata")?.optLong("generated", nowMillis)
            ?.takeIf { it in 1L..(nowMillis + 5 * 60_000L) } ?: nowMillis
        return LayerFeed(
            features = features.distinctBy { it.id }.sortedByDescending { it.observedAtMillis }.take(600),
            updatedAtMillis = generated,
            error = if (generated < nowMillis - 60 * 60_000L) "USGS feed is delayed; showing its last published observations." else null,
        )
    }
}
