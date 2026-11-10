package com.example.earthexplorer.content

import com.example.earthexplorer.data.CityCenter
import com.example.earthexplorer.data.NativeTileBounds
import com.example.earthexplorer.data.NativeTileCacheEntry
import com.example.earthexplorer.data.NativeTileCachePolicy
import com.example.earthexplorer.data.nativeTileCacheBudget
import org.junit.Assert.*
import org.junit.Test

class NativeTileCachePolicyTest {
    private fun tile(id: String, east: Double, bytes: Long = 4, lastVisible: Long = 0) =
        NativeTileCacheEntry(id, bytes, NativeTileBounds(east - 20, east + 20, -20.0, 20.0), lastVisible)

    @Test fun recentlyRenderedNeighborSurvivesWhenWithinBudget() {
        val entries = listOf(tile("visible", 0.0), tile("previous-view", -250.0), tile("next-view", 250.0))
        assertTrue(NativeTileCachePolicy(12).evictions(entries, setOf("visible"), CityCenter()).isEmpty())
    }

    @Test fun advancingViewReplacesFarthestNativeTilesBeforeNearestHalo() {
        val entries = listOf(tile("previous", -500.0), tile("visible", 300.0), tile("next", 550.0))
        assertEquals(setOf("previous"), NativeTileCachePolicy(8)
            .evictions(entries, setOf("visible"), CityCenter(300.0, 0.0)))
    }

    @Test fun speculativeContentCannotEvictTheVisibleViewEvenWhenItExceedsBudget() {
        val entries = listOf(tile("visible", 0.0, 12), tile("speculative", 200.0))
        assertEquals(setOf("speculative"), NativeTileCachePolicy(8)
            .evictions(entries, setOf("visible"), CityCenter()))
    }

    @Test fun jumpDropsOldCityAreaEvenWithSpareBudget() {
        val entries = listOf(tile("old", -2_000.0), tile("current", 0.0))
        assertEquals(setOf("old"), NativeTileCachePolicy(100)
            .evictions(entries, setOf("current"), CityCenter()))
    }

    @Test fun nativeResourceCountIsBoundedSeparatelyFromBytes() {
        val entries = listOf(tile("visible", 0.0), tile("near", 200.0), tile("far", 500.0))
        assertEquals(setOf("far"), NativeTileCachePolicy(100, maxTiles = 2)
            .evictions(entries, setOf("visible"), CityCenter()))
    }

    @Test fun equalDistanceRetainsMostRecentlyVisibleTiles() {
        val entries = listOf(tile("old", -200.0, lastVisible = 1), tile("recent", 200.0, lastVisible = 2))
        assertEquals(setOf("old"), NativeTileCachePolicy(4).evictions(entries, emptySet(), CityCenter()))
    }

    @Test fun footprintUsesNorthAsNegativeZ() {
        val bounds = NativeTileBounds(0.0, 100.0, -500.0, -400.0)
        assertEquals(0.0, bounds.distance(CityCenter(50.0, 450.0)), 0.0)
        assertTrue(bounds.visible(CityCenter(50.0, 450.0)))
        assertFalse(bounds.visible(CityCenter(50.0, -450.0)))
    }

    @Test fun nativeBudgetLeavesHeapSpaceAndIsCapped() {
        assertEquals(256L * 1024 * 1024, nativeTileCacheBudget(2L * 1024 * 1024 * 1024))
        assertEquals(32L * 1024 * 1024, nativeTileCacheBudget(96L * 1024 * 1024))
    }
}
