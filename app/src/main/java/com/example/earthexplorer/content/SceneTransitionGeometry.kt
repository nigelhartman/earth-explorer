package com.example.earthexplorer.content

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 25 longitude columns × 20 latitude rows: 500 Earth pieces. Two vertically adjacent pieces
 * land on one of 250 floor cells, forming the scanning grid the city then rises from.
 */
internal const val SCENE_FRAGMENT_COLUMNS = 25
internal const val SCENE_FRAGMENT_ROWS = 20
internal const val SCENE_FRAGMENT_COUNT = SCENE_FRAGMENT_COLUMNS * SCENE_FRAGMENT_ROWS
internal const val SCENE_CITY_ROWS = SCENE_FRAGMENT_ROWS / 2
internal const val SCENE_CITY_CELLS = SCENE_FRAGMENT_COLUMNS * SCENE_CITY_ROWS

/** The floor cell an Earth piece lands on. */
internal fun cityCellFor(piece: Int): Int =
    (piece / SCENE_FRAGMENT_COLUMNS / 2) * SCENE_FRAGMENT_COLUMNS + piece % SCENE_FRAGMENT_COLUMNS

internal data class FragmentPoint(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: FragmentPoint) = FragmentPoint(x - other.x, y - other.y, z - other.z)
}

internal data class FragmentVertex(val point: FragmentPoint, val u: Float, val v: Float)
internal data class FragmentGeometry(
    val center: FragmentPoint,
    val vertices: List<FragmentVertex>,
    val indices: List<Int>,
)

/** The same geographic orientation and UV convention as the interactive globe. */
internal fun earthFragmentGeometry(radius: Float): List<FragmentGeometry> =
    List(SCENE_FRAGMENT_COUNT) { piece ->
        val row = piece / SCENE_FRAGMENT_COLUMNS
        val column = piece % SCENE_FRAGMENT_COLUMNS
        fun point(u: Float, v: Float): FragmentPoint {
            val longitude = (-180.0 + 360.0 * u) * PI / 180.0
            val latitude = (90.0 - 180.0 * v) * PI / 180.0
            return FragmentPoint((radius * cos(latitude) * sin(longitude)).toFloat(),
                (radius * sin(latitude)).toFloat(), (radius * cos(latitude) * cos(longitude)).toFloat())
        }
        val center = point((column + 0.5f) / SCENE_FRAGMENT_COLUMNS, (row + 0.5f) / SCENE_FRAGMENT_ROWS)
        val subdivisions = 4
        val stride = subdivisions + 1
        val vertices = buildList {
            for (y in 0..subdivisions) for (x in 0..subdivisions) {
                val u = (column + x.toFloat() / subdivisions) / SCENE_FRAGMENT_COLUMNS
                val v = (row + y.toFloat() / subdivisions) / SCENE_FRAGMENT_ROWS
                add(FragmentVertex(point(u, v) - center, u, 1f - v))
            }
        }
        val indices = buildList {
            for (y in 0 until subdivisions) for (x in 0 until subdivisions) {
                val first = y * stride + x
                addAll(listOf(first, first + stride, first + 1, first + 1, first + stride, first + stride + 1))
            }
        }
        FragmentGeometry(center, vertices, indices)
    }

/** Floor cell centers lie just above the city's street level inside the shared volume. */
internal const val SCENE_FLOOR_Y = -0.346f

internal fun cityFragmentCenter(cell: Int, volumeSide: Float): FragmentPoint = FragmentPoint(
    ((cell % SCENE_FRAGMENT_COLUMNS + 0.5f) / SCENE_FRAGMENT_COLUMNS - 0.5f) * volumeSide,
    SCENE_FLOOR_Y,
    ((cell / SCENE_FRAGMENT_COLUMNS + 0.5f) / SCENE_CITY_ROWS - 0.5f) * volumeSide,
)
