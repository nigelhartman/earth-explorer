package com.example.earthexplorer.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobeCoordinateTest {
    @Test
    fun coordinateFromVector_mapsCardinalDirections() {
        assertCoordinate(0.0, 0.0, coordinateFromVector(0f, 0f, 1f))
        assertCoordinate(0.0, 90.0, coordinateFromVector(1f, 0f, 0f))
        assertCoordinate(90.0, 0.0, coordinateFromVector(0f, 1f, 0f))
        assertCoordinate(0.0, -90.0, coordinateFromVector(-1f, 0f, 0f))
    }

    @Test
    fun coordinateFromVector_ignoresMagnitude() {
        assertCoordinate(0.0, 45.0, coordinateFromVector(10f, 0f, 10f))
    }

    @Test
    fun coordinateFromVector_rejectsZeroVector() {
        assertNull(coordinateFromVector(0f, 0f, 0f))
    }

    private fun assertCoordinate(
        expectedLatitude: Double,
        expectedLongitude: Double,
        actual: GlobeCoordinate?,
    ) {
        requireNotNull(actual)
        assertEquals(expectedLatitude, actual.latitude, 0.0001)
        assertEquals(expectedLongitude, actual.longitude, 0.0001)
    }
}
