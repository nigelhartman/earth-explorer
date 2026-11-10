package com.example.earthexplorer.content

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The NASA texture is lifted and slightly saturated so oceans stay readable inside a lit room. */
internal fun loadBrightEarth(assets: AssetManager): Bitmap {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val source = assets.open("earth.jpg").use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalStateException("earth.jpg could not be decoded")
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val matrix = ColorMatrix().apply { setSaturation(1.2f) }
    matrix.postConcat(ColorMatrix(floatArrayOf(
        1.45f, 0f, 0f, 0f, 14f,
        0f, 1.45f, 0f, 0f, 18f,
        0f, 0f, 1.45f, 0f, 26f,
        0f, 0f, 0f, 1f, 0f,
    )))
    Canvas(result).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    source.recycle()
    return result
}

/** NASA Black Marble city lights for the night side; null when the asset is missing. */
internal fun loadNightLights(assets: AssetManager): Bitmap? = try {
    assets.open("night.jpg").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
} catch (_: java.io.IOException) { null }

/** Rotates a +Y-facing mesh (rings, cones) so +Y points along the given unit direction. */
internal fun quaternionFromUp(direction: Vector3): Quat {
    val w = 1f + direction.y
    if (w < 1e-5f) return Quat(1f, 0f, 0f, 0f)
    val denominator = sqrt(2f * w)
    return Quat(direction.z / denominator, 0f, -direction.x / denominator, w / denominator)
}

/** sRGB design colors become linear material colors, matching the renderer's output encoding. */
internal fun linearColor(rgb: Int, alpha: Float = 1f): Color4 {
    fun channel(shift: Int) = (((rgb shr shift) and 255) / 255.0).pow(2.2).toFloat()
    return Color4(channel(16), channel(8), channel(0), alpha)
}

/** A flat ring in the XZ plane whose axis is +Y, for reticles and pulse waves on the globe surface. */
internal fun ringMesh(majorRadius: Float, minorRadius: Float, name: String): MeshResource {
    val segments = 72
    val sides = 6
    val positions = ArrayList<Vector3>((segments + 1) * (sides + 1))
    val indices = ArrayList<Int>(segments * sides * 6)
    for (segment in 0..segments) {
        val angle = segment * 2.0 * PI / segments
        for (side in 0..sides) {
            val around = side * 2.0 * PI / sides
            val radial = majorRadius + minorRadius * cos(around)
            positions += Vector3((radial * cos(angle)).toFloat(), (minorRadius * sin(around)).toFloat(),
                (radial * sin(angle)).toFloat())
        }
    }
    for (segment in 0 until segments) for (side in 0 until sides) {
        val first = segment * (sides + 1) + side
        val next = first + sides + 1
        indices += listOf(first, first + 1, next, first + 1, next + 1, next)
    }
    val extent = majorRadius + minorRadius
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-extent, -minorRadius, -extent), Vector3(extent, minorRadius, extent)), name)
}

/**
 * A flat fan from the camera head outwards along a compass bearing, dropping to street level at
 * its far edge. Local +X is east and +Z is south, matching the city frame.
 */
internal fun viewConeMesh(length: Float, halfAngleDegrees: Float, drop: Float, headingDegrees: Float): MeshResource {
    val segments = 10
    val positions = ArrayList<Vector3>(segments + 2)
    val indices = ArrayList<Int>(segments * 3)
    positions += Vector3(0f, 0f, 0f)
    for (i in 0..segments) {
        val bearing = Math.toRadians((headingDegrees - halfAngleDegrees + 2f * halfAngleDegrees * i / segments).toDouble())
        positions += Vector3((length * sin(bearing)).toFloat(), -drop, (-length * cos(bearing)).toFloat())
    }
    for (i in 1..segments) indices += listOf(0, i, i + 1)
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-length, -drop, -length), Vector3(length, 0f, length)), "view-cone")
}

