package com.example.earthexplorer.content

import com.example.earthexplorer.data.FeatureSelection
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.GlobeFeature
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.RendererMode
import com.example.earthexplorer.data.WebcamCamera
import com.example.earthexplorer.data.WebcamSelection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebcamSelectionTest {
    private val camera = WebcamCamera("tfl-00001.04502", "Westminster Bridge/York Rd",
        "London", "United Kingdom", 51.5009, -0.11762, "Transport for London",
        "https://tfl.gov.uk/traffic/status/",
        "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.04502.jpg",
        "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.04502.mp4")

    @Before fun reset() = LocationStore.showGlobe()
    @After fun cleanUp() = LocationStore.showGlobe()

    @Test fun closingWebcamKeepsTerrainAndItsCenter() {
        LocationStore.select(camera.latitude, camera.longitude, camera.city, camera.country)
        WebcamSelection.open(camera)
        WebcamSelection.clear()
        assertNull(WebcamSelection.camera.value)
        assertEquals(RendererMode.CITY_MESH, LocationStore.renderState.value.mode)
        assertEquals(camera.latitude, LocationStore.selected.value!!.latitude, 0.0)
        assertEquals(camera.longitude, LocationStore.selected.value!!.longitude, 0.0)
    }

    @Test fun returningToEarthClearsVideoSelection() {
        WebcamSelection.open(camera)
        LocationStore.showGlobe()
        assertNull(WebcamSelection.camera.value)
        assertEquals(RendererMode.EARTH, LocationStore.renderState.value.mode)
    }

    @Test fun choosingAnotherLocationStopsPreviousCamera() {
        WebcamSelection.open(camera)
        LocationStore.select(52.5208, 13.4094, "Berlin", "Germany")
        assertNull(WebcamSelection.camera.value)
    }

    @Test fun hidingLayerStopsCameraEvenAfterItsDetailsWereCleared() {
        WebcamSelection.open(camera)
        MapLayers.setWebcamsVisible(false)
        assertNull(WebcamSelection.camera.value)
        assertFalse(MapLayers.isVisible(camera.toFeature().layer))
    }

    @Test fun clearingObservationAlsoStopsItsCamera() {
        WebcamSelection.open(camera)
        FeatureSelection.clear()
        assertNull(WebcamSelection.camera.value)
    }

    @Test fun staleDisabledCameraTapDoesNotReplaceCurrentObservation() {
        MapLayers.setWebcamsVisible(false)
        val aircraft = GlobeFeature("flight", FeatureLayer.AIRCRAFT, 30.0, 10.0,
            "Flight", observedAtMillis = 1L, source = "test")
        FeatureSelection.select(aircraft)
        FeatureSelection.select(camera.toFeature())
        assertEquals(aircraft, FeatureSelection.selected.value)
        assertEquals(RendererMode.EARTH, LocationStore.renderState.value.mode)
    }
}
