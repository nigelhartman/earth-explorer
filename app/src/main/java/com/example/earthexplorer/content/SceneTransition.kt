package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PolygonFillMode
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Earth dissolves into 500 cyan wireframe plates that settle as a scanning floor grid; the
 * streamed city then rises out of that grid. Returning to Earth mirrors it. Nothing here depends
 * on the download: the grid simply keeps scanning until the last tile has landed.
 */
internal class SceneTransition(private val volumeSide: Float) {
    private val root = Entity().apply { enabled = false }
    private val floor = Entity().apply { enabled = false }
    private val fragments = ArrayList<Fragment>()
    private val resources = ArrayList<Resource>()
    private val earthRadius = volumeSide * 0.33f
    private var earthMaterial: UnlitMaterial? = null
    private var gridMaterial: UnlitMaterial? = null
    private var scanMaterial: UnlitMaterial? = null
    private var ringMaterial: UnlitMaterial? = null
    private var scanTransform: TransformComponent? = null
    private var ringTransforms = emptyList<TransformComponent>()
    private var floorAlpha = -1f
    private var floorActive = false
    private var floorSince = 0L
    private val lifted = HashSet<Int>()
    private var built = false
    private var registered = false
    private var destroyed = false

    private class Fragment(
        val entity: Entity,
        val transform: TransformComponent,
        val piece: Entity,
        val earthTransform: TransformComponent,
        val tinted: Entity,
        val tintedTransform: TransformComponent,
        val wire: Entity,
        val wireTransform: TransformComponent,
        val earthCenter: FragmentPoint,
        val flat: Quat,
        val cell: FragmentPoint,
        var earthScale: Float = 1f,
        var lastTravel: Float = -1f,
        /** 0 = photo, 1 = cyan-tinted photo, 2 = wireframe; each piece switches on its own travel. */
        var look: Int = 0,
    )

