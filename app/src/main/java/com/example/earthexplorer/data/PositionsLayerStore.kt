package com.example.earthexplorer.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Live ISS reports and clearly labelled, locally animated GPS orbital predictions. */
object PositionsLayerStore {
    const val GPS_SOURCE_URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=gps-ops&FORMAT=json"
    private const val ISS_URL = "https://api.wheretheiss.at/v1/satellites/25544?units=kilometers"
    private const val ORBIT_REFRESH_MILLIS = 2 * 60 * 60 * 1_000L
    private const val ISS_REFRESH_MILLIS = 5_000L
    private const val MAX_RESPONSE_BYTES = 512 * 1_024
    private const val MAX_SATELLITES = 60
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val issRequests = Mutex()
    private val orbitRequests = Mutex()
    private val _state = MutableStateFlow(LayerFeed(note = "ISS live positions + approximate GPS satellite orbits"))
    val state: StateFlow<LayerFeed> = _state.asStateFlow()
    @Volatile private var enabled = false
    private var generation = 0L
    @Volatile private var worker: Job? = null
    @Volatile private var cacheDirectory: File? = null
    @Volatile private var cacheLoaded = false
    @Volatile private var orbits = emptyList<OrbitElements>()
    @Volatile private var lastOrbitAttempt = 0L
    private var lastIssAttemptNanos: Long? = null
    @Volatile private var lastIss: IssPosition? = null
    @Volatile private var orbitError: String? = null
    @Volatile private var issError: String? = null
    @Volatile private var orbitLoading = false
    @Volatile private var issLoading = false

    /** Call once with an app-private cache directory; files contain only public orbital data. */
    @Synchronized
    fun configureCache(directory: File) {
        cacheDirectory = directory
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        val session = ++generation
        worker?.cancel()
        worker = null
        orbitLoading = false
        issLoading = false
        while (refreshRequests.tryReceive().isSuccess) { /* Discard a previous session's refresh. */ }
        if (!value) {
            _state.value = _state.value.copy(loading = false)
            return
        }
        // Re-check source ages immediately rather than flashing an expired retained ISS marker.
        publish(session)
        _state.value = _state.value.copy(loading = _state.value.features.isEmpty())
        worker = scope.launch {
            coroutineScope {
                launch {
                    while (isActive) {
                        updateOrbitsIfDue(session)
                        delay(60_000)
                    }
                }
                launch {
                    while (isActive) {
                        updateIss(session)
                        withTimeoutOrNull(ISS_REFRESH_MILLIS) { refreshRequests.receive() }
                    }
                }
                launch {
                    while (isActive) {
                        publish(session)
                        delay(1_000)
                    }
                }
            }
        }
    }

    /** Refresh requests never bypass either provider's rate limit. */
    fun refresh() {
        if (enabled) refreshRequests.trySend(Unit)
    }

