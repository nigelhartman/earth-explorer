package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GlobeFocus(val latitude: Double = 24.0, val longitude: Double = 10.0)
data class CapitalChoice(val point: GlobeFocus, val cities: List<CapitalCity>)
data class GlobeFocusRequest(val point: GlobeFocus, val sequence: Long, val blink: Boolean = false)

/** Shared state lets the adjacent picker follow globe rotation without network requests. */
object GlobeSelection {
    private val _focus = MutableStateFlow(GlobeFocus())
    private val _choice = MutableStateFlow<CapitalChoice?>(null)
    val focus = _focus.asStateFlow()
    val choice = _choice.asStateFlow()
    private val _focusRequest = MutableStateFlow<GlobeFocusRequest?>(null)
    val focusRequest = _focusRequest.asStateFlow()
    private var focusSequence = 0L

    /** Turn the globe toward a place; with [blink] the lock-on reticle stays pulsing there. */
    fun focusOn(latitude: Double, longitude: Double, blink: Boolean = false) {
        _focusRequest.value = GlobeFocusRequest(GlobeFocus(latitude, longitude), ++focusSequence, blink)
    }

    fun consumeFocus(sequence: Long) {
        if (_focusRequest.value?.sequence == sequence) _focusRequest.value = null
    }

    fun face(latitude: Double, longitude: Double) {
        _focus.value = GlobeFocus(latitude, longitude)
    }

    fun clearChoice() { _choice.value = null }

    fun tap(latitude: Double, longitude: Double, entityName: String? = null) {
        val options = capitalCandidates(latitude, longitude, entityName, MapLayers.capitalsVisible.value)
        when {
            options.size > 1 -> _choice.value = CapitalChoice(GlobeFocus(latitude, longitude), options)
            options.size == 1 -> open(options.single())
            else -> { clearChoice(); LocationStore.select(latitude, longitude) }
        }
    }

    fun open(city: CapitalCity) {
        clearChoice()
        LocationStore.select(city.latitude, city.longitude, city.name, city.country)
    }

    internal fun capitalCandidates(latitude: Double, longitude: Double,
        entityName: String?, capitalsVisible: Boolean): List<CapitalCity> {
        if (!capitalsVisible) return emptyList()
        val direct = CapitalCities.fromEntityName(entityName)
        // Even a direct pin can overlap another capital (Rome / Vatican, for example).
        return if (direct != null) CapitalCities.nearby(direct.latitude, direct.longitude)
            else CapitalCities.nearby(latitude, longitude)
    }
}