/** Thin cell outlines for the scanning floor grid, lying in the XZ plane. */
internal fun floorGridMesh(volumeSide: Float, columns: Int, rows: Int): MeshResource {
    val half = volumeSide / 2f
    val width = volumeSide * 0.0012f
    val positions = ArrayList<Vector3>((columns + rows + 2) * 4)
    val indices = ArrayList<Int>((columns + rows + 2) * 6)
    fun strip(x0: Float, z0: Float, x1: Float, z1: Float) {
        val base = positions.size
        positions += Vector3(x0, 0f, z0); positions += Vector3(x1, 0f, z1)
        positions += Vector3(x1 + width, 0f, z1 + width); positions += Vector3(x0 + width, 0f, z0 + width)
        indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
    }
    for (column in 0..columns) {
        val x = -half + column * volumeSide / columns
        strip(x, -half, x, half)
    }
    for (row in 0..rows) {
        val z = -half + row * volumeSide / rows
        strip(-half, z, half, z)
    }
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-half, -0.001f, -half), Vector3(half, 0.001f, half)), "floor-grid")
}

/** Quaternion product a·b (apply b, then a). */
internal fun quaternionMultiply(a: Quat, b: Quat) = Quat(
    a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
)

internal fun quaternionConjugate(q: Quat) = Quat(-q.x, -q.y, -q.z, q.w)

/** Shortest-path spherical interpolation between two unit quaternions. */
internal fun quaternionSlerp(a: Quat, b: Quat, amount: Float): Quat {
    var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
    var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
    if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw }
    val wa: Float
    val wb: Float
    if (dot > 0.9995f) {
        wa = 1f - amount; wb = amount
    } else {
        val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        val sine = sin(angle)
        wa = sin((1f - amount) * angle) / sine
        wb = sin(amount * angle) / sine
    }
    val x = a.x * wa + bx * wb; val y = a.y * wa + by * wb; val z = a.z * wa + bz * wb; val w = a.w * wa + bw * wb
    val length = sqrt(x * x + y * y + z * z + w * w)
    return Quat(x / length, y / length, z / length, w / length)
}

/**
 * A horizontal textured quad for floor overlays such as the city compass. The bitmap's top edge
 * lands at -Z (away from the viewer, north) and its bottom edge at +Z (toward the viewer), because
 * the renderer's texture origin is the bottom of the bitmap.
 */
internal fun floorImageQuad(size: Float): MeshResource {
    val h = size / 2f
    return MeshResource.createWithMeshModel(MeshModel(
        listOf(Vector3(-h, 0f, -h), Vector3(h, 0f, -h), Vector3(h, 0f, h), Vector3(-h, 0f, h)),
        listOf(0, 1, 2, 0, 2, 3), null, null,
        listOf(Vector2(0f, 1f), Vector2(1f, 1f), Vector2(1f, 0f), Vector2(0f, 0f))),
        BoundingBox(Vector3(-h, -0.001f, -h), Vector3(h, 0.001f, h)), "floor-image")
}

/** A flat disc in the XZ plane. */
internal fun discMesh(radius: Float, segments: Int = 64): MeshResource {
    val positions = ArrayList<Vector3>(segments + 2)
    val indices = ArrayList<Int>(segments * 3)
    positions += Vector3(0f, 0f, 0f)
    for (i in 0..segments) {
        val a = i * 2.0 * PI / segments
        positions += Vector3((radius * cos(a)).toFloat(), 0f, (radius * sin(a)).toFloat())
    }
    for (i in 1..segments) indices += listOf(0, i, i + 1)
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-radius, -0.001f, -radius), Vector3(radius, 0.001f, radius)), "disc")
}

