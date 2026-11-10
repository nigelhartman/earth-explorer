package com.example.earthexplorer.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** OpenSky snapshots only: enabling or pressing refresh spends one global request. */
object AircraftLayerStore {
    private const val ENDPOINT = "https://opensky-network.org/api/states/all"
    private const val MAX_RESPONSE_BYTES = 20 * 1024 * 1024
    private const val SNAPSHOT_NOTE = "OpenSky snapshot · manual refresh only · 4 credits per refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val requestWindow = AircraftRequestWindow()
    private val _state = MutableStateFlow(LayerFeed(note = SNAPSHOT_NOTE))
    val state: StateFlow<LayerFeed> = _state.asStateFlow()
    private var enabled = false
    private var generation = 0L
    private var job: Job? = null
    private var connection: HttpURLConnection? = null

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            if (!value) {
                generation++
                job?.cancel()
                job = null
                connection?.disconnect()
                connection = null
                _state.value = _state.value.copy(loading = false)
                return
            }
        }
        refresh()
    }

    fun refresh() {
        val nextJob = synchronized(lock) {
            if (!enabled || job?.isActive == true) return
            val now = monotonicMillis()
            val remaining = requestWindow.remainingMillis(now)
            if (!requestWindow.claim(now)) {
                _state.value = _state.value.copy(
                    note = "$SNAPSHOT_NOTE · refresh available in ${ceil(remaining / 1000.0).toInt()} s",
                )
                return
            }
            val requestGeneration = ++generation
            _state.value = _state.value.copy(loading = true, error = null, note = "Fetching OpenSky snapshot…")
            scope.launch(start = CoroutineStart.LAZY) { fetch(requestGeneration) }.also { job = it }
        }
        nextJob.start()
    }

    private suspend fun fetch(requestGeneration: Long) {
        try {
            val payload = download(requestGeneration)
            currentCoroutineContext().ensureActive()
            val snapshot = AircraftSnapshotParser.parse(payload)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!enabled || generation != requestGeneration) return
                val ageSeconds = ((System.currentTimeMillis() - snapshot.timeMillis) / 1000).coerceAtLeast(0)
                val ageNote = if (ageSeconds > 120) " · provider snapshot is ${ageSeconds / 60} min old" else ""
                _state.value = LayerFeed(
                    features = snapshot.features,
                    updatedAtMillis = snapshot.timeMillis,
                    note = "$SNAPSHOT_NOTE$ageNote" +
                        if (snapshot.features.isEmpty()) " · no recent positions in this response" else "",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limited: AircraftRateLimitException) {
            synchronized(lock) {
                requestWindow.defer(monotonicMillis(), limited.retrySeconds * 1000)
            }
            fail(requestGeneration, "OpenSky request limit reached. Try again in ${ceil(limited.retrySeconds / 60.0).toInt()} min.")
        } catch (_: JSONException) {
            fail(requestGeneration, "OpenSky returned an unreadable snapshot. Try refreshing later.")
        } catch (failure: IOException) {
            fail(requestGeneration, failure.message ?: "OpenSky is unavailable. Try refreshing later.")
        } finally {
            synchronized(lock) {
                if (generation == requestGeneration) {
                    job = null
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }

    private fun fail(requestGeneration: Long, message: String) = synchronized(lock) {
        if (enabled && generation == requestGeneration) {
            _state.value = _state.value.copy(
                loading = false, error = message,
                note = if (_state.value.features.isEmpty()) SNAPSHOT_NOTE else "$SNAPSHOT_NOTE · previous snapshot retained",
            )
        }
    }

    private suspend fun download(requestGeneration: Long): String {
        val request = URL(ENDPOINT).openConnection() as HttpURLConnection
        request.connectTimeout = 15_000
        request.readTimeout = 20_000
        request.instanceFollowRedirects = false
        request.useCaches = false
        request.setRequestProperty("User-Agent", "EarthExplorer-PICO/1.0")
        request.setRequestProperty("Accept", "application/json")
        synchronized(lock) {
            if (!enabled || generation != requestGeneration) {
                request.disconnect()
                throw CancellationException("Aircraft layer disabled")
            }
            connection = request
        }
        try {
            currentCoroutineContext().ensureActive()
            val status = request.responseCode
            if (status == 429) {
                val retrySeconds = request.getHeaderField("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull()
                    ?: request.getHeaderField("Retry-After")?.toLongOrNull() ?: 300L
                throw AircraftRateLimitException(retrySeconds.coerceIn(60, 86_400))
            }
            if (status == 401 || status == 403) {
                throw IOException("OpenSky refused anonymous access. No other aircraft source is used.")
            }
            if (status !in 200..299) throw IOException("OpenSky is unavailable (HTTP $status). Try refreshing later.")
            if (request.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("OpenSky snapshot exceeds the safe response size.")
            return request.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw IOException("OpenSky snapshot exceeds the safe response size.")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } finally {
            request.disconnect()
            synchronized(lock) { if (connection === request) connection = null }
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
}

private class AircraftRateLimitException(val retrySeconds: Long) : IOException()

/** Monotonic clock gate; neither toggles nor failed requests bypass the credit guard. */
internal class AircraftRequestWindow {
    private var nextAllowedMillis = Long.MIN_VALUE

    fun remainingMillis(now: Long): Long = if (now >= nextAllowedMillis) 0 else nextAllowedMillis - now

    fun claim(now: Long): Boolean {
        if (remainingMillis(now) > 0) return false
        nextAllowedMillis = now + 60_000
        return true
    }

    fun defer(now: Long, durationMillis: Long) {
        nextAllowedMillis = maxOf(nextAllowedMillis, now + durationMillis.coerceAtLeast(60_000))
    }
}

internal data class AircraftSnapshot(val timeMillis: Long, val features: List<GlobeFeature>)

/** OpenSky positional-array mapping, retained from Flight Tracker without other providers. */
internal object AircraftSnapshotParser {
    fun parse(payload: String): AircraftSnapshot {
        val root = JSONObject(payload)
        val seconds = root.optLong("time", 0)
        if (seconds !in 1..100_000_000_000L) throw IOException("OpenSky snapshot has no valid observation time.")
        val rows = root.optJSONArray("states") ?: return AircraftSnapshot(seconds * 1000, emptyList())
        if (rows.length() > 50_000) throw IOException("OpenSky returned too many state vectors.")
        val features = LinkedHashMap<String, GlobeFeature>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val aircraft = AircraftObservation(
                icao24 = row.text(0).orEmpty(), callsign = row.text(1), country = row.text(2),
                positionTimeSeconds = row.number(3)?.toLong(), lastContactSeconds = row.number(4)?.toLong(),
                longitude = row.number(5), latitude = row.number(6), barometricAltitude = row.number(7),
                onGround = if (row.isNull(8)) null else row.optBoolean(8), speedMetersPerSecond = row.number(9),
                trackDegrees = row.number(10), verticalMetersPerSecond = row.number(11), geometricAltitude = row.number(13),
                squawk = row.text(14), positionSource = row.number(16)?.toInt(),
            ).feature(seconds) ?: continue
            val previous = features[aircraft.id]
            if (previous == null || aircraft.observedAtMillis > previous.observedAtMillis) features[aircraft.id] = aircraft
        }
        return AircraftSnapshot(seconds * 1000, features.values.toList())
    }

    private fun JSONArray.number(index: Int): Double? =
        if (isNull(index)) null else optDouble(index).takeIf { it.isFinite() }

    private fun JSONArray.text(index: Int): String? =
        if (isNull(index)) null else optString(index).trim().takeIf { it.isNotEmpty() }?.take(100)
}

internal data class AircraftObservation(
    val icao24: String,
    val callsign: String? = null,
    val country: String? = null,
    val positionTimeSeconds: Long? = null,
    val lastContactSeconds: Long? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val barometricAltitude: Double? = null,
    val onGround: Boolean? = null,
    val speedMetersPerSecond: Double? = null,
    val trackDegrees: Double? = null,
    val verticalMetersPerSecond: Double? = null,
    val geometricAltitude: Double? = null,
    val squawk: String? = null,
    val positionSource: Int? = null,
) {
    fun feature(snapshotTimeSeconds: Long): GlobeFeature? {
        val hex = icao24.lowercase(Locale.ROOT)
        if (hex.length != 6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val positionTime = positionTimeSeconds ?: return null
        if (positionTime <= 0 || positionTime < snapshotTimeSeconds - 120 || positionTime > snapshotTimeSeconds + 30) return null
        val barometric = barometricAltitude?.takeIf { it.isFinite() && it in -500.0..60_000.0 }
        val geometric = geometricAltitude?.takeIf { it.isFinite() && it in -500.0..60_000.0 }
        val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it in 0.0..1500.0 }
        val heading = trackDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }
        val vertical = verticalMetersPerSecond?.takeIf { it.isFinite() && it in -500.0..500.0 }
        val flightCallsign = callsign?.trim()?.takeIf { it.isNotEmpty() }?.take(16)
        val details = buildList {
            add(FeatureDetail("ICAO24", hex.uppercase(Locale.ROOT)))
            flightCallsign?.let { add(FeatureDetail("Callsign", it)) }
            country?.let { add(FeatureDetail("ICAO country", it)) }
            add(FeatureDetail("Position observed", utcTime(positionTime)))
            lastContactSeconds?.takeIf { it > 0 && it <= snapshotTimeSeconds + 30 }?.let {
                add(FeatureDetail("Last contact", utcTime(it)))
            }
            add(FeatureDetail("Status", when (onGround) { true -> "On ground"; false -> "Airborne"; null -> "Unknown" }))
            geometric?.let { add(FeatureDetail("Geometric altitude", "${it.roundToInt()} m")) }
            barometric?.let { add(FeatureDetail("Barometric altitude", "${it.roundToInt()} m / ${(it * 3.28084).roundToInt()} ft")) }
            speed?.let { add(FeatureDetail("Ground speed", "${(it * 1.94384).roundToInt()} kt / ${(it * 3.6).roundToInt()} km/h")) }
            heading?.let { add(FeatureDetail("Ground track", "${it.roundToInt()}°")) }
            vertical?.let { add(FeatureDetail("Vertical speed", "${(it * 196.8504).roundToInt()} ft/min")) }
            squawk?.let { add(FeatureDetail("Squawk", it)) }
            positionSource?.let {
                add(FeatureDetail("Position source", when (it) { 0 -> "ADS-B"; 1 -> "ASTERIX"; 2 -> "MLAT"; 3 -> "FLARM"; else -> "Unknown" }))
            }
        }
        return GlobeFeature(
            id = "aircraft:$hex", layer = FeatureLayer.AIRCRAFT, latitude = lat, longitude = lon,
            title = flightCallsign ?: hex.uppercase(Locale.ROOT), subtitle = country.orEmpty(),
            altitudeMeters = geometric ?: barometric ?: 0.0, headingDegrees = heading ?: 0.0,
            observedAtMillis = positionTime * 1000, details = details, source = "OpenSky Network",
            sourceUrl = "https://opensky-network.org/",
        )
    }

    private fun utcTime(seconds: Long): String = TIME_FORMAT.format(Instant.ofEpochSecond(seconds))

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
    }
}
