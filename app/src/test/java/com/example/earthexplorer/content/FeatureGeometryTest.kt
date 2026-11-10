package com.example.earthexplorer.content

import com.example.earthexplorer.data.*
import org.junit.Assert.*
import org.junit.Test

class FeatureGeometryTest {
    private fun feature(id: String = "test", lat: Double = 0.0, lon: Double = 0.0,
        layer: FeatureLayer = FeatureLayer.AIRCRAFT, altitude: Double = 0.0) =
        GlobeFeature(id, layer, lat, lon, id, altitudeMeters = altitude,
            observedAtMillis = 1L, source = "test")

    @Test fun coordinatesMatchEarthTextureBasis() {
        assertEquals(1.0, FeatureGeometry.direction(0.0, 0.0).z, 1e-12)
        assertEquals(1.0, FeatureGeometry.direction(0.0, 90.0).x, 1e-12)
        assertEquals(1.0, FeatureGeometry.direction(90.0, 0.0).y, 1e-12)
        assertEquals(-1.0, FeatureGeometry.direction(0.0, 180.0).z, 1e-12)
    }

    @Test fun pickingCrossesDatelineAndChoosesNearest() {
        val near = feature("near", lon = -179.9)
        val far = feature("far", lon = 178.5)
        assertEquals(near, FeatureGeometry.nearest(listOf(far, near), 0.0, 179.9))
    }

    @Test fun pickingDoesNotSelectDistantMarkers() {
        assertNull(FeatureGeometry.nearest(listOf(feature(lon = 3.0)), 0.0, 0.0))
        assertNull(FeatureGeometry.nearest(emptyList(), 0.0, 0.0))
    }

    @Test fun polarPickingIgnoresLongitudeSingularity() {
        val marker = feature(lat = 89.9, lon = -170.0)
        assertEquals(marker, FeatureGeometry.nearest(listOf(marker), 89.9, 20.0))
    }

    @Test fun earthquakeDepthDoesNotMoveEpicenterBelowOpaqueEarth() {
        val quake = feature(lat = 34.56, lon = 135.67, layer = FeatureLayer.EARTHQUAKES)
            .copy(depthKilometers = 600.0)
        assertEquals(1.014, FeatureGeometry.displayRadius(quake, 1.0), 1e-12)
        assertEquals(34.56, quake.latitude, 0.0)
        assertEquals(135.67, quake.longitude, 0.0)
    }

    @Test fun orbitalHeightIsCompressedAndFitsVolume() {
        val iss = feature(layer = FeatureLayer.POSITIONS, altitude = 420_000.0)
        val gps = iss.copy(altitudeMeters = 20_200_000.0)
        val high = iss.copy(altitudeMeters = 100_000_000.0)
        assertTrue(FeatureGeometry.displayRadius(iss, 1.0) > 1.0)
        assertTrue(FeatureGeometry.displayRadius(gps, 1.0) > FeatureGeometry.displayRadius(iss, 1.0))
        assertTrue(FeatureGeometry.displayRadius(high, 0.2904) < 0.44)
        assertEquals(420_000.0, iss.altitudeMeters, 0.0)
    }

    @Test fun aircraftAltitudeIsBoundedAndNonnegative() {
        assertEquals(1.015, FeatureGeometry.displayRadius(feature(altitude = -100.0), 1.0), 1e-12)
        assertEquals(1.115, FeatureGeometry.displayRadius(feature(altitude = 1e9), 1.0), 1e-12)
    }

    @Test fun rayHitsQuakeAtActualPositionDespiteElevatedAircraftCaptureShell() {
        val quake = feature("quake", lon = 60.0, layer = FeatureLayer.EARTHQUAKES)
        val position = FeatureGeometry.direction(quake.latitude, quake.longitude) * FeatureGeometry.displayRadius(quake, 1.0)
        val origin = DVec3(position.x, 0.0, 4.0)
        // The 1.14 R shell intercepts this ray at a different longitude than the epicenter.
        val shellHit = DVec3(position.x, 0.0, kotlin.math.sqrt(1.14 * 1.14 - position.x * position.x))
        val ray = requireNotNull(FeatureGeometry.rayThrough(origin, shellHit))
        assertEquals(quake, FeatureGeometry.pickRay(listOf(quake), ray, 1.0))
        val ground = requireNotNull(FeatureGeometry.pointOnEarth(ray, 1.0))
        assertEquals(position.x, ground.x, 1e-12)
        assertEquals(1.0, ground.length(), 1e-12)
    }

    @Test fun rayRejectsSatelliteBehindOpaqueEarth() {
        val satellite = feature("hidden", lon = 180.0, layer = FeatureLayer.POSITIONS, altitude = 420_000.0)
        val ray = FeaturePickRay(DVec3(0.0, 0.0, 4.0), DVec3(0.0, 0.0, -1.0))
        assertNull(FeatureGeometry.pickRay(listOf(satellite), ray, 1.0))
    }

