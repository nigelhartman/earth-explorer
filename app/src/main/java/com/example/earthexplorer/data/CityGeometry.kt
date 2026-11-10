package com.example.earthexplorer.data

import kotlin.math.*

/** Double precision is necessary until Earth coordinates are rebased locally. */
data class DVec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: DVec3) = DVec3(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: DVec3) = DVec3(x - b.x, y - b.y, z - b.z)
    operator fun times(s: Double) = DVec3(x * s, y * s, z * s)
    fun dot(b: DVec3) = x * b.x + y * b.y + z * b.z
    fun length() = sqrt(dot(this))
}

class GeoFrame(val latitude: Double, val longitude: Double) {
    private val lat = Math.toRadians(latitude)
    private val lon = Math.toRadians(longitude)
    val east = DVec3(-sin(lon), cos(lon), 0.0)
    val north = DVec3(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat))
    val up = DVec3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
    val origin = ecef(latitude, longitude)

    fun local(point: DVec3): DVec3 {
        val d = point - origin
        return DVec3(d.dot(east), d.dot(up), -d.dot(north))
    }

    fun coordinate(center: CityCenter): Pair<Double, Double> {
        val p = origin + east * center.east + north * center.north
        val longitude = Math.toDegrees(atan2(p.y, p.x))
        val r = hypot(p.x, p.y)
        var latitude = atan2(p.z, r * (1.0 - ECCENTRICITY))
        repeat(5) {
            val n = RADIUS / sqrt(1.0 - ECCENTRICITY * sin(latitude).pow(2))
            latitude = atan2(p.z + ECCENTRICITY * n * sin(latitude), r)
        }
        return Math.toDegrees(latitude) to longitude
    }

    companion object {
        private const val RADIUS = 6_378_137.0
        private const val ECCENTRICITY = 0.00669437999014

        fun ecef(latitude: Double, longitude: Double, height: Double = 0.0): DVec3 {
            val lat = Math.toRadians(latitude)
            val lon = Math.toRadians(longitude)
            val n = RADIUS / sqrt(1.0 - ECCENTRICITY * sin(lat).pow(2))
            return DVec3(
                (n + height) * cos(lat) * cos(lon),
                (n + height) * cos(lat) * sin(lon),
                (n * (1.0 - ECCENTRICITY) + height) * sin(lat),
            )
        }
    }
}

data class CityCenter(val east: Double = 0.0, val north: Double = 0.0) {
    fun dragged(dx: Float, dy: Float, dz: Float = 0f) = CityCenter(
        east - dx * 0.45,
        north + (dy + dz) * 0.65,
    )
}

/** Column-major matrices, matching glTF and 3D Tiles. */
class DMatrix(val values: DoubleArray) {
    init { require(values.size == 16) }

    operator fun times(b: DMatrix) = DMatrix(DoubleArray(16) { i ->
        val row = i % 4
        val col = i / 4
        (0..3).sumOf { k -> values[k * 4 + row] * b.values[col * 4 + k] }
    })

    fun point(p: DVec3) = DVec3(
        values[0] * p.x + values[4] * p.y + values[8] * p.z + values[12],
        values[1] * p.x + values[5] * p.y + values[9] * p.z + values[13],
        values[2] * p.x + values[6] * p.y + values[10] * p.z + values[14],
    )

    companion object {
        val IDENTITY = DMatrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        val Y_UP_TO_Z_UP = DMatrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0))

        fun trs(t: DoubleArray, q: DoubleArray, s: DoubleArray): DMatrix {
            val (x, y, z, w) = q
            return DMatrix(doubleArrayOf(
                (1-2*y*y-2*z*z)*s[0], (2*x*y+2*z*w)*s[0], (2*x*z-2*y*w)*s[0], 0.0,
                (2*x*y-2*z*w)*s[1], (1-2*x*x-2*z*z)*s[1], (2*y*z+2*x*w)*s[1], 0.0,
                (2*x*z+2*y*w)*s[2], (2*y*z-2*x*w)*s[2], (1-2*x*x-2*y*y)*s[2], 0.0,
                t[0], t[1], t[2], 1.0,
            ))
        }
    }
}

data class TileVertex(val x: Double, val y: Double, val z: Double, val u: Double, val v: Double)

/** Display scale and nominal view footprint; the PICO volume performs native clipping. */
object CityView {
    const val HALF_WIDTH = 160.0
    const val SCALE = 0.002375

}

/** The hit box stays inside the volume; protruding faces cannot receive clipped hits. */
data class ExplorerHitBox(val center: DVec3, val size: DVec3) {
    companion object {
        fun withinVolume(side: Double): ExplorerHitBox {
            require(side.isFinite() && side > 0)
            return ExplorerHitBox(DVec3(0.0, -side * 0.08, 0.0),
                DVec3(side * 0.94, side * 0.78, side * 0.94))
        }
    }
}
