package com.example.earthexplorer.content

import com.example.earthexplorer.data.SolarPosition
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SolarPositionTest {
    private fun solar(value: String) = SolarPosition.at(Instant.parse(value).toEpochMilli())

    @Test fun equinoxSunIsNearEquatorAndGreenwichAtNoon() {
        val sun = solar("2026-03-20T12:00:00Z")
        assertEquals(0.0, sun.latitude, 1.0)
        assertEquals(0.0, sun.longitude, 3.0)
        assertEquals(1.0, sun.direction.length(), 1e-12)
        assertTrue(sun.direction.z > 0.99)
    }

    @Test fun solsticeDeclinationsMatchEarthsObliquity() {
        assertEquals(23.44, solar("2026-06-21T12:00:00Z").latitude, 0.25)
        assertEquals(-23.44, solar("2026-12-21T12:00:00Z").latitude, 0.25)
    }

    @Test fun solarNoonMovesWestAsUtcTimeAdvances() {
        val morning = solar("2026-09-21T06:00:00Z")
        val noon = solar("2026-09-21T12:00:00Z")
        val evening = solar("2026-09-21T18:00:00Z")
        assertEquals(90.0, morning.longitude - noon.longitude, 0.1)
        assertEquals(90.0, noon.longitude - evening.longitude, 0.1)
        assertTrue(morning.direction.x > 0.99)
        assertTrue(evening.direction.x < -0.99)
    }

    @Test fun terminatorIsClosedAndPerpendicularToSunAtEveryVertex() {
        listOf("2026-03-20T00:00:00Z", "2026-06-21T12:00:00Z", "2026-12-21T18:00:00Z").forEach { date ->
            val sun = solar(date)
            val points = sun.terminator(120)
            assertEquals(121, points.size)
            assertEquals(points.first(), points.last())
            points.forEach { (latitude, longitude) ->
                assertTrue(latitude in -90.0..90.0)
                assertTrue(longitude in -180.0..180.0)
                val lat = Math.toRadians(latitude)
                val lon = Math.toRadians(longitude)
                val dot = sun.direction.x * cos(lat) * sin(lon) + sun.direction.y * sin(lat) +
                    sun.direction.z * cos(lat) * cos(lon)
                assertEquals("Terminator vertex for $date", 0.0, dot, 1e-12)
            }
        }
    }

    @Test fun utcOffsetDoesNotChangeSolarPositionForTheSameInstant() {
        assertEquals(solar("2026-09-21T12:30:12.345Z"), solar("2026-09-21T14:30:12.345+02:00"))
    }

    @Test fun leapDayAndYearRolloverRemainFiniteAndContinuous() {
        val before = solar("2024-02-28T12:00:00Z")
        val leapDay = solar("2024-02-29T12:00:00Z")
        val after = solar("2024-03-01T12:00:00Z")
        assertTrue(leapDay.latitude > before.latitude)
        assertTrue(after.latitude > leapDay.latitude)
        assertTrue(abs(after.latitude - before.latitude) < 1.0)
        val end = solar("2024-12-31T23:59:59Z")
        val start = solar("2025-01-01T00:00:00Z")
        assertEquals(end.latitude, start.latitude, 0.05)
        assertEquals(end.direction.x, start.direction.x, 0.001)
        assertEquals(end.direction.z, start.direction.z, 0.001)
    }

    @Test fun longitudesStayWrappedAcrossEveryHour() {
        val start = Instant.parse("2026-06-21T00:00:00Z").toEpochMilli()
        repeat(48) { hour ->
            val sun = SolarPosition.at(start + hour * 3_600_000L)
            assertTrue(sun.longitude >= -180.0 && sun.longitude < 180.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun terminatorRejectsInvalidResolution() { solar("2026-06-21T12:00:00Z").terminator(0) }
}