    @Test fun webcamMarkerIsPickableAboveEarthButNotThroughIt() {
        val camera = feature("webcam", layer = FeatureLayer.WEBCAMS)
        val ray = FeaturePickRay(DVec3(0.0, 0.0, 4.0), DVec3(0.0, 0.0, -1.0))
        assertEquals(1.065, FeatureGeometry.displayRadius(camera, 1.0), 1e-12)
        assertEquals(camera, FeatureGeometry.pickRay(listOf(camera), ray, 1.0))
        assertNull(FeatureGeometry.pickRay(listOf(camera.copy(longitude = 180.0)), ray, 1.0))
    }

    @Test fun farSideSatelliteAboveTheHorizonRemainsSelectable() {
        val satellite = feature("above limb", lon = 110.0, layer = FeatureLayer.POSITIONS, altitude = 20_200_000.0)
        val position = FeatureGeometry.direction(0.0, 110.0) * FeatureGeometry.displayRadius(satellite, 1.0)
        val ray = FeaturePickRay(DVec3(position.x, 0.0, 4.0), DVec3(0.0, 0.0, -1.0))
        assertTrue(position.x > 1.0)
        assertEquals(satellite, FeatureGeometry.pickRay(listOf(satellite), ray, 1.0))
        assertNull(FeatureGeometry.pointOnEarth(ray, 1.0))
    }

    @Test fun closestVisibleMarkerWinsIndependentOfLayerOrder() {
        val quake = feature("quake", layer = FeatureLayer.EARTHQUAKES)
        val plane = feature("plane", altitude = 10_000.0)
        val ray = FeaturePickRay(DVec3(0.0, 0.0, 4.0), DVec3(0.0, 0.0, -1.0))
        assertEquals(plane, FeatureGeometry.pickRay(listOf(quake, plane), ray, 1.0))
        assertEquals(plane, FeatureGeometry.pickRay(listOf(plane, quake), ray, 1.0))
    }

    @Test fun angularlyNearbyButVisuallyDistantSatelliteDoesNotStealGroundTap() {
        val satellite = feature("high orbit", lon = 45.0, layer = FeatureLayer.POSITIONS, altitude = 20_200_000.0)
        val ground = FeatureGeometry.direction(0.0, 45.0)
        val ray = FeaturePickRay(DVec3(ground.x, 0.0, 4.0), DVec3(0.0, 0.0, -1.0))
        assertNull(FeatureGeometry.pickRay(listOf(satellite), ray, 1.0))
    }

    @Test fun rayHandlesObliqueOriginsAndNonUnitDirections() {
        val quake = feature("quake", lat = 23.0, lon = 41.0, layer = FeatureLayer.EARTHQUAKES)
        val position = FeatureGeometry.direction(quake.latitude, quake.longitude) * FeatureGeometry.displayRadius(quake, 1.0)
        val ray = FeaturePickRay(DVec3(2.0, 2.0, 4.0), position - DVec3(2.0, 2.0, 4.0))
        assertEquals(quake, FeatureGeometry.pickRay(listOf(quake), ray, 1.0))
    }

    @Test fun touchUsesThreeDimensionalDistanceAndIgnoresBackside() {
        val quake = feature("quake", layer = FeatureLayer.EARTHQUAKES)
        val back = feature("back", lon = 180.0, layer = FeatureLayer.EARTHQUAKES)
        assertEquals(quake, FeatureGeometry.pickNearPoint(listOf(back, quake), DVec3(0.0, 0.0, 1.02), 1.0))
        assertNull(FeatureGeometry.pickNearPoint(listOf(back), DVec3(0.0, 0.0, 1.02), 1.0))
        assertNull(FeatureGeometry.pickNearPoint(listOf(quake), DVec3(0.0, 0.0, 1.14), 1.0))
    }

    @Test fun invalidOrBackwardRaysDoNotProduceSpuriousSelections() {
        val quake = feature("quake", layer = FeatureLayer.EARTHQUAKES)
        assertNull(FeatureGeometry.rayThrough(DVec3(0.0, 0.0, 1.0), DVec3(0.0, 0.0, 1.0)))
        assertNull(FeatureGeometry.pointOnEarth(FeaturePickRay(DVec3(0.0, 0.0, 4.0), DVec3(0.0, 0.0, 1.0)), 1.0))
        assertNull(FeatureGeometry.pickRay(listOf(quake), FeaturePickRay(DVec3(0.0, 0.0, 4.0), DVec3(0.0, 0.0, 0.0)), 1.0))
    }
}
