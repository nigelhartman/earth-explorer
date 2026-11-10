package com.example.earthexplorer.content

import com.example.earthexplorer.data.MeshTileCache
import com.example.earthexplorer.data.meshTileCacheBudget
import org.junit.Assert.*
import org.junit.Test

class MeshTileCacheTest {
    @Test fun boundedCacheEvictsLeastRecentlyUsedContent() {
        val cache = MeshTileCache(10)
        val first = ByteArray(4)
        cache.put("first", first)
        cache.put("second", ByteArray(4))
        assertSame(first, cache.get("first"))
        cache.put("third", ByteArray(4))
        assertTrue(cache.contains("first"))
        assertFalse(cache.contains("second"))
        assertTrue(cache.contains("third"))
        assertEquals(8L, cache.stats().bytes)
        assertEquals(10L, cache.stats().budgetBytes)
    }

    @Test fun oldNeighborhoodEvictsBeforeEvenOlderCurrentNeighborhood() {
        val cache = MeshTileCache(8)
        cache.put("current", ByteArray(4))
        cache.put("previous", ByteArray(4))
        cache.prefer(setOf("current", "incoming"))
        cache.put("incoming", ByteArray(4))
        assertTrue(cache.contains("current"))
        assertFalse(cache.contains("previous"))
        assertTrue(cache.contains("incoming"))
    }

    @Test fun oversizedNeighborhoodStillHonorsHardMemoryBound() {
        val cache = MeshTileCache(8)
        cache.prefer(setOf("first", "second", "third"))
        cache.put("first", ByteArray(4))
        cache.put("second", ByteArray(4))
        cache.put("third", ByteArray(4))
        assertFalse(cache.contains("first"))
        assertEquals(8L, cache.stats().bytes)
        assertEquals(2, cache.stats().entries)
    }

    @Test fun replacingSameTileDoesNotDoubleCountMemory() {
        val cache = MeshTileCache(10)
        cache.put("tile", ByteArray(7))
        val replacement = ByteArray(3)
        cache.put("tile", replacement)
        assertSame(replacement, cache.get("tile"))
        assertEquals(3L, cache.stats().bytes)
        assertEquals(1, cache.stats().entries)
    }

    @Test fun oversizedTileCannotDisplaceExistingUsefulContent() {
        val cache = MeshTileCache(10)
        cache.put("existing", ByteArray(5))
        assertFalse(cache.put("oversized", ByteArray(11)))
        assertEquals(5L, cache.stats().bytes)
        assertTrue(cache.contains("existing"))
    }

    @Test fun foregroundAndNearTilesSurviveOuterRingDownloads() {
        val cache = MeshTileCache(12)
        cache.prioritize(mapOf("visible" to 0, "near" to 1, "far" to 40, "incoming" to 20))
        cache.put("visible", ByteArray(4))
        cache.put("near", ByteArray(4))
        cache.put("far", ByteArray(4))
        assertTrue(cache.put("incoming", ByteArray(4)))
        assertTrue(cache.contains("visible"))
        assertTrue(cache.contains("near"))
        assertTrue(cache.contains("incoming"))
        assertFalse(cache.contains("far"))
        assertEquals(12L, cache.stats().bytes)
    }

    @Test fun rejectedPrefetchDoesNotDisplaceAnyExistingUsefulContent() {
        val cache = MeshTileCache(8)
        cache.prioritize(mapOf("visible" to 0, "near" to 1, "incoming" to 1))
        val visible = ByteArray(4)
        val near = ByteArray(4)
        cache.put("visible", visible)
        cache.put("near", near)
        // Evicting the same-ranked old tile still cannot fit this download. Admission
        // must reject atomically, retaining both existing tiles for the visible view.
        assertFalse(cache.put("incoming", ByteArray(6)))
        assertSame(visible, cache.get("visible"))
        assertSame(near, cache.get("near"))
        assertFalse(cache.contains("incoming"))
        assertEquals(8L, cache.stats().bytes)
    }

    @Test fun reprioritizingAfterPanKeepsTheNewCenterInsteadOfTheOldOne() {
        val cache = MeshTileCache(8)
        cache.prioritize(mapOf("old-center" to 0, "next-center" to 3))
        cache.put("old-center", ByteArray(4))
        cache.put("next-center", ByteArray(4))
        cache.prioritize(mapOf("next-center" to 0, "incoming" to 1))
        assertTrue(cache.put("incoming", ByteArray(4)))
        assertFalse(cache.contains("old-center"))
        assertTrue(cache.contains("next-center"))
        assertTrue(cache.contains("incoming"))
    }

    @Test fun equallyRankedTilesStillEvictInLeastRecentlyUsedOrder() {
        val cache = MeshTileCache(8)
        cache.prioritize(mapOf("first" to 2, "second" to 2, "third" to 2))
        cache.put("first", ByteArray(4))
        cache.put("second", ByteArray(4))
        cache.get("first")
        assertTrue(cache.put("third", ByteArray(4)))
        assertTrue(cache.contains("first"))
        assertFalse(cache.contains("second"))
        assertTrue(cache.contains("third"))
    }

    @Test fun rejectedReplacementPreservesTheOriginalCachedBytes() {
        val cache = MeshTileCache(8)
        val original = ByteArray(4)
        cache.prioritize(mapOf("visible" to 0, "near" to 1))
        cache.put("visible", ByteArray(4))
        cache.put("near", original)
        assertFalse(cache.put("near", ByteArray(6)))
        assertSame(original, cache.get("near"))
        assertEquals(8L, cache.stats().bytes)
    }

    @Test fun cacheCopiesPrioritiesInsteadOfRetainingMutableCallerState() {
        val cache = MeshTileCache(8)
        val priorities = mutableMapOf("visible" to 0, "far" to 3, "incoming" to 2)
        cache.prioritize(priorities)
        priorities["visible"] = 10
        cache.put("visible", ByteArray(4))
        cache.put("far", ByteArray(4))
        assertTrue(cache.put("incoming", ByteArray(4)))
        assertTrue(cache.contains("visible"))
        assertFalse(cache.contains("far"))
    }

    @Test fun enlargedCacheBudgetIsHeapAwareAndHardCapped() {
        val mib = 1024L * 1024
        assertEquals(64L * mib, meshTileCacheBudget(192L * mib))
        assertEquals(192L * mib, meshTileCacheBudget(576L * mib))
        assertEquals(192L * mib, meshTileCacheBudget(2048L * mib))
        assertEquals(1L, meshTileCacheBudget(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHeapSizeCannotCreateAnUnboundedCache() {
        meshTileCacheBudget(0)
    }

    @Test fun clearReleasesAllCompressedContent() {
        val cache = MeshTileCache(10)
        cache.put("tile", ByteArray(5))
        cache.clear()
        assertEquals(0L, cache.stats().bytes)
        assertEquals(0, cache.stats().entries)
        assertNull(cache.get("tile"))
    }
}