    suspend fun build(content: SpatialViewContent, earth: Bitmap) {
        if (built || destroyed) return
        val started = SystemClock.elapsedRealtime()
        val geometry = withContext(Dispatchers.Default) { earthFragmentGeometry(earthRadius) }
        val geometryMs = SystemClock.elapsedRealtime() - started
        val pending = ConcurrentLinkedQueue<Resource>()
        var transferred = false
        try {
            class Prepared(val material: UnlitMaterial, val tintedMaterial: UnlitMaterial, val wireMaterial: UnlitMaterial, val grid: MeshResource, val gridMaterial: UnlitMaterial,
                val scan: MeshResource, val scanMaterial: UnlitMaterial, val ring: MeshResource, val ringMaterial: UnlitMaterial)
            val prepared = withContext(Dispatchers.IO) {
                fun <T : Resource> keep(resource: T): T = resource.also { pending += it; it.toGlobal() }
                val texture = keep(TextureResource.create(earth))
                Prepared(
                    material = keep(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
                        setBaseColorTexture(texture)
                        setCullingMode(MaterialCullingMode.NONE)
                    },
                    tintedMaterial = keep(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
                        setBaseColorTexture(texture)
                        setBaseColor(Color4(0.55f, 0.94f, 1f, 1f))
                        setCullingMode(MaterialCullingMode.NONE)
                    },
                    wireMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                        setBaseColor(linearColor(0x6FEBFF, 0.95f))
                        setCullingMode(MaterialCullingMode.NONE)
                        setPolygonFillMode(PolygonFillMode.LINE)
                    },
                    grid = keep(floorGridMesh(volumeSide * 0.998f, SCENE_FRAGMENT_COLUMNS, SCENE_CITY_ROWS)),
                    gridMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                        setBaseColor(linearColor(0x4FE3FF, 0f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                    },
                    scan = keep(flatQuad(volumeSide * 0.998f, volumeSide * 0.014f)),
                    scanMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                        setBaseColor(linearColor(0x9FF1FF, 0f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                    },
                    ring = keep(ringMesh(volumeSide * 0.09f, volumeSide * 0.0016f, "floor-ring")),
                    ringMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                        setBaseColor(linearColor(0x4FE3FF, 0f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                    },
                )
            }
            // The SDK waits for each upload to become ready. Overlap a bounded number of those waits.
            val slots = Semaphore(12)
            val meshes = coroutineScope {
                geometry.mapIndexed { index, fragment -> async(Dispatchers.IO) {
                    slots.withPermit {
                        currentCoroutineContext().ensureActive()
                        mesh(fragment, "EarthFragment$index").also { pending += it }
                    }
                } }.awaitAll()
            }
            val nativeMs = SystemClock.elapsedRealtime() - started - geometryMs
            val attachStarted = SystemClock.elapsedRealtime()
            currentCoroutineContext().ensureActive()
            if (destroyed) return
            earthMaterial = prepared.material
            gridMaterial = prepared.gridMaterial
            scanMaterial = prepared.scanMaterial
            ringMaterial = prepared.ringMaterial
            floor.components[TransformComponent::class.java]?.setPosition(Vector3(0f, SCENE_FLOOR_Y - 0.003f, 0f))
            floor.addChild(ModelEntity(prepared.grid, prepared.gridMaterial))
            floor.addChild(ModelEntity(prepared.scan, prepared.scanMaterial).also {
                scanTransform = it.components[TransformComponent::class.java]
            })
            ringTransforms = List(2) {
                ModelEntity(prepared.ring, prepared.ringMaterial).also { ring -> floor.addChild(ring) }
                    .components[TransformComponent::class.java]!!
            }
            // Register the parents before children; registering one enormous tree blocks input.
            if (!registered) {
                content.addEntity(root)
                content.addEntity(floor)
                registered = true
            }
            val cellDepth = volumeSide / SCENE_CITY_ROWS
            geometry.forEachIndexed { index, part ->
                currentCoroutineContext().ensureActive()
                check(!destroyed) { "Transition was disposed while building Earth fragments" }
                val entity = Entity().apply { setName("SceneFragment$index") }
                val piece = ModelEntity(meshes[index], prepared.material)
                val tinted = ModelEntity(meshes[index], prepared.tintedMaterial).apply { enabled = false }
                val wire = ModelEntity(meshes[index], prepared.wireMaterial).apply { enabled = false }
                entity.addChild(piece)
                entity.addChild(tinted)
                entity.addChild(wire)
                root.addChild(entity)
                val length = sqrt(part.center.x * part.center.x + part.center.y * part.center.y + part.center.z * part.center.z)
                val normal = Vector3(part.center.x / length, part.center.y / length, part.center.z / length)
                // Two latitude rows share a floor cell; each takes its own half so the plates never overlap.
                val cell = cityFragmentCenter(cityCellFor(index), volumeSide)
                val half = if ((index / SCENE_FRAGMENT_COLUMNS) % 2 == 0) -0.25f else 0.25f
                fragments += Fragment(entity, entity.transform(), piece, piece.transform(), tinted, tinted.transform(), wire, wire.transform(), part.center,
                    quaternionConjugate(quaternionFromUp(normal)),
                    FragmentPoint(cell.x, cell.y, cell.z + half * cellDepth))
                if (index % 16 == 15) delay(6)
            }
            resources.addAll(pending)
            transferred = true
            built = true
            Log.i("SceneTransition", "Prepared $SCENE_FRAGMENT_COUNT textured Earth fragments; " +
                "geometry ${geometryMs}ms; native ${nativeMs}ms; attach ${SystemClock.elapsedRealtime() - attachStarted}ms")
        } catch (error: Throwable) {
            fragments.forEach { it.entity.destroy() }
            fragments.clear()
            if (!destroyed) root.enabled = false
            throw error
        } finally {
            if (!transferred) pending.toList().asReversed().forEach { it.close() }
        }
    }

    val ready: Boolean get() = built && !destroyed

    /**
     * Fly the pieces between the intact Earth and the floor grid. Toward the city the plates stay
     * on the floor afterwards (call [retractFloor] once the city has risen); toward Earth the grid
     * must already be raised with [raiseFloor].
     */
    suspend fun play(
        toCity: Boolean,
        latitude: Float,
        longitude: Float,
        onSourceHidden: () -> Unit,
        onDestinationVisible: () -> Unit,
    ) {
        check(ready) { "Build the transition before animating" }
        val orientation = orientation(latitude, longitude)
        val earthCenters = fragments.map { fragmentRotate(orientation, it.earthCenter) }
        // The pieces facing the viewer leave first, so the breakup rolls from the tapped place outwards.
        val waves = earthCenters.map { ((1f - it.z / earthRadius) / 2f).coerceIn(0f, 1f) }
        val label = if (toCity) "Earth -> grid" else "grid -> Earth"
        var frames = 0
        var longestUpdateMs = 0L
        try {
            // The scan ripple must not move plates once they can take off.
            floorActive = false
            lifted.clear()
            update(if (toCity) 0f else 1f, orientation, earthCenters, waves, stagger = false)
            applyLook(if (toCity) 0f else 1f)
            root.enabled = true
            floor.enabled = true
            onSourceHidden()
            delay(if (toCity) 240 else 120)
            currentCoroutineContext().ensureActive()
            if (destroyed) return
            val started = SystemClock.elapsedRealtime()
            var previousFrame = started
            var flightMillis = 0L
            Log.i("SceneTransition", "Starting $label; $SCENE_FRAGMENT_COUNT fragments")
            do {
                currentCoroutineContext().ensureActive()
                val frameStarted = SystemClock.elapsedRealtime()
                flightMillis += (frameStarted - previousFrame).coerceIn(0L, 50L)
                previousFrame = frameStarted
                val progress = (flightMillis / FLIGHT_MILLIS.toFloat()).coerceIn(0f, 1f)
                val phase = if (toCity) progress else 1f - progress
                update(phase, orientation, earthCenters, waves, stagger = true)
                applyLook(phase)
                frames++
                longestUpdateMs = maxOf(longestUpdateMs, SystemClock.elapsedRealtime() - frameStarted)
                if (progress >= 1f) break
                delay(16)
            } while (!destroyed)
            if (!destroyed) {
                update(if (toCity) 1f else 0f, orientation, earthCenters, waves, stagger = false)
                applyLook(if (toCity) 1f else 0f)
                if (toCity) {
                    floorActive = true
                    floorSince = SystemClock.elapsedRealtime()
                } else {
                    root.enabled = false
                    floor.enabled = false
                    floorActive = false
                }
                onDestinationVisible()
                Log.i("SceneTransition", "Completed $label; $frames frames / " +
                    "${SystemClock.elapsedRealtime() - started}ms; longest transform update ${longestUpdateMs}ms")
            }
        } finally {
            if (!destroyed && !toCity) { root.enabled = false; floor.enabled = false; floorActive = false }
        }
    }

    /** Plates shrink into the grid and the grid fades, once the city stands on its own. */
    suspend fun retractFloor() {
        if (!ready || !floorActive) return
        floorActive = false
        lifted.clear()
        val started = SystemClock.elapsedRealtime()
        while (!destroyed) {
            val amount = ((SystemClock.elapsedRealtime() - started) / RETRACT_MILLIS.toFloat()).coerceIn(0f, 1f)
            val eased = amount * amount * (3f - 2f * amount)
            val scale = PLATE_SCALE * (1f - eased)
            fragments.forEach { fragment ->
                fragment.earthTransform.setScaleVector(Vector3(scale))
                fragment.tintedTransform.setScaleVector(Vector3(scale))
                fragment.wireTransform.setScaleVector(Vector3(scale))
                fragment.earthScale = scale
            }
            setFloorAlpha(1f - eased)
            if (amount >= 1f) break
            delay(16)
        }
        if (!destroyed) { root.enabled = false; floor.enabled = false }
        Log.i("SceneTransition", "Floor grid retracted")
    }

    /** Bring the grid and plates back under the city before it sinks and the pieces fly home. */
    suspend fun raiseFloor(latitude: Float, longitude: Float) {
        if (!ready) return
        val orientation = orientation(latitude, longitude)
        val earthCenters = fragments.map { fragmentRotate(orientation, it.earthCenter) }
        val waves = earthCenters.map { 0f }
        lifted.clear()
        update(1f, orientation, earthCenters, waves, stagger = false)
        applyLook(1f)
        root.enabled = true
        floor.enabled = true
        val started = SystemClock.elapsedRealtime()
        while (!destroyed) {
            val amount = ((SystemClock.elapsedRealtime() - started) / RAISE_MILLIS.toFloat()).coerceIn(0f, 1f)
            val eased = amount * amount * (3f - 2f * amount)
            fragments.forEach { fragment ->
                fragment.earthTransform.setScaleVector(Vector3(PLATE_SCALE * eased))
                fragment.tintedTransform.setScaleVector(Vector3(PLATE_SCALE * eased))
                fragment.wireTransform.setScaleVector(Vector3(PLATE_SCALE * eased))
                fragment.earthScale = PLATE_SCALE * eased
            }
            setFloorAlpha(eased)
            if (amount >= 1f) break
            delay(16)
        }
        floorActive = true
        floorSince = SystemClock.elapsedRealtime()
    }

    /** Scan line, pulse rings and the ripple of plates under the scan line while the grid is live. */
    fun tick(now: Long) {
        if (!floorActive || destroyed) return
        val half = volumeSide / 2f
        val scan = ((now - floorSince) % SCAN_PERIOD) / SCAN_PERIOD.toFloat()
        val scanZ = -half + scan * volumeSide
        scanTransform?.setPosition(Vector3(0f, 0.001f, scanZ))
        ringTransforms.forEachIndexed { index, ring ->
            val phase = (((now - floorSince) + index * 900L) % 1800L) / 1800f
            ring.setScaleVector(Vector3(0.3f + 3.4f * phase, 1f, 0.3f + 3.4f * phase))
        }
        ringMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.5f * (1f - scan * 0.2f)))
        val band = volumeSide * 0.05f
        fragments.forEachIndexed { index, fragment ->
            if (fragment.look != 2) return@forEachIndexed // only plates resting on the grid ripple
            val distance = abs(fragment.cell.z - scanZ)
            if (distance < band) {
                val lift = (1f - distance / band)
                fragment.transform.setPosition(Vector3(fragment.cell.x, fragment.cell.y + 0.014f * lift, fragment.cell.z))
                lifted += index
            } else if (lifted.remove(index)) {
                fragment.transform.setPosition(Vector3(fragment.cell.x, fragment.cell.y, fragment.cell.z))
            }
        }
    }

