package com.example.earthexplorer.content

import com.example.earthexplorer.data.CityCenter
import com.example.earthexplorer.data.CityView
import com.example.earthexplorer.data.MeshNeighborhood
import com.example.earthexplorer.data.MeshRegion
import org.junit.Assert.*
import org.junit.Test

class MeshNeighborhoodTest {
    @Test fun neighborhoodContainsFortyNineEqualAdjacentSquaresWithoutDuplicates() {
        val neighborhood = MeshNeighborhood.around(CityCenter())
        assertEquals(MeshRegion(0, 0), neighborhood.central)
        assertEquals(neighborhood.central, neighborhood.regions.first())
        assertEquals(3, MeshNeighborhood.RADIUS)
        assertEquals(7, MeshNeighborhood.DIAMETER)
        assertEquals(49, MeshNeighborhood.REGION_COUNT)
        assertEquals(MeshNeighborhood.REGION_COUNT, neighborhood.regions.size)
        assertEquals(MeshNeighborhood.REGION_COUNT, neighborhood.regions.toSet().size)
        assertEquals((-3..3).toSet(), neighborhood.regions.map { it.eastIndex }.toSet())
        assertEquals((-3..3).toSet(), neighborhood.regions.map { it.northIndex }.toSet())
        assertEquals(2_240.0, MeshNeighborhood.WIDTH_METERS, 0.0)
        assertEquals(MeshNeighborhood.WIDTH_METERS,
            neighborhood.regions.maxOf { it.east } - neighborhood.regions.minOf { it.west }, 0.0)
        assertEquals(MeshNeighborhood.WIDTH_METERS,
            neighborhood.regions.maxOf { it.north } - neighborhood.regions.minOf { it.south }, 0.0)
        for (region in neighborhood.regions) {
            assertEquals(CityView.HALF_WIDTH * 2, region.east - region.west, 0.0)
            assertEquals(CityView.HALF_WIDTH * 2, region.north - region.south, 0.0)
        }
        assertEquals(neighborhood.central.east, MeshRegion(1, 0).west, 0.0)
        assertEquals(neighborhood.central.north, MeshRegion(0, 1).south, 0.0)
    }

    @Test fun movementsWithinCellReuseTheSameNeighborhood() {
        val original = MeshNeighborhood.around(CityCenter())
        val dragged = MeshNeighborhood.around(CityCenter(159.99, -160.0))
        assertEquals(original.central, dragged.central)
        assertEquals(original.regions.toSet(), dragged.regions.toSet())
    }

    @Test fun crossingBoundaryKeepsFortyTwoCellsAndPrefetchesOnlyTheNewColumn() {
        val old = MeshNeighborhood.around(CityCenter(159.0, 0.0))
        val next = MeshNeighborhood.around(CityCenter(160.0, 0.0))
        assertEquals(MeshRegion(1, 0), next.central)
        assertEquals(42, old.regions.intersect(next.regions.toSet()).size)
        assertEquals((-3..3).map { MeshRegion(4, it) }.toSet(),
            next.regions.toSet() - old.regions.toSet())
    }

    @Test fun crossingEachBoundaryRetainsFortyTwoCellsAndPrefetchesOneOuterStrip() {
        val original = MeshNeighborhood.around(CityCenter()).regions.toSet()
        for (direction in listOf(MeshRegion(1, 0), MeshRegion(-1, 0),
            MeshRegion(0, 1), MeshRegion(0, -1))) {
            val next = MeshNeighborhood.around(direction.center)
            assertEquals(direction, next.central)
            assertEquals(42, original.intersect(next.regions.toSet()).size)
            val newRegions = next.regions.toSet() - original
            assertEquals(7, newRegions.size)
            assertTrue(newRegions.all {
                if (direction.eastIndex != 0) it.eastIndex == direction.eastIndex * 4
                else it.northIndex == direction.northIndex * 4
            })
        }
    }

    @Test fun negativeCoordinatesUseFloorInsteadOfTruncation() {
        assertEquals(MeshRegion(-1, -1), MeshRegion.containing(CityCenter(-160.01, -479.99)))
        assertEquals(MeshRegion(-2, -2), MeshRegion.containing(CityCenter(-480.01, -480.01)))
    }

    @Test fun diagonalMovementRetainsThirtySixCellsAndReplenishesThirteen() {
        val old = MeshNeighborhood.around(CityCenter())
        for (east in listOf(-320.0, 320.0)) {
            for (north in listOf(-320.0, 320.0)) {
                val next = MeshNeighborhood.around(CityCenter(east, north))
                assertEquals(36, old.regions.intersect(next.regions.toSet()).size)
                assertEquals(13, (next.regions.toSet() - old.regions.toSet()).size)
            }
        }
    }

    @Test fun initialCellIsFirstAndClosestNeighborToDragDirectionFollows() {
        val neighborhood = MeshNeighborhood.around(CityCenter(150.0, 0.0))
        assertEquals(MeshRegion(0, 0), neighborhood.regions[0])
        assertEquals(MeshRegion(1, 0), neighborhood.regions[1])
    }

    @Test fun allPrefetchRegionsAreOrderedByDistanceToTheActualViewCenter() {
        for (center in listOf(CityCenter(150.0, 120.0), CityCenter(-159.99, -140.0),
            CityCenter(471.0, -800.0))) {
            val neighborhood = MeshNeighborhood.around(center)
            assertEquals(neighborhood.central, neighborhood.regions.first())
            val distances = neighborhood.regions.drop(1).map {
                val dx = it.center.east - center.east
                val dy = it.center.north - center.north
                dx * dx + dy * dy
            }
            assertEquals(distances.sorted(), distances)
        }
    }

    @Test fun nearbyFullRingsFinishBeforeDistantRegions() {
        val neighborhood = MeshNeighborhood.around(CityCenter())
        assertEquals((-1..1).flatMap { north ->
            (-1..1).map { east -> MeshRegion(east, north) }
        }.toSet(), neighborhood.regions.take(9).toSet())
    }

    @Test fun allPositionsWithinCentralCellHaveTheirWholeViewCovered() {
        val neighborhood = MeshNeighborhood.around(CityCenter())
        for (x in listOf(-160.0, 0.0, 159.99)) {
            for (y in listOf(-160.0, 0.0, 159.99)) {
                val center = CityCenter(x, y)
                val touched = neighborhood.regions.filter { it.intersectsView(center) }
                assertTrue(touched.isNotEmpty())
                assertTrue(touched.minOf { it.west } <= center.east - CityView.HALF_WIDTH)
                assertTrue(touched.maxOf { it.east } >= center.east + CityView.HALF_WIDTH)
                assertTrue(touched.minOf { it.south } <= center.north - CityView.HALF_WIDTH)
                assertTrue(touched.maxOf { it.north } >= center.north + CityView.HALF_WIDTH)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCoordinatesCannotCreateAnUnboundedNeighborhood() {
        MeshNeighborhood.around(CityCenter(Double.NaN, 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun regionsAtTheIntegerBoundaryCannotWrapTheExpandedGrid() {
        MeshNeighborhood.around(CityCenter((Int.MAX_VALUE - 1).toDouble() * MeshRegion.SIDE, 0.0))
    }
}
