package com.example.earthexplorer.content

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class SceneTransitionGeometryTest {
    @Test fun fiveHundredTexturedPiecesReconstructTheOriginalSphere() {
        val pieces = earthFragmentGeometry(0.33f)
        assertEquals(500, pieces.size)
        pieces.forEach { piece ->
            assertTrue(piece.indices.isNotEmpty())
            piece.vertices.forEach { vertex ->
                val x = vertex.point.x + piece.center.x
                val y = vertex.point.y + piece.center.y
                val z = vertex.point.z + piece.center.z
                assertEquals(0.33f, sqrt(x*x + y*y + z*z), 1e-6f)
                assertTrue(vertex.u in 0f..1f)
                assertTrue(vertex.v in 0f..1f)
            }
        }
        assertEquals(0f, pieces.first().vertices.first().u, 0f)
        assertEquals(1f, pieces.first().vertices.first().v, 0f)
        assertEquals(1f, pieces.last().vertices.last().u, 0f)
        assertEquals(0f, pieces.last().vertices.last().v, 0f)
    }

    @Test fun everyEarthPieceLandsOnAFloorCellInsideTheVolume() {
        val cells = (0 until SCENE_FRAGMENT_COUNT).map(::cityCellFor)
        assertEquals(SCENE_CITY_CELLS, cells.toSet().size)
        assertTrue(cells.all { it in 0 until SCENE_CITY_CELLS })
        // Rows 0 and 1 of the same column converge on one cell; row 2 starts the next cell row.
        assertEquals(cityCellFor(12), cityCellFor(12 + SCENE_FRAGMENT_COLUMNS))
        assertEquals(cityCellFor(12) + SCENE_FRAGMENT_COLUMNS, cityCellFor(12 + 2 * SCENE_FRAGMENT_COLUMNS))
        for (cell in 0 until SCENE_CITY_CELLS) {
            val center = cityFragmentCenter(cell, 2f)
            assertTrue(center.x > -1f && center.x < 1f)
            assertTrue(center.z > -1f && center.z < 1f)
            assertEquals(SCENE_FLOOR_Y, center.y, 0f)
        }
    }
}
