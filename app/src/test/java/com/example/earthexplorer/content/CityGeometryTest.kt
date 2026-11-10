package com.example.earthexplorer.content

import com.example.earthexplorer.data.*
import org.junit.Assert.*
import org.junit.Test

class CityGeometryTest {
    @Test fun earthCoordinatesRebaseWithSubMillimeterPrecision() {
        val frame = GeoFrame(52.52, 13.405)
        val local = frame.local(frame.origin + frame.east * 12.25 + frame.north * 8.5 + frame.up * 35.0)
        assertEquals(12.25, local.x, 1e-6)
        assertEquals(35.0, local.y, 1e-6)
        assertEquals(-8.5, local.z, 1e-6)
        val (latitude, longitude) = frame.coordinate(CityCenter())
        assertEquals(52.52, latitude, 1e-8)
        assertEquals(13.405, longitude, 1e-8)
    }

    @Test fun dragMovesViewCenterWithoutChangingGeometryOrientation() {
        val center = CityCenter().dragged(200f, -100f)
        assertEquals(-90.0, center.east, 1e-8)
        assertEquals(-65.0, center.north, 1e-8)
        val recentered = center.dragged(-200f, 100f)
        assertEquals(CityCenter(), recentered)
    }

    @Test fun panningUpdatesCoordinatesAcrossTheDateLine() {
        val frame = GeoFrame(0.0, 179.999)
        val (latitude, longitude) = frame.coordinate(CityCenter(1000.0, 0.0))
        assertEquals(0.0, latitude, 1e-8)
        assertTrue(longitude < -179.9)
    }

    @Test fun depthDragPansTheGroundPlaneWithoutChangingEast() {
        val center = CityCenter().dragged(0f, 0f, 100f)
        assertEquals(0.0, center.east, 0.0)
        assertEquals(65.0, center.north, 1e-8)
        assertEquals(CityCenter(), center.dragged(0f, 0f, -100f))
    }

    @Test fun fallbackHitTargetStaysInsideEveryPhysicalVolumeFace() {
        for (side in listOf(0.55, 0.76, 1.1, 2.0)) {
            val bounds = ExplorerHitBox.withinVolume(side)
            for ((center, size) in listOf(bounds.center.x to bounds.size.x,
                bounds.center.y to bounds.size.y, bounds.center.z to bounds.size.z)) {
                assertTrue(center - size/2 > -side/2)
                assertTrue(center + size/2 < side/2)
            }
        }
    }

    @Test fun gltfYUpToEcefThenLocalPreservesCoordinates() {
        val frame = GeoFrame(52.52, 13.405)
        val p = frame.origin
        val node = DMatrix.trs(doubleArrayOf(p.x,p.z,-p.y), doubleArrayOf(0.0,0.0,0.0,1.0), doubleArrayOf(1.0,1.0,1.0))
        val origin = (DMatrix.Y_UP_TO_Z_UP * node).point(DVec3(0.0,0.0,0.0))
        assertEquals(0.0, frame.local(origin).length(), 1e-8)
    }
}
