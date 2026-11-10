package com.example.earthexplorer.data

import kotlin.math.abs
import kotlin.math.floor

/** A fixed ground-plane cell. Provider tiles need not have this size or shape. */
data class MeshRegion(val eastIndex: Int, val northIndex: Int) {
    val center: CityCenter get() = CityCenter(eastIndex * SIDE, northIndex * SIDE)
    val west: Double get() = center.east - HALF_SIDE
    val east: Double get() = center.east + HALF_SIDE
    val south: Double get() = center.north - HALF_SIDE
    val north: Double get() = center.north + HALF_SIDE

    fun intersectsView(view: CityCenter, halfWidth: Double = CityView.HALF_WIDTH): Boolean =
        abs(center.east - view.east) <= HALF_SIDE + halfWidth &&
            abs(center.north - view.north) <= HALF_SIDE + halfWidth

    companion object {
        const val HALF_SIDE = CityView.HALF_WIDTH
        const val SIDE = HALF_SIDE * 2.0

        fun containing(center: CityCenter): MeshRegion {
            require(center.east.isFinite() && center.north.isFinite())
            fun index(value: Double): Int {
                val index = floor((value + HALF_SIDE) / SIDE)
                require(index > Int.MIN_VALUE && index < Int.MAX_VALUE)
                return index.toInt()
            }
            return MeshRegion(index(center.east), index(center.north))
        }
    }
}

/** A 2.24 km-wide, nearest-first grid of 49 equally sized 320 m squares. */
data class MeshNeighborhood(val central: MeshRegion, val regions: List<MeshRegion>) {
    companion object {
        const val RADIUS = 3
        const val DIAMETER = RADIUS * 2 + 1
        const val REGION_COUNT = DIAMETER * DIAMETER
        const val WIDTH_METERS = DIAMETER * MeshRegion.SIDE

        fun around(center: CityCenter): MeshNeighborhood {
            val central = MeshRegion.containing(center)
            val safeIndices = (Int.MIN_VALUE + RADIUS)..(Int.MAX_VALUE - RADIUS)
            require(central.eastIndex in safeIndices && central.northIndex in safeIndices) {
                "The mesh neighborhood must fit inside the supported grid."
            }
            val neighbors = (-RADIUS..RADIUS).flatMap { north ->
                (-RADIUS..RADIUS).map { east ->
                    MeshRegion(central.eastIndex + east, central.northIndex + north)
                }
            }.sortedWith(compareBy<MeshRegion> { it != central }.thenBy {
                val dx = it.center.east - center.east
                val dy = it.center.north - center.north
                dx * dx + dy * dy
            })
            return MeshNeighborhood(central, neighbors)
        }
    }
}