    private fun orientation(latitude: Float, longitude: Float) = quaternionMultiply(
        axisAngle(1f, 0f, 0f, latitude * PI.toFloat() / 180f),
        axisAngle(0f, 1f, 0f, -longitude * PI.toFloat() / 180f),
    )

    private fun update(
        progress: Float,
        orientation: Quat,
        centers: List<FragmentPoint>,
        waves: List<Float>,
        stagger: Boolean,
    ) {
        fragments.forEachIndexed { index, fragment ->
            val travel = if (stagger) ((progress - waves[index] * WAVE_WINDOW) / (1f - WAVE_WINDOW)).coerceIn(0f, 1f)
                else progress
            // Pieces that have not left yet, or have already landed, cost no native calls this frame.
            if (stagger && travel == fragment.lastTravel) return@forEachIndexed
            fragment.lastTravel = if (stagger) travel else -1f
            val t = smooth(travel)
            val lift = sin(PI.toFloat() * t)
            val start = centers[index]
            val end = fragment.cell
            val side = if (index % 2 == 0) 1f else -1f
            val swirl = ((index * 7) % 10) / 10f
            val x = start.x + (end.x - start.x) * t + side * volumeSide * (0.04f + 0.04f * swirl) * lift
            val y = start.y + (end.y - start.y) * t + volumeSide * (0.13f + 0.07f * swirl) * lift
            val z = start.z + (end.z - start.z) * t + sin(index * 2.4f) * volumeSide * 0.07f * lift
            fragment.transform.setPosition(Vector3(x, y, z))
            val spin = axisAngle(0.57735026f, side * 0.57735026f, 0.57735026f,
                side * lift * PI.toFloat() * (1.1f + 0.5f * swirl))
            fragment.transform.setQuaternion(quaternionMultiply(spin, quaternionSlerp(orientation, fragment.flat, t)))
            val earthScale = quantize(1f - (1f - PLATE_SCALE) * smooth(((t - 0.25f) / 0.6f).coerceIn(0f, 1f)))
            if (earthScale != fragment.earthScale) {
                fragment.earthTransform.setScaleVector(Vector3(earthScale))
                fragment.tintedTransform.setScaleVector(Vector3(earthScale))
                fragment.wireTransform.setScaleVector(Vector3(earthScale))
                fragment.earthScale = earthScale
            }
            // Photo near the sphere, cyan-tinted in flight, wireframe near the grid: judged per piece.
            val look = when { t < 0.28f -> 0; t < 0.62f -> 1; else -> 2 }
            if (look != fragment.look) {
                fragment.look = look
                fragment.piece.enabled = look == 0
                fragment.tinted.enabled = look == 1
                fragment.wire.enabled = look == 2
            }
        }
    }

