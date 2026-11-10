package com.example.earthexplorer.content

import com.example.earthexplorer.data.AircraftObservation
import com.example.earthexplorer.data.AircraftRequestWindow
import com.example.earthexplorer.data.FeatureLayer
import org.junit.Assert.*
import org.junit.Test

class AircraftLayerTest {
    private val observation = AircraftObservation(
        icao24 = "3C6444", callsign = "  DLH123  ", country = "Germany", positionTimeSeconds = 1_000,
        longitude = 13.4, latitude = 52.5, barometricAltitude = 10_000.0, geometricAltitude = 10_150.0,
        onGround = false, speedMetersPerSecond = 250.0, trackDegrees = 95.0,
        verticalMetersPerSecond = 5.0, squawk = "1234", positionSource = 0,
    )

    @Test fun mapsOnlyRealOpenSkyFieldsWithoutInventingRoutes() {
        val aircraft = requireNotNull(observation.feature(1_010))
        assertEquals("aircraft:3c6444", aircraft.id)
        assertEquals(FeatureLayer.AIRCRAFT, aircraft.layer)
        assertEquals("DLH123", aircraft.title)
        assertEquals("Germany", aircraft.subtitle)
        assertEquals(10_150.0, aircraft.altitudeMeters, 0.0)
        assertEquals(95.0, aircraft.headingDegrees, 0.0)
        assertEquals(1_000_000L, aircraft.observedAtMillis)
        assertEquals("OpenSky Network", aircraft.source)
        assertTrue(aircraft.details.any { it.label == "Ground speed" && it.value == "486 kt / 900 km/h" })
        assertTrue(aircraft.details.any { it.label == "ICAO country" && it.value == "Germany" })
        assertFalse(aircraft.details.any { it.label.contains("destination", true) || it.label.contains("origin", true) })
    }

    @Test fun rejectsMissingInvalidAndStalePositions() {
        assertNull(observation.copy(latitude = null).feature(1_010))
        assertNull(observation.copy(latitude = Double.NaN).feature(1_010))
        assertNull(observation.copy(latitude = 91.0).feature(1_010))
        assertNull(observation.copy(longitude = 181.0).feature(1_010))
        assertNull(observation.copy(positionTimeSeconds = null).feature(1_010))
        assertNull(observation.feature(1_121))
        assertNull(observation.copy(positionTimeSeconds = 1_050).feature(1_010))
        assertNull(observation.copy(icao24 = "unknown").feature(1_010))
    }

    @Test fun preservesRealGroundAndZeroCoordinateObservations() {
        val aircraft = requireNotNull(observation.copy(latitude = 0.0, longitude = 0.0, onGround = true).feature(1_010))
        assertEquals(0.0, aircraft.latitude, 0.0)
        assertEquals(0.0, aircraft.longitude, 0.0)
        assertTrue(aircraft.details.any { it.label == "Status" && it.value == "On ground" })
    }

    @Test fun missingOrInvalidOptionalTelemetryIsNotInventedInDetails() {
        val aircraft = requireNotNull(observation.copy(
            callsign = " ", geometricAltitude = Double.NaN, barometricAltitude = null,
            trackDegrees = -30.0, speedMetersPerSecond = -2.0, verticalMetersPerSecond = Double.POSITIVE_INFINITY,
        ).feature(1_010))
        assertEquals("3C6444", aircraft.title)
        assertEquals(0.0, aircraft.altitudeMeters, 0.0)
        assertFalse(aircraft.details.any { it.label.contains("altitude") || it.label == "Ground speed" || it.label == "Ground track" || it.label == "Vertical speed" })
    }

    @Test fun refreshesHaveAMinimumSixtySecondCooldown() {
        val gate = AircraftRequestWindow()
        assertTrue(gate.claim(5_000))
        assertFalse(gate.claim(5_001))
        assertFalse(gate.claim(64_999))
        assertEquals(1L, gate.remainingMillis(64_999))
        assertTrue(gate.claim(65_000))
        assertFalse(gate.claim(65_001))
    }

    @Test fun providerRetryAfterExtendsTheCooldown() {
        val gate = AircraftRequestWindow()
        assertTrue(gate.claim(0))
        gate.defer(500, 300_000)
        assertFalse(gate.claim(60_001))
        assertFalse(gate.claim(300_499))
        assertTrue(gate.claim(300_500))
    }
}
