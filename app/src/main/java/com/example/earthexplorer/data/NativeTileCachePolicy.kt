package com.example.earthexplorer.data

import kotlin.math.hypot
import kotlin.math.max

/** Includes the retained CPU geometry and estimated native texture/mesh allocation. */
internal fun nativeTileCacheBudget(maxHeapBytes: Long): Long {
    require(maxHeapBytes > 0)
    return minOf(256L * 1024 * 1024, (maxHeapBytes / 3).coerceAtLeast(1))
}

internal data class NativeTileBounds(val minX: Double, val maxX: Double, val minZ: Double, val maxZ: Double) {
    fun distance(center: CityCenter): Double = hypot(
        max(0.0, max(minX - center.east, center.east - maxX)),
        max(0.0, max(minZ + center.north, -center.north - maxZ)),
    )

    fun contains(x: Double, z: Double, margin: Double = 0.0): Boolean =
        x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin

    fun contains(other: NativeTileBounds): Boolean =
        other.minX <= maxX && other.maxX >= minX && other.minZ <= maxZ && other.maxZ >= minZ

    fun visible(center: CityCenter, halfWidth: Double = CityView.HALF_WIDTH): Boolean =
        maxX >= center.east - halfWidth && minX <= center.east + halfWidth &&
            maxZ >= -center.north - halfWidth && minZ <= -center.north + halfWidth
}

internal data class NativeTileCacheEntry(
    val id: String,
    val bytes: Long,
    val bounds: NativeTileBounds,
    val lastVisible: Long,
)

/** Nearby native resources survive panning; visible geometry is never evicted for speculation. */
internal class NativeTileCachePolicy(
    val budgetBytes: Long,
    val maxTiles: Int = 240,
    private val retentionDistance: Double = MeshRegion.SIDE * 3,
) {
    init { require(budgetBytes > 0 && maxTiles > 0 && retentionDistance >= 0) }

    fun evictions(entries: List<NativeTileCacheEntry>, protectedIds: Set<String>, center: CityCenter): Set<String> {
        val candidates = entries.filter { it.id !in protectedIds }
            .sortedWith(compareByDescending<NativeTileCacheEntry> { it.bounds.distance(center) }
                .thenBy { it.lastVisible })
        val result = linkedSetOf<String>()
        var bytes = entries.sumOf { it.bytes }
        var count = entries.size
        for (entry in candidates) {
            if (entry.bounds.distance(center) <= retentionDistance && bytes <= budgetBytes && count <= maxTiles) break
            result += entry.id
            bytes -= entry.bytes
            count--
        }
        return result
    }
}