    /** The floor grid fades in as the flight approaches the grid end; piece looks are handled per piece. */
    private fun applyLook(phase: Float) {
        setFloorAlpha(smooth(((phase - 0.45f) / 0.45f).coerceIn(0f, 1f)))
    }

    private fun setFloorAlpha(alpha: Float) {
        val quantized = quantize(alpha)
        if (quantized == floorAlpha) return
        floorAlpha = quantized
        gridMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.38f * quantized))
        scanMaterial?.setBaseColor(linearColor(0x9FF1FF, 0.75f * quantized))
        if (quantized == 0f) ringMaterial?.setBaseColor(linearColor(0x4FE3FF, 0f))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        floorActive = false
        root.destroy()
        floor.destroy()
        fragments.clear()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private fun mesh(geometry: FragmentGeometry, name: String): MeshResource =
        MeshResource.createWithMeshModel(MeshModel(
            positions = geometry.vertices.map { Vector3(it.point.x, it.point.y, it.point.z) },
            triangleIndices = geometry.indices,
            uv0 = geometry.vertices.map { Vector2(it.u, it.v) },
        ), null, name).also { resource ->
            try { resource.toGlobal() }
            catch (error: Throwable) { resource.close(); throw error }
        }

    private fun flatQuad(width: Float, depth: Float): MeshResource {
        val hw = width / 2f; val hd = depth / 2f
        return MeshResource.createWithMeshModel(MeshModel(
            listOf(Vector3(-hw, 0f, -hd), Vector3(hw, 0f, -hd), Vector3(hw, 0f, hd), Vector3(-hw, 0f, hd)),
            listOf(0, 1, 2, 0, 2, 3)), BoundingBox(Vector3(-hw, -0.001f, -hd), Vector3(hw, 0.001f, hd)), "scan-line")
    }

    private fun Entity.transform() = checkNotNull(components[TransformComponent::class.java])
    private fun smooth(value: Float) = value * value * (3f - 2f * value)
    private fun quantize(value: Float) = kotlin.math.round(value * 24f) / 24f
}

private const val FLIGHT_MILLIS = 4_600L
private const val RETRACT_MILLIS = 700L
private const val RAISE_MILLIS = 500L
private const val SCAN_PERIOD = 1_700L
/** Each piece flies for about a quarter of the window; the wave rolls from the tapped place to the far side. */
private const val WAVE_WINDOW = 0.72f
/** Landed plates are scaled so two of them fit inside one floor cell. */
private const val PLATE_SCALE = 0.42f

private fun axisAngle(x: Float, y: Float, z: Float, angle: Float): Quat {
    val s = sin(angle / 2f)
    return Quat(x * s, y * s, z * s, cos(angle / 2f))
}

private fun fragmentRotate(q: Quat, p: FragmentPoint): FragmentPoint {
    val cx = q.y * p.z - q.z * p.y
    val cy = q.z * p.x - q.x * p.z
    val cz = q.x * p.y - q.y * p.x
    return FragmentPoint(p.x + 2f * (q.w * cx + q.y * cz - q.z * cy),
        p.y + 2f * (q.w * cy + q.z * cx - q.x * cz), p.z + 2f * (q.w * cz + q.x * cy - q.y * cx))
}
