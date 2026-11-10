package com.example.earthexplorer.content

import com.example.earthexplorer.data.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class CapitalCitiesTest {
    @Before fun reset() { LocationStore.showGlobe() }
    @After fun cleanUp() { LocationStore.showGlobe() }

    @Test fun catalogHasUniqueValidCapitalLocations() {
        assertEquals(200, CapitalCities.all.size)
        assertEquals(200, CapitalCities.all.map { it.id }.toSet().size)
        assertTrue(CapitalCities.all.all {
            it.name.isNotBlank() && it.country.isNotBlank() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        })
    }

    @Test fun searchMatchesCityAndCountryIgnoringCase() {
        assertTrue(CapitalCities.search("pArIs", 0.0, 0.0).any { it.name == "Paris" })
        assertTrue(CapitalCities.search(" Germany ", 0.0, 0.0).any { it.name == "Berlin" })
        assertTrue(CapitalCities.search("not a real capital", 0.0, 0.0).isEmpty())
    }

    @Test fun requestedCapitalsOpenAtTheirLandmarks() {
        val landmarks = listOf(
            Triple("London", 51.500708, -0.124601),
            Triple("Berlin", 52.520815, 13.411050),
            Triple("Washington, D.C.", 38.897676, -77.036530),
        )
        landmarks.forEach { (name, latitude, longitude) ->
            val capital = CapitalCities.all.single { it.name == name }
            GlobeSelection.open(capital)
            val selected = requireNotNull(LocationStore.selected.value)
            assertEquals(latitude, selected.latitude, 1e-7)
            assertEquals(longitude, selected.longitude, 1e-7)
        }
    }

    @Test fun hoverRayFindsCapitalThroughOuterLayerInputShell() {
        val london = CapitalCities.all.single { it.name == "London" }
        val direction = FeatureGeometry.direction(london.latitude, london.longitude)
        val ray = requireNotNull(FeatureGeometry.rayThrough(direction * 1.2, direction * 0.34))
        assertEquals(london, hoveredCapital(ray, 0.3))
    }

    @Test fun hoverDoesNotSelectCapitalsBehindTheEarthOrOffTheGlobe() {
        val ray = FeaturePickRay(DVec3(0.0, 0.0, 1.0), DVec3(0.0, 0.0, -1.0))
        assertNull(hoveredCapital(ray, 0.3))
        assertNull(hoveredCapital(ray.copy(origin = DVec3(1.0, 1.0, 1.0)), 0.3))
    }

    @Test fun expandedHoverColliderCannotRevealACapitalBehindTheLimb() {
        val capital = CapitalCity("limb", "Hidden", "Test", 0.0, 86.0)
        val point = FeatureGeometry.direction(capital.latitude, capital.longitude) * 0.3036
        val ray = requireNotNull(FeatureGeometry.rayThrough(DVec3(0.0, 0.0, 1.2), point * 1.01))
        assertNull("The offset ray misses Earth but still crosses the expanded pin", FeatureGeometry.pointOnEarth(ray, 0.3))
        assertNull(hoveredCapital(ray, 0.3, listOf(capital)))
    }

    @Test fun visibleRaisedPinAtTheLimbStillHasAHoverLabel() {
        val capital = CapitalCity("limb", "Visible", "Test", 0.0, 80.0)
        val point = FeatureGeometry.direction(capital.latitude, capital.longitude) * 0.3036
        val ray = requireNotNull(FeatureGeometry.rayThrough(DVec3(0.0, 0.0, 1.2), point))
        assertEquals(capital, hoveredCapital(ray, 0.3, listOf(capital)))
    }

    @Test fun invalidHoverRaysNeverSelectACapital() {
        assertNull(hoveredCapital(FeaturePickRay(DVec3(0.0, 0.0, 1.0), DVec3(0.0, 0.0, 0.0)), 0.3))
        assertNull(hoveredCapital(FeaturePickRay(DVec3(Double.NaN, 0.0, 1.0), DVec3(0.0, 0.0, -1.0)), 0.3))
    }

    @Test fun angularDistanceWrapsAtDateLine() {
        val point = CapitalCity("test", "Test", "Test", 0.0, 179.5)
        assertEquals(1.0, point.distanceDegrees(0.0, -179.5), 1e-6)
    }

    @Test fun onlyCapitalEntityNamesResolveToMarkers() {
        val city = CapitalCities.all.first()
        assertEquals(city, CapitalCities.fromEntityName("capital:${city.id}"))
        assertNull(CapitalCities.fromEntityName(city.id))
        assertNull(CapitalCities.fromEntityName(null))
        assertNull(CapitalCities.fromEntityName("capital:unknown"))
    }

    @Test fun markerUsesExactCityCenterInsteadOfImpreciseFingerHit() {
        val tokyo = CapitalCities.all.first { it.name == "Tokyo" }
        GlobeSelection.tap(tokyo.latitude + 0.4, tokyo.longitude - 0.4, "capital:${tokyo.id}")
        val selected = requireNotNull(LocationStore.selected.value)
        assertEquals(tokyo.latitude, selected.latitude, 0.0)
        assertEquals(tokyo.longitude, selected.longitude, 0.0)
        assertEquals("Tokyo", selected.city)
        assertFalse(selected.loading)
        assertEquals(RendererMode.CITY_MESH, LocationStore.renderState.value.mode)
    }

    @Test fun overlappingRomeAndVaticanMarkersOfferAChoice() {
        val rome = CapitalCities.all.first { it.name == "Rome" }
        GlobeSelection.tap(rome.latitude, rome.longitude, "capital:${rome.id}")
        assertNull(LocationStore.selected.value)
        val choices = requireNotNull(GlobeSelection.choice.value).cities
        assertTrue(choices.any { it.name == "Rome" })
        assertTrue(choices.any { it.name == "Vatican City" })
        GlobeSelection.open(rome)
        assertEquals("Rome", LocationStore.selected.value?.city)
        assertNull(GlobeSelection.choice.value)
    }
}
