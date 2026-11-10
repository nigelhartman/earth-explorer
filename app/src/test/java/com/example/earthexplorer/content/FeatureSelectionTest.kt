package com.example.earthexplorer.content

import com.example.earthexplorer.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class FeatureSelectionTest {
    @Before fun reset() = LocationStore.showGlobe()
    @After fun cleanUp() = LocationStore.showGlobe()

    private fun feature(layer: FeatureLayer) = GlobeFeature(
        "event", layer, 35.123456, 139.654321, "Observation", observedAtMillis = 1L, source = "test")

    @Test fun earthquakeOpensNativeMeshAtExactEpicenterWithoutCapitalSnapping() {
        val quake = feature(FeatureLayer.EARTHQUAKES)
        FeatureSelection.select(quake)
        assertEquals(quake, FeatureSelection.selected.value)
        assertEquals(RendererMode.CITY_MESH, LocationStore.renderState.value.mode)
        assertEquals(quake.latitude, LocationStore.selected.value!!.latitude, 0.0)
        assertEquals(quake.longitude, LocationStore.selected.value!!.longitude, 0.0)
    }

    @Test fun draggingTerrainDoesNotMoveEarthquakeObservation() {
        val quake = feature(FeatureLayer.EARTHQUAKES)
        FeatureSelection.select(quake)
        LocationStore.panCity(-100f, 20f)
        assertEquals(quake, FeatureSelection.selected.value)
        assertNotEquals(quake.longitude, LocationStore.viewLocation.value!!.longitude, 1e-8)
        LocationStore.recenterCity()
        assertEquals(quake.longitude, LocationStore.viewLocation.value!!.longitude, 1e-8)
    }

    @Test fun selectingAircraftKeepsEarthVisibleUntilGroundIsRequested() {
        val aircraft = feature(FeatureLayer.AIRCRAFT)
        FeatureSelection.select(aircraft)
        assertEquals(RendererMode.EARTH, LocationStore.renderState.value.mode)
        assertNull(LocationStore.selected.value)
        FeatureSelection.exploreGround(aircraft)
        assertEquals(RendererMode.CITY_MESH, LocationStore.renderState.value.mode)
        assertEquals(aircraft, FeatureSelection.selected.value)
    }

    @Test fun clearingDifferentLayerDoesNotClearCurrentObservation() {
        val iss = feature(FeatureLayer.POSITIONS)
        FeatureSelection.select(iss)
        FeatureSelection.clearLayer(FeatureLayer.AIRCRAFT)
        assertEquals(iss, FeatureSelection.selected.value)
        FeatureSelection.clearLayer(FeatureLayer.POSITIONS)
        assertNull(FeatureSelection.selected.value)
    }

    @Test fun returningToEarthClearsEventAndTerrainTogether() {
        FeatureSelection.select(feature(FeatureLayer.EARTHQUAKES))
        LocationStore.showGlobe()
        assertNull(FeatureSelection.selected.value)
        assertNull(LocationStore.selected.value)
    }
}
