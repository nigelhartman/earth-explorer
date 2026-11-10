package com.example.earthexplorer.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The geometric subsolar point, without atmospheric refraction or twilight offsets. */
data class SolarSnapshot(
    val latitude: Double,
    val longitude: Double,
    val epochMillis: Long,
    val equationOfTimeMinutes: Double,
) {
    /** Unit direction in the native globe's X-east, Y-north, Z-Greenwich coordinates. */
    val direction: DVec3
        get() {
            val lat = Math.toRadians(latitude)
            val lon = Math.toRadians(longitude)
            return DVec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
        }

    /** A closed latitude/longitude loop whose points are perpendicular to the sunlight. */
    fun terminator(segments: Int = 180): List<Pair<Double, Double>> {
        require(segments in 12..1440) { "Terminator segments must be between 12 and 1440" }
        val sun = direction
        val east = cross(sun, DVec3(0.0, 1.0, 0.0)).let { it * (1.0 / it.length()) }
        val north = cross(sun, east)
        val points = (0 until segments).map { index ->
            val angle = index * 2.0 * PI / segments
            val point = east * cos(angle) + north * sin(angle)
            Math.toDegrees(asin(point.y.coerceIn(-1.0, 1.0))) to
                Math.toDegrees(atan2(point.x, point.z))
        }
        return points + points.first()
    }

    private fun cross(a: DVec3, b: DVec3) = DVec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )
}

/** NOAA's fractional-year approximation, suitable for a continuously updated globe overlay. */
object SolarPosition {
    const val SOURCE_URL = "https://gml.noaa.gov/grad/solcalc/solareqns.PDF"

    fun at(epochMillis: Long): SolarSnapshot {
        val utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        val hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0 + utc.nano / 3.6e12
        val yearLength = if (utc.toLocalDate().isLeapYear) 366.0 else 365.0
        val gamma = 2.0 * PI / yearLength * (utc.dayOfYear - 1 + (hours - 12.0) / 24.0)
        val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)
        // Solar noon has a zero hour angle: UTC minutes + equation + 4 * longitude = 720.
        val longitude = wrapLongitude((720.0 - hours * 60.0 - equation) / 4.0)
        return SolarSnapshot(Math.toDegrees(declination), longitude, epochMillis, equation)
    }

    private fun wrapLongitude(longitude: Double) = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

/** Shared selection metadata for the native Sun marker and the information panel. */
fun SolarSnapshot.toFeature(): GlobeFeature {
    val timestamp = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss 'UTC'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epochMillis))
    return GlobeFeature(
        id = "sun:subsolar", layer = FeatureLayer.SUN,
        latitude = latitude, longitude = longitude,
        title = "Sun · subsolar point", subtitle = "Where the Sun is directly overhead",
        observedAtMillis = epochMillis, highlighted = true,
        source = "NOAA solar equations", sourceUrl = SolarPosition.SOURCE_URL,
        details = listOf(
            FeatureDetail("Calculated for", timestamp),
            FeatureDetail("Subsolar latitude", String.format(Locale.ROOT, "%.3f°", latitude)),
            FeatureDetail("Subsolar longitude", String.format(Locale.ROOT, "%.3f°", longitude)),
            FeatureDetail("Gold boundary", "Geometric day / night terminator"),
            FeatureDetail("Dark hemisphere", "Night side of Earth"),
            FeatureDetail("Model", "NOAA approximation · atmospheric twilight and refraction not included"),
        ),
    )
}
