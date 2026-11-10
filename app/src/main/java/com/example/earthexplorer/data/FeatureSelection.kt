package com.example.earthexplorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selection keeps the event's exact coordinates separate from the movable terrain center. */
object FeatureSelection {
    private val _selected = MutableStateFlow<GlobeFeature?>(null)
    val selected = _selected.asStateFlow()

    fun select(feature: GlobeFeature) {
        val camera = if (feature.layer == FeatureLayer.WEBCAMS) {
            if (!MapLayers.webcamsVisible.value) return
            WebcamLayerStore.find(feature.id) ?: return
        } else null
        GlobeSelection.clearChoice()
        WebcamSelection.clear()
        if (feature.layer == FeatureLayer.EARTHQUAKES) {
            LocationStore.select(feature.latitude, feature.longitude)
        }
        if (camera != null) {
            LocationStore.select(camera.latitude, camera.longitude, camera.city, camera.country)
            WebcamSelection.open(camera)
        }
        _selected.value = feature
    }

    /** Show the details card and marker without opening the 3D terrain. */
    fun preview(feature: GlobeFeature) {
        GlobeSelection.clearChoice()
        WebcamSelection.clear()
        _selected.value = feature
    }

    fun exploreGround(feature: GlobeFeature, city: String? = null, country: String? = null) {
        LocationStore.select(feature.latitude, feature.longitude, city, country)
        _selected.value = feature
    }

    fun clear() {
        _selected.value = null
        WebcamSelection.clear()
    }
    fun clearLayer(layer: FeatureLayer) {
        if (_selected.value?.layer == layer) clear()
    }
}
