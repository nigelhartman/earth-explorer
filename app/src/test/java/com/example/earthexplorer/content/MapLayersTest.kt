package com.example.earthexplorer.content

import com.example.earthexplorer.data.CapitalCities
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.MapLayers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MapLayersTest {
    @Before fun reset() {
        MapLayers.setCapitalsVisible(true)
        GlobeSelection.clearChoice()
    }

    @After fun cleanUp() = reset()

    @Test fun toggleTurnsCapitalMarkersOffAndOn() {
        assertTrue(MapLayers.capitalsVisible.value)
        MapLayers.toggleCapitals()
        assertFalse(MapLayers.capitalsVisible.value)
        MapLayers.toggleCapitals()
        assertTrue(MapLayers.capitalsVisible.value)
    }

    @Test fun explicitVisibilityIsIdempotent() {
        repeat(2) { MapLayers.setCapitalsVisible(false) }
        assertFalse(MapLayers.capitalsVisible.value)
        repeat(2) { MapLayers.setCapitalsVisible(true) }
        assertTrue(MapLayers.capitalsVisible.value)
    }

    @Test fun hidingCapitalsDismissesOverlappingMarkerChoice() {
        val rome = CapitalCities.all.first { it.name == "Rome" }
        GlobeSelection.tap(rome.latitude, rome.longitude, "capital:${rome.id}")
        assertNotNull(GlobeSelection.choice.value)
        MapLayers.setCapitalsVisible(false)
        assertNull(GlobeSelection.choice.value)
    }

    @Test fun hiddenCapitalsNeverSnapEvenIfAnOldMarkerHitIsDelivered() {
        val tokyo = CapitalCities.all.first { it.name == "Tokyo" }
        val marker = "capital:${tokyo.id}"
        val latitude = tokyo.latitude + 0.4
        val longitude = tokyo.longitude - 0.4
        assertEquals(listOf(tokyo), GlobeSelection.capitalCandidates(latitude, longitude, marker, true))
        assertTrue(GlobeSelection.capitalCandidates(latitude, longitude, marker, false).isEmpty())
        assertTrue(GlobeSelection.capitalCandidates(tokyo.latitude, tokyo.longitude, null, false).isEmpty())
    }
}
