package com.example.earthexplorer.data

import com.example.earthexplorer.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

data class SelectedLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

enum class RendererMode(val label: String) {
    EARTH("Native Earth globe"),
    CITY_MESH("Cesium photorealistic 3D Tiles"),
}

data class RenderState(
    val mode: RendererMode = RendererMode.EARTH,
    val status: String = "Ready — tap a city",
    val loadedTiles: Int = 0,
    val requestedTiles: Int = 0,
    val attribution: String = "",
    val error: String? = null,
    val triangles: Int = 0,
    val coarseTiles: Int = 0,
    val viewWidthMeters: Double = 320.0,
    val dragging: Boolean = false,
    val dragInput: String = "Waiting for drag",
    val dragEvents: Int = 0,
)

object LocationStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _selected = MutableStateFlow<SelectedLocation?>(null)
    private val _viewLocation = MutableStateFlow<SelectedLocation?>(null)
    private val _renderState = MutableStateFlow(RenderState())
    private val _cityCenter = MutableStateFlow(CityCenter())
    private val _retry = MutableStateFlow(0)
    private var lookupJob: Job? = null
    private var centerLookupJob: Job? = null
    private var selectionVersion = 0L
    private var frame: GeoFrame? = null
    private val places = LinkedHashMap<Pair<Long, Long>, SelectedLocation>(16, 0.75f, true)

    val selected: StateFlow<SelectedLocation?> = _selected.asStateFlow()
    val viewLocation: StateFlow<SelectedLocation?> = _viewLocation.asStateFlow()
    val renderState: StateFlow<RenderState> = _renderState.asStateFlow()
    val cityCenter: StateFlow<CityCenter> = _cityCenter.asStateFlow()
    val retry: StateFlow<Int> = _retry.asStateFlow()

    @Synchronized
    fun select(latitude: Double, longitude: Double, city: String? = null, country: String? = null) {
        FeatureSelection.clear()
        lookupJob?.cancel()
        centerLookupJob?.cancel()
        val version = ++selectionVersion
        frame = GeoFrame(latitude, longitude)
        places.clear()
        val pending = SelectedLocation(latitude, longitude, city = city, country = country,
            loading = city == null)
        _selected.value = pending
        _viewLocation.value = pending
        _cityCenter.value = CityCenter()
        _renderState.value = RenderState(
            RendererMode.CITY_MESH,
            "Connecting to Cesium ion…",
        )
        // Known capital coordinates already have their names; do not replace them with a street address.
        if (city != null) {
            cachePlace(CityCenter(), pending)
            return
        }
        lookupJob = scope.launch {
            delay(350)
            val result = lookup(pending)
            currentCoroutineContext().ensureActive()
            synchronized(this@LocationStore) {
                if (version != selectionVersion) return@synchronized
                _selected.value = result
                if (result.error == null) cachePlace(CityCenter(), result)
                if (cell(_cityCenter.value) == cell(CityCenter())) {
                    val current = _viewLocation.value ?: return@synchronized
                    _viewLocation.value = result.copy(latitude = current.latitude, longitude = current.longitude)
                }
            }
        }
    }

    fun updateRenderStatus(status: String) {
        _renderState.value = _renderState.value.copy(status = status)
    }

    fun updateCityProgress(status: String, loaded: Int, requested: Int, attribution: String,
        error: String? = null, triangles: Int = 0, coarseTiles: Int = 0) {
        if (_renderState.value.mode != RendererMode.CITY_MESH) return
        _renderState.value = _renderState.value.copy(status = status, loadedTiles = loaded,
            requestedTiles = requested, attribution = attribution, error = error,
            triangles = triangles, coarseTiles = coarseTiles)
    }

    fun beginPanGesture(input: String) {
        _renderState.value = _renderState.value.copy(dragging = true, dragInput = input)
    }

    fun endPanGesture() {
        _renderState.value = _renderState.value.copy(dragging = false)
    }

    fun panCityFromGesture(dx: Float, dy: Float, dz: Float) {
        if (!dx.isFinite() || !dy.isFinite() || !dz.isFinite()) return
        panCity(dx, dy, dz)
        _renderState.value = _renderState.value.copy(dragEvents = _renderState.value.dragEvents + 1)
    }

    @Synchronized
    fun panCity(dx: Float, dy: Float, dz: Float = 0f) {
        if (_renderState.value.mode == RendererMode.CITY_MESH && dx.isFinite() && dy.isFinite() && dz.isFinite()) {
            _cityCenter.value = _cityCenter.value.dragged(dx, dy, dz)
            updateViewLocation()
        }
    }

    @Synchronized
    fun recenterCity() {
        _cityCenter.value = CityCenter()
        updateViewLocation()
    }
    fun retryCity() { _retry.value += 1 }

    @Synchronized
    fun showGlobe() {
        FeatureSelection.clear()
        ++selectionVersion
        lookupJob?.cancel()
        centerLookupJob?.cancel()
        frame = null
        places.clear()
        GlobeSelection.clearChoice()
        _selected.value = null
        _viewLocation.value = null
        _renderState.value = RenderState()
    }

    private fun updateViewLocation() {
        val coordinate = frame?.coordinate(_cityCenter.value) ?: return
        val pending = (_viewLocation.value ?: _selected.value ?: return).copy(
            latitude = coordinate.first, longitude = coordinate.second, loading = true, error = null,
        )
        centerLookupJob?.cancel()
        val key = cell(_cityCenter.value)
        val cached = places[key]
        if (cached != null) {
            _viewLocation.value = cached.copy(latitude = coordinate.first, longitude = coordinate.second)
            return
        }
        _viewLocation.value = pending
        val version = selectionVersion
        val center = _cityCenter.value
        centerLookupJob = scope.launch {
            delay(700)
            val result = lookup(pending)
            currentCoroutineContext().ensureActive()
            synchronized(this@LocationStore) {
                if (version != selectionVersion || cell(_cityCenter.value) != key) return@synchronized
                if (result.error == null) cachePlace(center, result)
                val current = _viewLocation.value ?: return@synchronized
                _viewLocation.value = result.copy(latitude = current.latitude, longitude = current.longitude)
            }
        }
    }

    private fun cell(center: CityCenter): Pair<Long, Long> =
        floor((center.east + CityView.HALF_WIDTH) / (2 * CityView.HALF_WIDTH)).toLong() to
            floor((center.north + CityView.HALF_WIDTH) / (2 * CityView.HALF_WIDTH)).toLong()

    private fun cachePlace(center: CityCenter, location: SelectedLocation) {
        places[cell(center)] = location
        while (places.size > 64) places.remove(places.keys.first())
    }

    private fun lookup(pending: SelectedLocation): SelectedLocation = try {
        reverseGeocode(pending.latitude, pending.longitude)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: IOException) {
        pending.failed()
    } catch (_: JSONException) {
        pending.failed()
    } catch (_: IllegalStateException) {
        pending.failed()
    }

    private fun reverseGeocode(
        latitude: Double,
        longitude: Double,
    ): SelectedLocation {
        val latitudeText = String.format(Locale.US, "%.6f", latitude)
        val longitudeText = String.format(Locale.US, "%.6f", longitude)
        val token = URLEncoder.encode(BuildConfig.CESIUM_ION_TOKEN, "UTF-8")
        val text = URLEncoder.encode("$latitudeText,$longitudeText", "UTF-8")
        val url = URL("https://api.cesium.com/v1/geocode/search" +
            "?geocoder=google&access_token=$token&text=$text")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "EarthExplorer/1.0 (com.example.earthexplorer)",
            )
            connection.setRequestProperty(
                "Accept-Language",
                Locale.getDefault().toLanguageTag(),
            )
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Reverse geocoder returned ${connection.responseCode}"
                )
            }
            val payload = connection.inputStream.bufferedReader().use {
                it.readText()
            }
            val features = JSONObject(payload).optJSONArray("features")
            val candidates = (0 until (features?.length() ?: 0)).mapNotNull { index ->
                val feature = features?.optJSONObject(index) ?: return@mapNotNull null
                val label = feature.optJSONObject("properties")?.optString("label")
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val bounds = feature.optJSONArray("bbox")
                val span = if (bounds != null && bounds.length() == 4) {
                    val longitudeSpan = ((bounds.getDouble(2) - bounds.getDouble(0)) + 360) % 360
                    val latitudeSpan = bounds.getDouble(3) - bounds.getDouble(1)
                    val longitudeScale = cos(Math.toRadians((bounds.getDouble(3) + bounds.getDouble(1)) / 2))
                    hypot(min(longitudeSpan, 360 - longitudeSpan) * longitudeScale, latitudeSpan) * 111.32
                } else null
                GeocoderPlace(label, span)
            }
            val labels = PlaceLabels.from(candidates)
            SelectedLocation(
                latitude = latitude,
                longitude = longitude,
                city = labels.city,
                region = labels.region,
                country = labels.country,
                loading = false,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun SelectedLocation.failed(): SelectedLocation = copy(
        loading = false,
        error =
            "City lookup is unavailable. " +
                "The current center coordinates are still shown.",
    )
}
