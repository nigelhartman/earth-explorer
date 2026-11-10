package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared layer visibility survives switching between the globe and a city. */
object MapLayers {
    private val _capitalsVisible = MutableStateFlow(true)
    val capitalsVisible = _capitalsVisible.asStateFlow()
    private val _aircraftVisible = MutableStateFlow(false)
    private val _earthquakesVisible = MutableStateFlow(false)
    private val _sunVisible = MutableStateFlow(false)
    private val _positionsVisible = MutableStateFlow(false)
    private val _webcamsVisible = MutableStateFlow(false)
    private val _gridVisible = MutableStateFlow(true)
    val gridVisible = _gridVisible.asStateFlow()
    val aircraftVisible = _aircraftVisible.asStateFlow()
    val earthquakesVisible = _earthquakesVisible.asStateFlow()
    val sunVisible = _sunVisible.asStateFlow()
    val positionsVisible = _positionsVisible.asStateFlow()
    val webcamsVisible = _webcamsVisible.asStateFlow()

    fun setCapitalsVisible(visible: Boolean) {
        _capitalsVisible.value = visible
        if (!visible) GlobeSelection.clearChoice()
    }

    fun toggleCapitals() = setCapitalsVisible(!_capitalsVisible.value)

    fun setAircraftVisible(visible: Boolean) {
        _aircraftVisible.value = visible
        AircraftLayerStore.setEnabled(visible)
        if (!visible) FeatureSelection.clearLayer(FeatureLayer.AIRCRAFT)
    }

    fun setEarthquakesVisible(visible: Boolean) {
        _earthquakesVisible.value = visible
        EarthquakeLayerStore.setEnabled(visible)
        if (!visible) FeatureSelection.clearLayer(FeatureLayer.EARTHQUAKES)
    }

    fun setSunVisible(visible: Boolean) {
        _sunVisible.value = visible
        if (!visible) FeatureSelection.clearLayer(FeatureLayer.SUN)
    }

    fun setPositionsVisible(visible: Boolean) {
        _positionsVisible.value = visible
        PositionsLayerStore.setEnabled(visible)
        if (!visible) FeatureSelection.clearLayer(FeatureLayer.POSITIONS)
    }

    fun toggleAircraft() = setAircraftVisible(!_aircraftVisible.value)
    fun toggleEarthquakes() = setEarthquakesVisible(!_earthquakesVisible.value)
    fun toggleSun() = setSunVisible(!_sunVisible.value)
    fun togglePositions() = setPositionsVisible(!_positionsVisible.value)

    fun setWebcamsVisible(visible: Boolean) {
        _webcamsVisible.value = visible
        WebcamLayerStore.setEnabled(visible)
        if (!visible) {
            FeatureSelection.clearLayer(FeatureLayer.WEBCAMS)
            WebcamSelection.clear()
        }
    }

    fun toggleWebcams() = setWebcamsVisible(!_webcamsVisible.value)

    fun setGridVisible(visible: Boolean) { _gridVisible.value = visible }
    fun toggleGrid() = setGridVisible(!_gridVisible.value)

    fun isVisible(layer: FeatureLayer): Boolean = when (layer) {
        FeatureLayer.AIRCRAFT -> _aircraftVisible.value
        FeatureLayer.EARTHQUAKES -> _earthquakesVisible.value
        FeatureLayer.SUN -> _sunVisible.value
        FeatureLayer.POSITIONS -> _positionsVisible.value
        FeatureLayer.WEBCAMS -> _webcamsVisible.value
    }
}
