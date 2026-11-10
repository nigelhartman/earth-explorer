package com.example.earthexplorer.data

import kotlin.math.*

/** Both values are in Earth-root local meters, before globe rotation. */
data class FeaturePickRay(val origin: DVec3, val direction: DVec3)

/** Globe coordinates and picking use the same basis as the Earth texture. */
object FeatureGeometry {
    fun direction(latitude: Double, longitude: Double): DVec3 {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        return DVec3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
    }

    fun nearest(features: Collection<GlobeFeature>, latitude: Double, longitude: Double,
        maxDegrees: Double = 2.1): GlobeFeature? {
        val point = direction(latitude, longitude)
        var bestDot = cos(Math.toRadians(maxDegrees))
        var best: GlobeFeature? = null
        features.forEach { feature ->
            val dot = point.dot(direction(feature.latitude, feature.longitude))
            if (dot > bestDot) { best = feature; bestDot = dot }
        }
        return best
    }

    fun rayThrough(origin: DVec3, hit: DVec3): FeaturePickRay? {
        if (!finite(origin) || !finite(hit)) return null
        val direction = hit - origin
        val length = direction.length()
        if (length < 1e-7) return null
        return FeaturePickRay(origin, direction * (1.0 / length))
    }

    /** Invisible input shells only capture the event; the real marker positions determine the hit. */
    fun pickRay(features: Collection<GlobeFeature>, ray: FeaturePickRay, radius: Double): GlobeFeature? {
        val normalized = normalize(ray) ?: return null
        if (!radius.isFinite() || radius <= 0.0) return null
        val earthDistance = sphereEntry(normalized, DVec3(0.0, 0.0, 0.0), radius)
            ?: Double.POSITIVE_INFINITY
        var nearestDistance = Double.POSITIVE_INFINITY
        var best: GlobeFeature? = null
        features.forEach { feature ->
            val center = direction(feature.latitude, feature.longitude) * displayRadius(feature, radius)
            val distance = sphereEntry(normalized, center, markerHitRadius(feature, radius)) ?: return@forEach
            if (distance >= nearestDistance || distance > earthDistance + radius * 1e-6) return@forEach
            // A broad hit target must not make an actually hidden marker selectable through Earth.
            val toCenter = center - normalized.origin
            val centerDistance = toCenter.length()
            if (centerDistance > 1e-7) {
                val centerRay = FeaturePickRay(normalized.origin, toCenter * (1.0 / centerDistance))
                val obstruction = sphereEntry(centerRay, DVec3(0.0, 0.0, 0.0), radius)
                if (obstruction != null && obstruction < centerDistance - radius * 1e-6) return@forEach
            }
            nearestDistance = distance
            best = feature
        }
        return best
    }

    /** Correct geographic fallback after a ray first hits an elevated invisible capture shell. */
    fun pointOnEarth(ray: FeaturePickRay, radius: Double): DVec3? {
        val normalized = normalize(ray) ?: return null
        if (!radius.isFinite() || radius <= 0.0) return null
        val distance = sphereEntry(normalized, DVec3(0.0, 0.0, 0.0), radius) ?: return null
        return normalized.origin + normalized.direction * distance
    }

    /** Direct finger pokes use local 3D proximity, never a projection to the opposite globe surface. */
    fun pickNearPoint(features: Collection<GlobeFeature>, point: DVec3, radius: Double): GlobeFeature? {
        if (!finite(point) || !radius.isFinite() || radius <= 0.0) return null
        var bestDistance = Double.POSITIVE_INFINITY
        var best: GlobeFeature? = null
        features.forEach { feature ->
            val center = direction(feature.latitude, feature.longitude) * displayRadius(feature, radius)
            val distance = (center - point).length()
            if (distance <= markerHitRadius(feature, radius) + radius * 0.012 && distance < bestDistance) {
                // The small reach tolerance must not cross through the opaque Earth's interior.
                val segment = rayThrough(point, center)
                val obstruction = segment?.let { sphereEntry(it, DVec3(0.0, 0.0, 0.0), radius) }
                if (obstruction != null && obstruction < distance - radius * 1e-6) return@forEach
                bestDistance = distance
                best = feature
            }
        }
        return best
    }

    private fun markerHitRadius(feature: GlobeFeature, radius: Double): Double = radius * when (feature.layer) {
        FeatureLayer.AIRCRAFT -> 0.022
        FeatureLayer.EARTHQUAKES -> 0.006 + maxOf(0.009,
            0.005 * (1.0 + ((feature.magnitude ?: 5.4) - 5.4).coerceIn(0.0, 4.0) * 0.65))
        FeatureLayer.POSITIONS -> if (feature.highlighted) 0.07 else 0.025
        FeatureLayer.SUN -> 0.035
        FeatureLayer.WEBCAMS -> 0.028
    }

    private fun normalize(ray: FeaturePickRay): FeaturePickRay? {
        if (!finite(ray.origin) || !finite(ray.direction)) return null
        val length = ray.direction.length()
        if (length < 1e-7) return null
        return FeaturePickRay(ray.origin, ray.direction * (1.0 / length))
    }

    /** Closest nonnegative distance along a normalized ray, including origins already inside a target. */
    private fun sphereEntry(ray: FeaturePickRay, center: DVec3, radius: Double): Double? {
        val offset = ray.origin - center
        val c = offset.dot(offset) - radius * radius
        if (c <= 0.0) return 0.0
        val b = offset.dot(ray.direction)
        if (b > 0.0) return null
        val discriminant = b * b - c
        if (discriminant < 0.0) return null
        return (-b - sqrt(discriminant)).takeIf { it >= 0.0 }
    }

    private fun finite(point: DVec3) = point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    /** Great-circle separation of two coordinates in degrees. */
    fun angularDistanceDegrees(latitude: Double, longitude: Double, otherLatitude: Double, otherLongitude: Double): Double {
        val a = Math.toRadians(latitude); val b = Math.toRadians(otherLatitude)
        val cosine = (sin(a) * sin(b) + cos(a) * cos(b) * cos(Math.toRadians(longitude - otherLongitude))).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    /** Orbital heights are compressed to fit the existing window, never represented as true scale. */
    fun displayRadius(feature: GlobeFeature, radius: Double): Double = when (feature.layer) {
        FeatureLayer.AIRCRAFT -> radius * (1.015 +
            (feature.altitudeMeters.coerceAtLeast(0.0) / 6_371_000 * 45).coerceAtMost(0.10))
        FeatureLayer.EARTHQUAKES -> radius * 1.014
        FeatureLayer.SUN -> radius * 1.30
        FeatureLayer.WEBCAMS -> radius * 1.065
        FeatureLayer.POSITIONS -> radius * (1.0 + maxOf(0.07,
            0.34 * ln1p(feature.altitudeMeters.coerceAtLeast(0.0) / 400_000) / ln1p(100.0)).coerceAtMost(0.36))
    }
}
