package com.example.earthexplorer.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.earthexplorer.data.*

/** Debug-only emulator control exercises exactly the same panning path as a hand drag. */
class CityDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.example.earthexplorer.PAN" -> LocationStore.panCity(
                intent.getFloatExtra("dx", 0f), intent.getFloatExtra("dy", 0f))
            "com.example.earthexplorer.RECENTER" -> LocationStore.recenterCity()
            "com.example.earthexplorer.RETRY" -> LocationStore.retryCity()
            "com.example.earthexplorer.EARTH" -> LocationStore.showGlobe()
            "com.example.earthexplorer.CITY" -> LocationStore.select(52.520815, 13.411050, "Berlin", "Germany")
            "com.example.earthexplorer.LAYERS" -> {
                if (intent.hasExtra("aircraft")) MapLayers.setAircraftVisible(intent.getBooleanExtra("aircraft", false))
                if (intent.hasExtra("earthquakes")) MapLayers.setEarthquakesVisible(intent.getBooleanExtra("earthquakes", false))
                if (intent.hasExtra("sun")) MapLayers.setSunVisible(intent.getBooleanExtra("sun", false))
                if (intent.hasExtra("positions")) MapLayers.setPositionsVisible(intent.getBooleanExtra("positions", false))
                if (intent.hasExtra("capitals")) MapLayers.setCapitalsVisible(intent.getBooleanExtra("capitals", true))
                if (intent.hasExtra("webcams")) MapLayers.setWebcamsVisible(intent.getBooleanExtra("webcams", false))
            }
            "com.example.earthexplorer.FOCUS_ISS" -> {
                PositionsLayerStore.state.value.features.firstOrNull { it.highlighted }?.let {
                    FeatureSelection.select(it)
                    GlobeSelection.focusOn(it.latitude, it.longitude)
                }
            }
            "com.example.earthexplorer.SELECT_QUAKE" -> {
                val feature = FeatureGeometry.nearest(EarthquakeLayerStore.state.value.features,
                    intent.getDoubleExtra("latitude", 35.68), intent.getDoubleExtra("longitude", 139.76), 180.0)
                feature?.let(FeatureSelection::select)
            }
            "com.example.earthexplorer.SELECT_WEBCAM" -> {
                val id = intent.getStringExtra("id")
                WebcamLayerStore.state.value.features.firstOrNull { id == null || it.id == id }
                    ?.let(FeatureSelection::select)
            }
            "com.example.earthexplorer.CLOSE_WEBCAM" -> WebcamSelection.clear()
            "com.example.earthexplorer.ASSISTANT_PRESS" -> AssistantDemo.pressStart()
            "com.example.earthexplorer.ASSISTANT_RELEASE" -> AssistantDemo.pressEnd()
            "com.example.earthexplorer.ASSISTANT_RESET" -> AssistantDemo.reset()
            "com.example.earthexplorer.PANELS" -> PanelVisibility.set(intent.getBooleanExtra("visible", true))
            "com.example.earthexplorer.STATUS" -> {
                Log.i("LayerSmokeTest", "Presentation phase=${ExplorerMotion.phase.value}")
                listOf("aircraft" to AircraftLayerStore.state.value,
                    "earthquakes" to EarthquakeLayerStore.state.value,
                    "positions" to PositionsLayerStore.state.value,
                    "webcams" to WebcamLayerStore.state.value).forEach { (name, feed) ->
                    Log.i("LayerSmokeTest", "$name count=${feed.features.size} loading=${feed.loading} updated=${feed.updatedAtMillis} error=${feed.error}")
                }
                PositionsLayerStore.state.value.features.firstOrNull { it.highlighted }?.let {
                    Log.i("LayerSmokeTest", "ISS lat=${it.latitude} lon=${it.longitude} observed=${it.observedAtMillis}")
                }
                FeatureSelection.selected.value?.let {
                    Log.i("LayerSmokeTest", "Selected ${it.id} GPS=${it.latitude},${it.longitude}")
                }
                LocationStore.selected.value?.let {
                    Log.i("LayerSmokeTest", "Terrain GPS=${it.latitude},${it.longitude}")
                }
                WebcamSelection.camera.value?.let {
                    Log.i("LayerSmokeTest", "Webcam ${it.id} GPS=${it.latitude},${it.longitude} video=${it.videoUrl != null}")
                }
            }
        }
    }
}
