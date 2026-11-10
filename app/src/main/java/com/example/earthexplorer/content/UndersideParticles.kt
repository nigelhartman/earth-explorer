package com.example.earthexplorer.content

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.PolygonFillMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cyan data motes sink slowly from the terrain's underside to the bottom of the volume, swaying
 * a little and fading out the deeper they fall. Release heights follow the real loaded terrain.
 * Fading is done by depth bands: each falling object belongs to one of a few materials whose
 * alpha steps down with depth, which keeps the per-frame material updates tiny.
 */
internal class UndersideParticles(parent: Entity, private val volumeSide: Float) {
    class Column(val x: Float, val z: Float, var top: Float, var bottom: Float)

    private val resources = ArrayList<Resource>()
    private val root = Entity().also { parent.addChild(it) }
    private val bottomY = -volumeSide / 2f + volumeSide * 0.004f
    private val span = volumeSide * 0.9f
    private class Mote(val transform: TransformComponent, val bands: List<Entity>, val wire: Boolean,
        var x: Float, var z: Float, var y: Float, var speed: Float, val phase: Float, val sway: Float,
        val spinAxis: Vector3, val spinSpeed: Float, var scale: Float, var band: Int = -1)
    private val motes = ArrayList<Mote>(COUNT + WIRE_COUNT)
    private val holders = ArrayList<Entity>(COUNT + WIRE_COUNT)
    private var lastTick = 0L
    private var litColumns = 0
    private var destroyed = false

    /** Coarse sampling grid across the volume, in stationary volume coordinates. */
    val columns: List<Column> = buildList {
        for (row in 0 until GRID) for (column in 0 until GRID) {
            add(Column(-span / 2f + span * (column + 0.5f) / GRID, -span / 2f + span * (row + 0.5f) / GRID, Float.NaN, bottomY))
        }
    }