/** Meridians and parallels every 15° as one thin tube mesh: the command-deck grid over the globe. */
internal fun graticuleMesh(radius: Float, lineRadius: Float): MeshResource {
    val positions = ArrayList<Vector3>(20_000)
    val indices = ArrayList<Int>(60_000)
    fun ring(center: Vector3, axisU: Vector3, axisV: Vector3, ringRadius: Float, segments: Int) {
        val sides = 4
        val base = positions.size
        val normal = Vector3(axisU.y * axisV.z - axisU.z * axisV.y, axisU.z * axisV.x - axisU.x * axisV.z,
            axisU.x * axisV.y - axisU.y * axisV.x)
        for (segment in 0..segments) {
            val angle = segment * 2.0 * PI / segments
            val cu = cos(angle).toFloat()
            val sv = sin(angle).toFloat()
            val radial = Vector3(axisU.x * cu + axisV.x * sv, axisU.y * cu + axisV.y * sv, axisU.z * cu + axisV.z * sv)
            for (side in 0..sides) {
                val around = side * 2.0 * PI / sides
                val cb = cos(around).toFloat()
                val sb = sin(around).toFloat()
                positions += Vector3(
                    center.x + radial.x * (ringRadius + lineRadius * cb) + normal.x * lineRadius * sb,
                    center.y + radial.y * (ringRadius + lineRadius * cb) + normal.y * lineRadius * sb,
                    center.z + radial.z * (ringRadius + lineRadius * cb) + normal.z * lineRadius * sb,
                )
            }
        }
        for (segment in 0 until segments) for (side in 0 until sides) {
            val first = base + segment * (sides + 1) + side
            val next = first + sides + 1
            indices += listOf(first, first + 1, next, first + 1, next + 1, next)
        }
    }
    for (latitude in -75..75 step 15) {
        val phi = Math.toRadians(latitude.toDouble())
        ring(Vector3(0f, (radius * sin(phi)).toFloat(), 0f), Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f),
            (radius * cos(phi)).toFloat(), 96)
    }
    for (longitude in 0 until 180 step 15) {
        val lambda = Math.toRadians(longitude.toDouble())
        ring(Vector3(0f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(sin(lambda).toFloat(), 0f, cos(lambda).toFloat()), radius, 128)
    }
    val extent = radius + lineRadius
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-extent), Vector3(extent)), "graticule")
}

/**
 * Low-poly airliner for instanced aircraft markers. Local +Y is the nose direction and
 * +Z points away from the globe, matching the heading rotation used for the old cones.
 */
internal fun airplaneMesh(length: Float): MeshResource {
    val positions = ArrayList<Vector3>(96)
    val indices = ArrayList<Int>(96)
    fun triangle(a: Vector3, b: Vector3, c: Vector3) {
        val base = positions.size
        positions += a; positions += b; positions += c
        indices += listOf(base, base + 1, base + 2)
    }
    fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3) { triangle(a, b, c); triangle(a, c, d) }
    val l = length
    val r = l * 0.055f
    val nose = Vector3(0f, l * 0.5f, 0f)
    val tail = Vector3(0f, -l * 0.5f, 0f)
    val sides = 6
    val front = List(sides) { i -> val a = i * 2.0 * PI / sides; Vector3((r * cos(a)).toFloat(), l * 0.26f, (r * sin(a)).toFloat()) }
    val rear = List(sides) { i -> val a = i * 2.0 * PI / sides; Vector3((r * 0.7f * cos(a)).toFloat(), -l * 0.3f, (r * 0.7f * sin(a)).toFloat()) }
    for (i in 0 until sides) {
        val j = (i + 1) % sides
        triangle(nose, front[i], front[j])
        quad(front[i], rear[i], rear[j], front[j])
        triangle(tail, rear[j], rear[i])
    }
    for (side in listOf(-1f, 1f)) {
        quad(Vector3(side * r, l * 0.10f, 0f), Vector3(side * l * 0.47f, -l * 0.15f, l * 0.02f),
            Vector3(side * l * 0.47f, -l * 0.24f, l * 0.02f), Vector3(side * r, -l * 0.13f, 0f))
        quad(Vector3(side * r * 0.7f, -l * 0.37f, 0f), Vector3(side * l * 0.18f, -l * 0.46f, l * 0.01f),
            Vector3(side * l * 0.18f, -l * 0.5f, l * 0.01f), Vector3(side * r * 0.7f, -l * 0.46f, 0f))
    }
    quad(Vector3(0f, -l * 0.33f, r * 0.6f), Vector3(0f, -l * 0.47f, l * 0.16f),
        Vector3(0f, -l * 0.5f, l * 0.16f), Vector3(0f, -l * 0.5f, r * 0.6f))
    // A thin contrail behind the tail reads as motion along the reported track.
    quad(Vector3(-r * 0.35f, -l * 0.55f, 0f), Vector3(r * 0.35f, -l * 0.55f, 0f),
        Vector3(r * 0.08f, -l * 2.1f, 0f), Vector3(-r * 0.08f, -l * 2.1f, 0f))
    val extent = l * 2.1f
    return MeshResource.createWithMeshModel(MeshModel(positions, indices),
        BoundingBox(Vector3(-extent), Vector3(extent)), "airplane")
}
