package com.example.earthexplorer.content

import com.example.earthexplorer.data.IssPosition
import com.example.earthexplorer.data.OrbitElements
import com.example.earthexplorer.data.greenwichSiderealRadians
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class OrbitPredictionTest {
    private val epoch = Instant.parse("2000-01-01T12:00:00Z").toEpochMilli()
    private val circular = OrbitElements(12345, "Test GPS", epoch, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test fun siderealAngleMatchesJ2000Reference() {
        assertEquals(280.46061837, Math.toDegrees(greenwichSiderealRadians(epoch)), 1e-8)
    }

    @Test fun equatorialOrbitConvertsInertialCoordinatesToEarthFixedLongitude() {
        val position = circular.approximatePositionAt(epoch)
        val angularSpeed = 2.0 * 2.0 * PI / 86_400.0
        val orbitalRadius = (3.986004418e14 / angularSpeed.pow(2.0)).pow(1.0 / 3.0)
        assertEquals(0.0, position.latitude, 1e-10)
        assertEquals(79.53938163, position.longitude, 1e-8)
        assertEquals(orbitalRadius - 6_378_137.0, position.altitudeMeters, 1e-5)
    }

    @Test fun positionMovesOverTimeAndRetainsNormalizedLongitude() {
        val before = circular.approximatePositionAt(epoch)
        val after = circular.approximatePositionAt(epoch + 1_000L)
        assertTrue(abs(after.longitude - before.longitude) > 0.001)
        repeat(100) { step ->
            val position = circular.approximatePositionAt(epoch + step * 17_000_000L)
            assertTrue(position.longitude >= -180.0 && position.longitude < 180.0)
            assertTrue(position.latitude in -90.0..90.0)
            assertTrue(position.altitudeMeters.isFinite())
        }
    }

    @Test fun inclinedOrbitReachesNorthernAndSouthernLatitudeExtrema() {
        val inclined = circular.copy(inclinationDegrees = 55.0)
        val quarterPeriodMillis = 3 * 60 * 60 * 1_000L
        assertEquals(55.0, inclined.approximatePositionAt(epoch + quarterPeriodMillis).latitude, 0.1)
        assertEquals(-55.0, inclined.approximatePositionAt(epoch + 3 * quarterPeriodMillis).latitude, 0.1)
    }

    @Test fun polarOrbitHasFiniteGeodeticAltitudeAtPole() {
        val polar = circular.copy(inclinationDegrees = 90.0, meanAnomalyDegrees = 90.0)
        val position = polar.approximatePositionAt(epoch)
        assertEquals(90.0, position.latitude, 1e-8)
        assertTrue(position.altitudeMeters.isFinite())
        assertTrue(position.altitudeMeters > 20_000_000.0)
    }

    @Test fun eccentricOrbitHasExpectedApogeeAndPerigeeRatio() {
        val eccentric = circular.copy(eccentricity = 0.1)
        val perigee = eccentric.approximatePositionAt(epoch).altitudeMeters + 6_378_137.0
        val apogee = eccentric.approximatePositionAt(epoch + 6 * 60 * 60 * 1_000L).altitudeMeters + 6_378_137.0
        assertEquals(1.1 / 0.9, apogee / perigee, 1e-9)
    }

    @Test fun oldOrFutureElementsAreNotPresentedAsCurrentPositions() {
        assertNull(circular.featureAt(epoch + OrbitElements.MAX_ORBIT_AGE_MILLIS + 1L))
        assertNull(circular.featureAt(epoch - 3 * OrbitElements.DAY_MILLIS))
        assertNotNull(circular.featureAt(epoch + 3 * OrbitElements.DAY_MILLIS))
    }

    @Test fun predictionsClearlyIdentifySourceEpochAndApproximateModel() {
        val feature = requireNotNull(circular.featureAt(epoch + 60_000L))
        assertEquals("positions:12345", feature.id)
        assertFalse(feature.highlighted)
        assertTrue(feature.subtitle.contains("approximate"))
        assertTrue(feature.details.any { it.label == "Orbit epoch" && it.value == "2000-01-01T12:00:00Z" })
        assertTrue(feature.details.any { it.value.contains("not telemetry") })
        assertEquals(epoch + 60_000L, feature.observedAtMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPhysicalOrbitElementsAreRejected() {
        circular.copy(eccentricity = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteOrbitElementsAreRejected() {
        circular.copy(meanMotionRevolutionsPerDay = Double.NaN)
    }

    @Test fun issUsesProviderTimestampAndRemainsHighlighted() {
        val report = IssPosition(10.0, 20.0, 420.0, 27_500.0, epoch, "daylight")
        val feature = requireNotNull(report.featureAt(epoch + 5_000L))
        assertEquals("positions:25544", feature.id)
        assertTrue(feature.highlighted)
        assertEquals(epoch, feature.observedAtMillis)
        assertEquals(420_000.0, feature.altitudeMeters, 0.0)
        assertEquals(10.0, feature.latitude, 0.0)
        assertEquals(20.0, feature.longitude, 0.0)
    }

    @Test fun issReportsBecomeDelayedThenExpireWithoutInventedMotion() {
        val report = IssPosition(10.0, 20.0, 420.0, 27_500.0, epoch, "daylight")
        val delayed = requireNotNull(report.featureAt(epoch + 30_000L))
        assertTrue(delayed.subtitle.contains("delayed"))
        assertEquals(epoch, delayed.observedAtMillis)
        assertEquals(20.0, delayed.longitude, 0.0)
        assertNull(report.featureAt(epoch + 120_001L))
        assertNull(report.featureAt(epoch - 60_001L))
    }

    @Test fun outOfOrderIssResponsesDoNotReplaceANewerProviderPosition() {
        val latest = IssPosition(10.0, 20.0, 420.0, 27_500.0, epoch, "daylight")
        val older = latest.copy(timestampMillis = epoch - 5_000L)
        assertTrue(older.isFreshAt(epoch + 1_000L))
        assertFalse(older.supersedes(latest, epoch + 1_000L))
        assertTrue(latest.supersedes(older, epoch + 1_000L))
        assertTrue(latest.supersedes(latest, epoch + 1_000L))
        assertFalse(latest.supersedes(null, epoch + 120_001L))
    }
}