    init {
        fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources += it }
        // One material per depth band per look; alpha falls with depth.
        fun banded(rgb: Int, peak: Float, wire: Boolean) = List(BANDS) { band ->
            keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
                setBaseColor(linearColor(rgb, peak * (1f - band / (BANDS - 1f)) * (1f - band / (BANDS - 1f)) * 0.9f + peak * 0.02f))
                setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                if (wire) setPolygonFillMode(PolygonFillMode.LINE)
            }
        }
        val bright = banded(0x9FF1FF, 0.95f, wire = false)
        val soft = banded(0x4FE3FF, 0.75f, wire = false)
        val small = keep(crossQuad(volumeSide * 0.007f))
        val large = keep(crossQuad(volumeSide * 0.013f))
        fun addMote(mesh: MeshResource, materials: List<UnlitMaterial>, wire: Boolean, speedLow: Float, speedHigh: Float) {
            val holder = Entity().apply { enabled = false }
            val bands = materials.map { material -> ModelEntity(mesh, material).also { it.enabled = false; holder.addChild(it) } }
            root.addChild(holder)
            val axis = Vector3(Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f)
            motes += Mote(holder.components[TransformComponent::class.java]!!, bands, wire,
                0f, 0f, Float.NaN, volumeSide * (speedLow + (speedHigh - speedLow) * Random.nextFloat()),
                Random.nextFloat() * 6.28f, volumeSide * (0.004f + 0.01f * Random.nextFloat()),
                axis, if (wire) 0.25f + 0.5f * Random.nextFloat() else 0f, 1f)
            holders += holder
        }
        repeat(COUNT) { index ->
            val big = index % 3 == 0
            addMote(if (big) large else small, if (big) soft else bright, wire = false, 0.012f, 0.03f)
        }
    }

    /** Refresh release heights (NaN = no terrain over that cell). */
    fun update(tops: FloatArray) {
        if (destroyed) return
        require(tops.size == columns.size)
        columns.forEachIndexed { index, column ->
            column.top = tops[index].takeIf { it.isFinite() && it > bottomY + volumeSide * 0.01f } ?: Float.NaN
        }
        litColumns = columns.count { !it.top.isNaN() }
    }

    private fun columnAt(x: Float, z: Float): Column {
        val cx = ((x + span / 2f) / span * GRID).toInt().coerceIn(0, GRID - 1)
        val cz = ((z + span / 2f) / span * GRID).toInt().coerceIn(0, GRID - 1)
        return columns[cz * GRID + cx]
    }

    private fun respawn(mote: Mote): Boolean {
        // Pick a lit cell at random, then a random point inside it.
        if (litColumns == 0) return false
        var pick = Random.nextInt(litColumns)
        val column = columns.first { !it.top.isNaN() && pick-- == 0 }
        val cell = span / GRID
        mote.x = column.x + (Random.nextFloat() - 0.5f) * cell
        mote.z = column.z + (Random.nextFloat() - 0.5f) * cell
        mote.y = column.top - Random.nextFloat() * (column.top - bottomY) * 0.15f
        mote.speed = volumeSide * (0.012f + 0.018f * Random.nextFloat())
        mote.band = -1
        return true
    }

    fun tick(now: Long) {
        if (destroyed || !root.enabled) { lastTick = 0L; return }
        val dt = if (lastTick == 0L) 0f else ((now - lastTick).coerceIn(0L, 100L)) / 1000f
        lastTick = now
        val t = now / 1000f
        motes.forEachIndexed { index, mote ->
            val holder = holders[index]
            val column = columnAt(mote.x, mote.z)
            if (mote.y.isNaN() || mote.y <= bottomY || column.top.isNaN() || mote.y > column.top) {
                if (!respawn(mote)) { holder.enabled = false; return@forEachIndexed }
                holder.enabled = true
            }
            mote.y -= mote.speed * dt
            val current = columnAt(mote.x, mote.z)
            val height = (current.top - bottomY).coerceAtLeast(0.01f)
            val fall = ((current.top - mote.y) / height).coerceIn(0f, 1f)
            // Fade in just below the ground, then get more transparent the deeper it sinks.
            val band = if (fall < 0.08f) 0 else ((fall - 0.08f) / 0.92f * BANDS).toInt().coerceIn(0, BANDS - 1)
            if (band != mote.band) {
                mote.band = band
                mote.bands.forEachIndexed { i, entity -> entity.enabled = i == band }
            }
            val scale = if (fall < 0.08f) (fall / 0.08f).coerceAtLeast(0.05f) else 1f
            if (kotlin.math.abs(scale - mote.scale) > 0.04f) {
                mote.scale = scale
                mote.transform.setScaleVector(Vector3(scale))
            }
            val sway = sin(t * 0.8f + mote.phase) * mote.sway
            mote.transform.setPosition(Vector3(mote.x + sway, mote.y, mote.z + sway * 0.6f))
            if (mote.wire) {
                val angle = (t * mote.spinSpeed + mote.phase) % (2f * PI.toFloat())
                val a = mote.spinAxis
                val len = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z).coerceAtLeast(1e-4f)
                val sn = sin(angle / 2f)
                mote.transform.setQuaternion(Quat(a.x / len * sn, a.y / len * sn, a.z / len * sn, cos(angle / 2f)))
            }
        }
    }

    fun setVisible(visible: Boolean) { root.enabled = visible }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        root.destroy()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private fun crossQuad(size: Float): MeshResource {
        val h = size / 2f
        val positions = listOf(
            Vector3(-h, -h, 0f), Vector3(h, -h, 0f), Vector3(h, h, 0f), Vector3(-h, h, 0f),
            Vector3(0f, -h, -h), Vector3(0f, -h, h), Vector3(0f, h, h), Vector3(0f, h, -h),
        )
        return MeshResource.createWithMeshModel(MeshModel(positions, listOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7)),
            BoundingBox(Vector3(-h), Vector3(h)), "data-mote")
    }

    private companion object {
        const val GRID = 8
        const val COUNT = 550
        const val WIRE_COUNT = 0
        const val BANDS = 5
    }
}
