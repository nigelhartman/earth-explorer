package com.example.earthexplorer.content

import com.example.earthexplorer.data.CityCenter
import com.example.earthexplorer.data.GeoFrame
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.RendererMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocationStoreTest {
    @Before fun reset() = LocationStore.showGlobe()
    @After fun cleanUp() = LocationStore.showGlobe()

    @Test fun liveCoordinatesFollowPanWithoutMovingTheGeographicAnchor() {
        LocationStore.select(53.335007, -6.250852, "Dublin", "Ireland")
        val origin = requireNotNull(LocationStore.selected.value)
        LocationStore.panCity(-100f, 40f)
        val expected = GeoFrame(origin.latitude, origin.longitude).coordinate(LocationStore.cityCenter.value)
        val current = requireNotNull(LocationStore.viewLocation.value)
        assertEquals(origin, LocationStore.selected.value)
        assertEquals(expected.first, current.latitude, 1e-10)
        assertEquals(expected.second, current.longitude, 1e-10)
        assertEquals("Dublin", current.city)
        assertEquals("Ireland", current.country)
        assertFalse(current.loading)
    }

    @Test fun recenterRestoresInitialCoordinatesAndKnownPlace() {
        LocationStore.select(52.52, 13.405, "Berlin", "Germany")
        LocationStore.panCity(-100f, 50f)
        LocationStore.recenterCity()
        val current = requireNotNull(LocationStore.viewLocation.value)
        assertEquals(CityCenter(), LocationStore.cityCenter.value)
        assertEquals(52.52, current.latitude, 1e-9)
        assertEquals(13.405, current.longitude, 1e-9)
        assertEquals("Berlin", current.city)
    }

    @Test fun switchingCitiesAndReturningToEarthClearsPreviousPlace() {
        LocationStore.select(52.52, 13.405, "Berlin", "Germany")
        LocationStore.panCity(40f, 40f)
        LocationStore.select(53.335007, -6.250852, "Dublin", "Ireland")
        assertEquals("Dublin", LocationStore.viewLocation.value?.city)
        assertEquals(CityCenter(), LocationStore.cityCenter.value)
        LocationStore.showGlobe()
        assertNull(LocationStore.viewLocation.value)
        assertNull(LocationStore.selected.value)
        assertEquals(RendererMode.EARTH, LocationStore.renderState.value.mode)
    }

    @Test fun invalidGestureDoesNotCorruptCoordinates() {
        LocationStore.select(52.52, 13.405, "Berlin", "Germany")
        val before = LocationStore.viewLocation.value
        LocationStore.panCityFromGesture(Float.NaN, 0f, 0f)
        LocationStore.panCity(0f, Float.POSITIVE_INFINITY)
        assertEquals(CityCenter(), LocationStore.cityCenter.value)
        assertEquals(before, LocationStore.viewLocation.value)
    }
}