    private suspend fun updateIss(session: Long) = issRequests.withLock {
        if (!isCurrent(session)) return@withLock
        val elapsed = lastIssAttemptNanos?.let { (System.nanoTime() - it) / 1_000_000L }
        if (elapsed != null) delay((ISS_REFRESH_MILLIS - elapsed).coerceIn(0, ISS_REFRESH_MILLIS))
        currentCoroutineContext().ensureActive()
        if (!isCurrent(session)) return@withLock
        lastIssAttemptNanos = System.nanoTime()
        updateCurrent(session) { issLoading = lastIss?.isFreshAt(System.currentTimeMillis()) != true }
        try {
            val parsed = parseIssPosition(fetchJson(ISS_URL))
            currentCoroutineContext().ensureActive()
            if (!parsed.isFreshAt(System.currentTimeMillis())) throw IOException("ISS provider returned an old position")
            updateCurrent(session) {
                // Even a fresh but out-of-order server response must not move the ISS backward.
                if (parsed.supersedes(lastIss, System.currentTimeMillis())) {
                    lastIss = parsed
                    issError = null
                } else issError = "ISS provider returned an older position; keeping the latest report"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            updateCurrent(session) { issError = "ISS position temporarily unavailable" }
        } catch (_: JSONException) {
            updateCurrent(session) { issError = "ISS provider returned an invalid position" }
        } catch (_: IllegalArgumentException) {
            updateCurrent(session) { issError = "ISS provider returned an invalid position" }
        } finally {
            updateCurrent(session) { issLoading = false }
        }
    }

    private suspend fun updateOrbitsIfDue(session: Long) = orbitRequests.withLock {
        if (!isCurrent(session)) return@withLock
        // Serialize cache loading with downloads across session changes as well as within one.
        loadCache()
        currentCoroutineContext().ensureActive()
        if (!isCurrent(session)) return@withLock
        val now = System.currentTimeMillis()
        // CelesTrak explicitly requests at most one download per two-hour update, even on retry.
        if (lastOrbitAttempt > 0 && now - lastOrbitAttempt < ORBIT_REFRESH_MILLIS) {
            updateCurrent(session) {
                orbitLoading = false
                if (orbits.isEmpty() && orbitError == null) {
                    orbitError = "Waiting for CelesTrak's next two-hour update; ISS tracking is independent"
                }
            }
            return@withLock
        }
        lastOrbitAttempt = now
        persistAttempt(now)
        updateCurrent(session) { orbitLoading = orbits.isEmpty() }
        try {
            // Keep an already received response even if the user switches this layer off while
            // it is in flight. Otherwise the two-hour cooldown would leave the next toggle empty.
            val raw = fetchJson(GPS_SOURCE_URL, retainCompletedResponse = true)
            val parsed = parseOrbitElements(raw)
            if (parsed.isEmpty()) throw IOException("GPS orbital feed contains no valid elements")
            orbits = parsed
            orbitError = null
            persistOrbits(raw)
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            updateCurrent(session) {
                orbitError = "GPS orbital feed temporarily unavailable; retrying on the next two-hour update"
            }
        } catch (_: JSONException) {
            updateCurrent(session) { orbitError = "GPS orbital feed could not be read; ISS tracking is independent" }
        } finally {
            updateCurrent(session) { orbitLoading = false }
        }
    }

    @Synchronized
    private fun isCurrent(session: Long) = enabled && generation == session

    @Synchronized
    private fun updateCurrent(session: Long, update: () -> Unit) {
        if (!isCurrent(session)) return
        update()
        publish(session)
    }

    @Synchronized
    private fun publish(session: Long) {
        if (!isCurrent(session)) return
        val now = System.currentTimeMillis()
        val iss = lastIss?.featureAt(now)
        val satellites = orbits.mapNotNull { it.featureAt(now) }
        val currentIssError = issError
        val currentOrbitError = orbitError
        val messages = buildList {
            if (currentIssError != null) add(currentIssError)
            if (currentOrbitError != null) add(currentOrbitError)
            if (lastIss != null && iss == null && currentIssError == null) add("ISS position expired; waiting for a fresh report")
            if (orbits.isNotEmpty() && satellites.isEmpty()) add("GPS orbital elements are too old to display")
        }
        val features = listOfNotNull(iss) + satellites
        _state.value = LayerFeed(
            features = features,
            loading = orbitLoading || issLoading,
            updatedAtMillis = if (satellites.isNotEmpty()) now else iss?.observedAtMillis,
            error = messages.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            note = "ISS: 5-second provider updates. GPS: approximate Kepler prediction each second, not telemetry; altitude is visually compressed.",
        )
    }

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        val directory = cacheDirectory ?: return
        try {
            val attemptFile = File(directory, "gps-last-attempt.txt")
            if (attemptFile.isFile && attemptFile.length() < 64) {
                lastOrbitAttempt = (attemptFile.readText().trim().toLongOrNull() ?: 0L)
                    .coerceIn(0L, System.currentTimeMillis())
            }
            val dataFile = File(directory, "gps-elements.json")
            if (dataFile.isFile && dataFile.length() <= MAX_RESPONSE_BYTES) {
                orbits = parseOrbitElements(dataFile.readText())
            }
            if (orbits.isEmpty() && lastOrbitAttempt > 0 &&
                System.currentTimeMillis() - lastOrbitAttempt in 0 until ORBIT_REFRESH_MILLIS) {
                orbitError = "Waiting for CelesTrak's next two-hour update; ISS tracking is independent"
            }
        } catch (_: IOException) {
            // A missing/evicted cache must not prevent live tracking.
        } catch (_: JSONException) {
            orbitError = "Saved GPS data could not be read"
        }
    }

