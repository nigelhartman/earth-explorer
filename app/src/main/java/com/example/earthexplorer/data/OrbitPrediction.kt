package com.example.earthexplorer.data

import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** CelesTrak mean elements, for an explicitly approximate educational globe visualization. */
data class OrbitElements(
    val catalogId: Long,
    val name: String,
    val epochMillis: Long,
    val meanMotionRevolutionsPerDay: Double,
    val eccentricity: Double,
    val inclinationDegrees: Double,
    val ascendingNodeDegrees: Double,
    val argumentOfPericenterDegrees: Double,
    val meanAnomalyDegrees: Double,
) {
    init {
        require(catalogId > 0 && name.isNotBlank()) { "Missing satellite identity" }
        require(epochMillis > 0) { "Invalid orbital epoch" }
        require(meanMotionRevolutionsPerDay.isFinite() && meanMotionRevolutionsPerDay in 0.01..20.0)
        require(eccentricity.isFinite() && eccentricity >= 0.0 && eccentricity < 1.0)
        require(inclinationDegrees.isFinite() && inclinationDegrees in 0.0..180.0)
        require(listOf(ascendingNodeDegrees, argumentOfPericenterDegrees, meanAnomalyDegrees).all { it.isFinite() })
    }

    fun featureAt(timeMillis: Long): GlobeFeature? {
        // Old mean elements are not silently animated as though they were current observations.
        if (timeMillis - epochMillis > MAX_ORBIT_AGE_MILLIS || epochMillis - timeMillis > 2 * DAY_MILLIS) return null
        val position = approximatePositionAt(timeMillis)
        if (!position.altitudeMeters.isFinite() || position.altitudeMeters < 0.0) return null
        val ageHours = (timeMillis - epochMillis).coerceAtLeast(0L) / 3_600_000.0
        return GlobeFeature(
            id = "positions:$catalogId",
            layer = FeatureLayer.POSITIONS,
            latitude = position.latitude,
            longitude = position.longitude,
            title = name,
            subtitle = "GPS satellite · approximate prediction",
            altitudeMeters = position.altitudeMeters,
            observedAtMillis = timeMillis,
            details = listOf(
                FeatureDetail("Position", "Approximate Kepler prediction — not telemetry"),
                FeatureDetail("NORAD ID", catalogId.toString()),
                FeatureDetail("Altitude", String.format(Locale.US, "%,.0f km", position.altitudeMeters / 1_000.0)),
                FeatureDetail("Orbital period", String.format(Locale.US, "%.1f hours", 24.0 / meanMotionRevolutionsPerDay)),
                FeatureDetail("Orbit epoch", Instant.ofEpochMilli(epochMillis).toString()),
                FeatureDetail("Element age", String.format(Locale.US, "%.1f hours", ageHours)),
                FeatureDetail("Model", "Two-body Kepler + Earth rotation; not SGP4. For visualization, not navigation."),
            ),
            source = "CelesTrak GPS orbital elements",
            sourceUrl = PositionsLayerStore.GPS_SOURCE_URL,
        )
    }

    /** Two-body propagation omits perturbations; it must never be presented as precise tracking. */
    fun approximatePositionAt(timeMillis: Long): OrbitPosition {
        val angularSpeed = meanMotionRevolutionsPerDay * 2.0 * PI / 86_400.0
        val semimajorAxis = (EARTH_MU / (angularSpeed * angularSpeed)).pow(1.0 / 3.0)
        val mean = radiansWrapped(Math.toRadians(meanAnomalyDegrees) + angularSpeed * ((timeMillis - epochMillis) / 1_000.0))
        var eccentricAnomaly = if (eccentricity < 0.8) mean else PI
        repeat(24) {
            val correction = (eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - mean) /
                (1.0 - eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= correction
        }
        val anomaly = atan2(sqrt(1.0 - eccentricity * eccentricity) * sin(eccentricAnomaly), cos(eccentricAnomaly) - eccentricity)
        val radius = semimajorAxis * (1.0 - eccentricity * cos(eccentricAnomaly))
        val argument = Math.toRadians(argumentOfPericenterDegrees) + anomaly
        val node = Math.toRadians(ascendingNodeDegrees)
        val inclination = Math.toRadians(inclinationDegrees)
        val x = radius * (cos(node) * cos(argument) - sin(node) * sin(argument) * cos(inclination))
        val y = radius * (sin(node) * cos(argument) + cos(node) * sin(argument) * cos(inclination))
        val z = radius * sin(argument) * sin(inclination)
        return geographicPosition(x, y, z, timeMillis)
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L
        const val MAX_ORBIT_AGE_MILLIS = 14 * DAY_MILLIS
        private const val EARTH_MU = 3.986004418e14
    }
}

data class OrbitPosition(val latitude: Double, val longitude: Double, val altitudeMeters: Double)

/** Greenwich mean sidereal rotation, using UTC as a sub-second approximation to UT1. */
internal fun greenwichSiderealRadians(timeMillis: Long): Double {
    val daysSinceJ2000 = timeMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
    val centuries = daysSinceJ2000 / 36_525.0
    return radiansWrapped(Math.toRadians(
        280.46061837 + 360.98564736629 * daysSinceJ2000 +
            0.000387933 * centuries * centuries - centuries * centuries * centuries / 38_710_000.0,
    ))
}

private fun radiansWrapped(angle: Double) = ((angle % (2.0 * PI)) + 2.0 * PI) % (2.0 * PI)

private fun geographicPosition(x: Double, y: Double, z: Double, timeMillis: Long): OrbitPosition {
    val equatorialRadius = 6_378_137.0
    val eccentricitySquared = 6.69437999014e-3
    val horizontal = hypot(x, y)
    var latitude = atan2(z, horizontal * (1.0 - eccentricitySquared))
    repeat(8) {
        val normalRadius = equatorialRadius / sqrt(1.0 - eccentricitySquared * sin(latitude).pow(2.0))
        latitude = atan2(z + eccentricitySquared * normalRadius * sin(latitude), horizontal)
    }
    val normalRadius = equatorialRadius / sqrt(1.0 - eccentricitySquared * sin(latitude).pow(2.0))
    val altitude = if (abs(cos(latitude)) > 1e-8) {
        horizontal / cos(latitude) - normalRadius
    } else {
        abs(z) - equatorialRadius * sqrt(1.0 - eccentricitySquared)
    }
    val longitude = Math.toDegrees(radiansWrapped(atan2(y, x) - greenwichSiderealRadians(timeMillis) + PI) - PI)
    return OrbitPosition(Math.toDegrees(latitude), longitude, altitude)
}

/** The provider's timestamp is retained: polling does not make an old ISS response current. */
data class IssPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeKilometers: Double,
    val velocityKilometersPerHour: Double,
    val timestampMillis: Long,
    val visibility: String,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(altitudeKilometers.isFinite() && altitudeKilometers in 100.0..2_000.0)
        require(velocityKilometersPerHour.isFinite() && velocityKilometersPerHour in 1_000.0..50_000.0)
        require(timestampMillis > 0)
    }

    fun isFreshAt(nowMillis: Long): Boolean = nowMillis - timestampMillis in -60_000L..120_000L

    fun supersedes(previous: IssPosition?, nowMillis: Long): Boolean =
        isFreshAt(nowMillis) && (previous == null || timestampMillis >= previous.timestampMillis)

    fun featureAt(nowMillis: Long): GlobeFeature? {
        if (!isFreshAt(nowMillis)) return null
        val delayed = nowMillis - timestampMillis > 20_000L
        return GlobeFeature(
            id = "positions:25544",
            layer = FeatureLayer.POSITIONS,
            latitude = latitude,
            longitude = longitude,
            title = "ISS — International Space Station",
            subtitle = if (delayed) "ISS · last reported position (delayed)" else "ISS · position refreshed every 5 seconds",
            altitudeMeters = altitudeKilometers * 1_000.0,
            observedAtMillis = timestampMillis,
            details = listOf(
                FeatureDetail("NORAD ID", "25544"),
                FeatureDetail("Altitude", String.format(Locale.US, "%.1f km", altitudeKilometers)),
                FeatureDetail("Speed", String.format(Locale.US, "%,.0f km/h", velocityKilometersPerHour)),
                FeatureDetail("Sunlight", visibility.replaceFirstChar { it.uppercase() }),
                FeatureDetail("Position time", Instant.ofEpochMilli(timestampMillis).toString()),
                FeatureDetail("Tracking", "Provider-computed orbit position; checked every 5 seconds"),
            ),
            source = "Where the ISS at?",
            sourceUrl = "https://wheretheiss.at/",
            highlighted = true,
        )
    }
}