    private fun persistAttempt(time: Long) {
        val directory = cacheDirectory ?: return
        try {
            if (directory.isDirectory || directory.mkdirs()) {
                File(directory, "gps-last-attempt.txt").writeText(time.toString())
            }
        } catch (_: IOException) {
            // The in-memory provider cooldown still applies if disk caching is unavailable.
        }
    }

    private fun persistOrbits(raw: String) {
        val directory = cacheDirectory ?: return
        try {
            if (directory.isDirectory || directory.mkdirs()) {
                val pending = File(directory, "gps-elements.pending")
                pending.writeText(raw)
                pending.renameTo(File(directory, "gps-elements.json"))
            }
        } catch (_: IOException) {
            // Public orbit data remains available in memory for this session.
        }
    }

    private suspend fun fetchJson(address: String, retainCompletedResponse: Boolean = false): String {
        currentCoroutineContext().ensureActive()
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "EarthExplorer/1.0 (PICO educational globe)")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (connection.responseCode !in 200..299) throw IOException("Position provider HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
            if (!retainCompletedResponse) currentCoroutineContext().ensureActive()
            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Position feed exceeded size limit")
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseIssPosition(raw: String): IssPosition {
        val value = JSONObject(raw)
        require(value.getLong("id") == 25544L) { "Unexpected satellite in ISS response" }
        require(value.optString("units", "kilometers") == "kilometers") { "Unexpected ISS distance units" }
        val seconds = value.getLong("timestamp")
        require(seconds in 1..4_102_444_800L) { "Invalid ISS position time" }
        return IssPosition(
            latitude = value.getDouble("latitude"),
            longitude = value.getDouble("longitude"),
            altitudeKilometers = value.getDouble("altitude"),
            velocityKilometersPerHour = value.getDouble("velocity"),
            timestampMillis = seconds * 1_000L,
            visibility = value.optString("visibility", "unknown").take(40),
        )
    }

    internal fun parseOrbitElements(raw: String): List<OrbitElements> {
        val values = JSONArray(raw)
        return buildList {
            for (index in 0 until values.length().coerceAtMost(MAX_SATELLITES)) {
                val value = values.optJSONObject(index) ?: continue
                try {
                    val epoch = value.getString("EPOCH")
                    val epochMillis = if (epoch.endsWith("Z")) Instant.parse(epoch).toEpochMilli()
                        else LocalDateTime.parse(epoch).toInstant(ZoneOffset.UTC).toEpochMilli()
                    add(OrbitElements(
                        catalogId = value.getLong("NORAD_CAT_ID"),
                        name = value.getString("OBJECT_NAME").trim().take(100),
                        epochMillis = epochMillis,
                        meanMotionRevolutionsPerDay = value.getDouble("MEAN_MOTION"),
                        eccentricity = value.getDouble("ECCENTRICITY"),
                        inclinationDegrees = value.getDouble("INCLINATION"),
                        ascendingNodeDegrees = value.getDouble("RA_OF_ASC_NODE"),
                        argumentOfPericenterDegrees = value.getDouble("ARG_OF_PERICENTER"),
                        meanAnomalyDegrees = value.getDouble("MEAN_ANOMALY"),
                    ))
                } catch (_: JSONException) {
                    // One malformed orbit should not hide the rest of the constellation.
                } catch (_: IllegalArgumentException) {
                    // Nonphysical or incomplete orbital elements cannot be propagated safely.
                } catch (_: DateTimeParseException) {
                    // Keep the source epoch rather than inventing one from the download time.
                } catch (_: ArithmeticException) {
                    // An out-of-range ISO epoch must not overflow Unix milliseconds.
                }
            }
        }.distinctBy { it.catalogId }
    }
}
