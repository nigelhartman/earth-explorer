package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.example.earthexplorer.data.*
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import kotlin.math.*

/** Native overlays share the Earth's rotating parent. Only the moving satellites update each second. */
internal class GlobeLayerScene(parent: Entity, private val radius: Float) {
    /** One instanced marker set split into longitude sectors, so an activation sweep can reveal it in order. */
    private class Band(val material: UnlitMaterial, parent: Entity, mesh: MeshResource) {
        val sectors = Array(SWEEP_SECTORS) { ModelEntity(mesh, material).also { parent.addChild(it); it.enabled = false } }
        val instances = arrayOfNulls<MeshInstancesResource>(SWEEP_SECTORS)
        val populated = BooleanArray(SWEEP_SECTORS)
        fun close() { sectors.forEach { it.destroy() }; instances.forEach { it?.close() } }
    }
    private class Sweep(val layer: FeatureLayer, val started: Long, val startLongitude: Float)
    /** A moment to look at the globe before a newly activated layer sweeps in. */
    private val pendingSweeps = HashMap<FeatureLayer, Long>()
    private val root = Entity().also { parent.addChild(it) }
    private val aircraftRoot = Entity().also { root.addChild(it) }
    private val earthquakeRoot = Entity().also { root.addChild(it) }
    private val positionsRoot = Entity().also { root.addChild(it) }
    private val webcamsRoot = Entity().also { root.addChild(it) }
    private val resources = mutableListOf<Resource>()
    private val physicsMaterial = keep(PhysicsMaterialResource())
    private val features = mutableMapOf<FeatureLayer, List<GlobeFeature>>()
    private val satellites = mutableMapOf<String, Entity>()
    private val webcams = mutableMapOf<String, Entity>()
    private val webcamMesh = keep(MeshResource.createBox(Vector3(radius * 0.035f, radius * 0.025f, radius * 0.018f), 0f))
    private val webcamLens = keep(MeshResource.createSphere(radius * 0.009f))
    private val webcamShape = keep(ShapeResource.createSphere(radius * 0.028f))
    private val webcamMaterial = material(0x54F0D1)
    private val webcamLensMaterial = material(0x123951)
    private val aircraftMesh = keep(airplaneMesh(radius * 0.034f))
    private val quakeMesh = keep(MeshResource.createSphere(radius * 0.005f))
    private val satelliteMesh = keep(MeshResource.createSphere(radius * 0.013f))
    private val satelliteShape = keep(ShapeResource.createSphere(radius * 0.025f))
    private val issShape = keep(ShapeResource.createSphere(radius * 0.07f))
    private val issBody = keep(MeshResource.createBox(Vector3(radius * 0.018f, radius * 0.050f, radius * 0.018f), 0f))
    private val issPanel = keep(MeshResource.createBox(Vector3(radius * 0.043f, radius * 0.04f, radius * 0.005f), 0f))
    private val satelliteMaterial = material(0x62D9FF)
    private val issMaterial = material(0xFFD76B)
    private val issPanelMaterial = material(0x297BC1)
    private val selectedMaterial = material(0xFFFFFF)
    private val pulseRoot = Entity().also { earthquakeRoot.addChild(it) }
    private val pulseMesh = keep(ringMesh(radius * 0.018f, radius * 0.0022f, "quake-pulse"))
    private val pulseMaterials = List(3) { keep(UnlitMaterial.create(BlendingMode.FADE)).apply { setCullingMode(MaterialCullingMode.NONE) } }
    private class Pulse(val entity: Entity, val transform: TransformComponent, val group: Int, val size: Float, val sector: Int)
    private val pulses = ArrayList<Pulse>()
    private val issHaloMesh = keep(ringMesh(radius * 0.055f, radius * 0.003f, "iss-halo"))
    private val issHaloMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply { setCullingMode(MaterialCullingMode.NONE) }
    private var issHalo: Entity? = null
    private var issId: String? = null
    private val aircraftBands = listOf(0x4FA3D1, 0x5FBF7E, 0xE0A526, 0xE8734A, 0xD2604A)
        .map { color -> Band(material(color), aircraftRoot, aircraftMesh) }
    private val quakeBands = listOf(0xFFD36E, 0xF18D4C, 0x69ACEF, 0x7775E4)
        .map { color -> Band(material(color), earthquakeRoot, quakeMesh) }
    /** Global traffic reads as density glow; individual airliners only appear near the focus. */
    private val densityMesh = keep(MeshResource.createSphere(radius * 0.0075f))
    private val densityMaterial = keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
        setBaseColor(linearColor(0x5CC8FF, 0.55f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
    }
    private val densityBand = Band(densityMaterial, aircraftRoot, densityMesh)
    private var aircraftFocus: GlobeFocus? = null
    private var aircraftRebuiltAt = 0L
    private val revealed = FeatureLayer.values().associateWith { BooleanArray(SWEEP_SECTORS) { true } }
    private val sweepMesh = keep(ringMesh(radius * 1.06f, radius * 0.004f, "layer-sweep"))
    private val sweepMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
        setBaseColor(linearColor(0x4FE3FF, 0f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
    }
    private val sweepRing = ModelEntity(sweepMesh, sweepMaterial).also { root.addChild(it); it.enabled = false }
    private var sweep: Sweep? = null
    private var lastSlowTick = 0L
    /** The orbit shells belong to the Positions reveal: hidden until that layer's sweep begins. */
    private val shellsRoot = Entity().apply { enabled = false }
    private val shellMesh = keep(ringMesh(radius * 1.29f, radius * 0.0016f, "gps-shell"))
    private val shellMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
        setBaseColor(linearColor(0xB08CFF, 0.2f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
    }
    private val selectedCone = ModelEntity(aircraftMesh, selectedMaterial).also { aircraftRoot.addChild(it); it.enabled = false }
    private val selectedDot = ModelEntity(satelliteMesh, selectedMaterial).also { root.addChild(it); it.enabled = false }
    private val issLabel = createIssLabel().also { positionsRoot.addChild(it); it.enabled = false }
    private var orientation = Quat(0f, 0f, 0f, 1f)
    private var selectedId: String? = null

    init {
        // Capture events outside every aircraft; exact ray tests below ignore this shell's depth.
        val shape = keep(ShapeResource.createSphere(radius * 1.14f))
        aircraftRoot.addChild(Entity().apply {
            setName("layer:aircraft")
            components.set(CollisionComponent(listOf(shape), physicsMaterial))
            components.set(InteractableComponent())
        })
        val quakeShape = keep(ShapeResource.createSphere(radius * 1.045f))
        earthquakeRoot.addChild(Entity().apply {
            setName("layer:earthquakes")
            components.set(CollisionComponent(listOf(quakeShape), physicsMaterial))
            components.set(InteractableComponent())
        })
        // Six GPS orbital planes: 55° inclination, ascending nodes 60° apart, at the compressed display radius.
        positionsRoot.addChild(shellsRoot)
        for (plane in 0 until 6) {
            shellsRoot.addChild(ModelEntity(shellMesh, shellMaterial).apply {
                setName("GpsShell$plane")
                components[TransformComponent::class.java]?.setQuaternion(multiply(
                    axisAngle(0f, 1f, 0f, Math.toRadians(plane * 60.0).toFloat()),
                    axisAngle(1f, 0f, 0f, Math.toRadians(55.0).toFloat())))
            })
        }
        setVisible(FeatureLayer.AIRCRAFT, MapLayers.aircraftVisible.value)
        setVisible(FeatureLayer.EARTHQUAKES, MapLayers.earthquakesVisible.value)
        setVisible(FeatureLayer.POSITIONS, MapLayers.positionsVisible.value)
        setVisible(FeatureLayer.WEBCAMS, MapLayers.webcamsVisible.value)
    }

    fun setFeatures(layer: FeatureLayer, list: List<GlobeFeature>) {
        val hadFeatures = features[layer].orEmpty().isNotEmpty()
        features[layer] = list
        if (!MapLayers.isVisible(layer)) return
        when (layer) {
            FeatureLayer.AIRCRAFT -> {
                updateAircraft(list, GlobeSelection.focus.value)
                updateDensity(list)
                Log.i("GlobeLayers", "Placed ${list.size} OpenSky aircraft: density bins plus airliners near the focus")
            }
            FeatureLayer.EARTHQUAKES -> {
                val groups = list.groupBy { when {
                    (it.depthKilometers ?: 0.0) < 70 -> 0
                    (it.depthKilometers ?: 0.0) < 300 -> 1
                    (it.depthKilometers ?: 0.0) < 500 -> 2
                    else -> 3
                } }
                quakeBands.forEachIndexed { index, band -> updateBand(band, groups[index].orEmpty(), "earthquakes-$index") }
                updatePulses(list)
                Log.i("GlobeLayers", "Placed ${list.size} USGS epicentres")
            }
            FeatureLayer.POSITIONS -> updateSatellites(list)
            FeatureLayer.WEBCAMS -> updateWebcams(list)
            FeatureLayer.SUN -> Unit
        }
        applyReveal(layer)
        updateSelection()
        if (!hadFeatures && list.isNotEmpty() && layer != FeatureLayer.SUN) startSweep(layer)
    }

    fun setVisible(layer: FeatureLayer, visible: Boolean) {
        when (layer) {
            FeatureLayer.AIRCRAFT -> aircraftRoot.enabled = visible
            FeatureLayer.EARTHQUAKES -> earthquakeRoot.enabled = visible
            FeatureLayer.POSITIONS -> positionsRoot.enabled = visible
            FeatureLayer.WEBCAMS -> webcamsRoot.enabled = visible
            FeatureLayer.SUN -> Unit
        }
        if (visible) features[layer]?.let { setFeatures(layer, it) }
        updateSelection()
        if (visible && (layer == FeatureLayer.SUN || features[layer].orEmpty().isNotEmpty())) startSweep(layer)
    }

    /** Hide the layer's markers and schedule its sweep for one second from now. */
    fun startSweep(layer: FeatureLayer) {
        revealed.getValue(layer).fill(false)
        applyReveal(layer)
        if (layer == FeatureLayer.POSITIONS) shellsRoot.enabled = false
        pendingSweeps[layer] = android.os.SystemClock.elapsedRealtime() + SWEEP_DELAY_MILLIS
    }

    /** A bright meridian in the layer's accent sweeps once around the globe, revealing markers as it passes. */
    private fun beginSweep(layer: FeatureLayer) {
        if (layer == FeatureLayer.POSITIONS) shellsRoot.enabled = true
        val color = when (layer) {
            FeatureLayer.AIRCRAFT -> 0x5CC8FF; FeatureLayer.EARTHQUAKES -> 0xFF7A45
            FeatureLayer.POSITIONS -> 0xB08CFF; FeatureLayer.WEBCAMS -> 0x3EE8C5; FeatureLayer.SUN -> 0xFFD166
        }
        sweep?.let { finishSweep(it) }
        revealed.getValue(layer).fill(false)
        applyReveal(layer)
        sweep = Sweep(layer, android.os.SystemClock.elapsedRealtime(), GlobeSelection.focus.value.longitude.toFloat())
        sweepMaterial.setBaseColor(linearColor(color, 0.9f))
        sweepRing.enabled = true
    }

    private fun finishSweep(active: Sweep) {
        revealed.getValue(active.layer).fill(true)
        applyReveal(active.layer)
        sweepRing.enabled = false
        sweep = null
    }

    private fun applyReveal(layer: FeatureLayer) {
        val mask = revealed.getValue(layer)
        fun bands(list: List<Band>) = list.forEach { band ->
            for (sector in 0 until SWEEP_SECTORS) band.sectors[sector].enabled = band.populated[sector] && mask[sector]
        }
        when (layer) {
            FeatureLayer.AIRCRAFT -> { bands(aircraftBands); bands(listOf(densityBand)) }
            FeatureLayer.EARTHQUAKES -> {
                bands(quakeBands)
                pulses.forEach { it.entity.enabled = mask[it.sector] }
            }
            FeatureLayer.POSITIONS -> features[layer]?.forEach { feature ->
                satellites[feature.id]?.enabled = mask[sectorOf(feature.longitude)]
            }
            FeatureLayer.WEBCAMS -> features[layer]?.forEach { feature ->
                webcams[feature.id]?.enabled = mask[sectorOf(feature.longitude)]
            }
            FeatureLayer.SUN -> Unit
        }
    }

    private fun updateAircraft(list: List<GlobeFeature>, focus: GlobeFocus) {
        aircraftFocus = focus
        aircraftRebuiltAt = android.os.SystemClock.elapsedRealtime()
        // A bounded set of the closest airliners keeps the instanced geometry light on the headset.
        val near = list.map { it to FeatureGeometry.angularDistanceDegrees(it.latitude, it.longitude, focus.latitude, focus.longitude) }
            .filter { it.second < AIRCRAFT_DETAIL_DEGREES }.sortedBy { it.second }.take(AIRCRAFT_DETAIL_LIMIT).map { it.first }
        val groups = near.groupBy { when {
            it.altitudeMeters < 2_438 -> 0
            it.altitudeMeters < 6_096 -> 1
            it.altitudeMeters < 9_754 -> 2
            it.altitudeMeters < 12_497 -> 3
            else -> 4
        } }
        aircraftBands.forEachIndexed { index, band -> updateBand(band, groups[index].orEmpty(), "aircraft-$index") }
    }

    private fun updateDensity(list: List<GlobeFeature>) {
        val bins = HashMap<Long, Int>()
        list.forEach { feature ->
            val row = ((feature.latitude + 90.0) / DENSITY_DEGREES).toInt()
            val column = ((feature.longitude + 180.0) / DENSITY_DEGREES).toInt()
            bins.merge(row * 1000L + column, 1, Int::plus)
        }
        val blobs = bins.entries.sortedByDescending { it.value }.take(DENSITY_LIMIT).map { (key, count) ->
            val latitude = (key / 1000) * DENSITY_DEGREES - 90.0 + DENSITY_DEGREES / 2
            val longitude = (key % 1000) * DENSITY_DEGREES - 180.0 + DENSITY_DEGREES / 2
            val direction = FeatureGeometry.direction(latitude, longitude) * (radius * 1.02)
            val scale = (0.6f + 0.5f * sqrt(count.toFloat())).coerceAtMost(4.2f)
            GlobeFeature("density:$key", FeatureLayer.AIRCRAFT, latitude, longitude, "", observedAtMillis = 0L, source = "") to
                Transform(Vector3(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()), Quat(0f, 0f, 0f, 1f), Vector3(scale))
        }
        updateBandTransforms(densityBand, blobs, "aircraft-density")
    }

    fun select(id: String?) { selectedId = id; updateSelection() }

    fun pick(ray: FeaturePickRay?, point: DVec3, entityName: String?,
        extras: Collection<GlobeFeature> = emptyList()): GlobeFeature? {
        val visible = features.filterKeys(MapLayers::isVisible).values.flatten() + extras
        if (ray != null) return FeatureGeometry.pickRay(visible, ray, radius.toDouble())
        // The SDK already resolved actual native colliders for satellites and the Sun.
        if (entityName?.startsWith("feature:") == true) {
            visible.firstOrNull { "feature:${it.id}" == entityName }?.let { return it }
        }
        return FeatureGeometry.pickNearPoint(visible, point, radius.toDouble())
    }

    fun setOrientation(value: Quat) {
        orientation = value
        positionIssLabel()
        orientWebcams()
        // Airliners follow the focus: rebuild the detailed set once the view has moved on.
        val list = features[FeatureLayer.AIRCRAFT].orEmpty()
        val focus = GlobeSelection.focus.value
        val previous = aircraftFocus
        if (list.isNotEmpty() && aircraftRoot.enabled && previous != null &&
            android.os.SystemClock.elapsedRealtime() - aircraftRebuiltAt > AIRCRAFT_REBUILD_MILLIS &&
            FeatureGeometry.angularDistanceDegrees(previous.latitude, previous.longitude, focus.latitude, focus.longitude) > 7.0) {
            updateAircraft(list, focus)
            applyReveal(FeatureLayer.AIRCRAFT)
            updateSelection()
        }
    }

    /** Ambient motion: the newest epicenters emit expanding rings and the ISS carries a halo. */
    fun tick(now: Long) {
        // Every material change is a call into the render service; pulses need far fewer than 30 per second.
        val slow = now - lastSlowTick >= 66
        if (slow) lastSlowTick = now
        if (pendingSweeps.isNotEmpty() && sweep == null) {
            pendingSweeps.entries.firstOrNull { it.value <= now }?.let { due ->
                pendingSweeps.remove(due.key)
                if (MapLayers.isVisible(due.key) || due.key == FeatureLayer.SUN) beginSweep(due.key) else { revealed.getValue(due.key).fill(true); applyReveal(due.key) }
            }
        }
        sweep?.let { active ->
            val k = (now - active.started) / SWEEP_MILLIS.toFloat()
            if (k >= 1f) { finishSweep(active); return@let }
            val angle = k * 360f
            val longitude = active.startLongitude + angle
            sweepRing.components[TransformComponent::class.java]?.setQuaternion(multiply(
                axisAngle(0f, 1f, 0f, Math.toRadians(longitude.toDouble()).toFloat()),
                axisAngle(0f, 0f, 1f, (PI / 2).toFloat())))
            val mask = revealed.getValue(active.layer)
            var changed = false
            for (sector in 0 until SWEEP_SECTORS) {
                if (mask[sector]) continue
                val center = -180f + sector * (360f / SWEEP_SECTORS) + 180f / SWEEP_SECTORS
                val passed = ((center - active.startLongitude) % 360f + 360f) % 360f
                if (passed <= angle) { mask[sector] = true; changed = true }
            }
            if (changed) applyReveal(active.layer)
            if (k > 0.85f) {
                val fade = 1f - (k - 0.85f) / 0.15f
                val current = sweepMaterial
                current.setOpacity(fade)
            } else sweepMaterial.setOpacity(1f)
        }
        if (slow && earthquakeRoot.enabled && pulses.isNotEmpty()) {
            for (group in 0 until 3) {
                val phase = ((now + group * PULSE_PERIOD / 3) % PULSE_PERIOD) / PULSE_PERIOD.toFloat()
                val fade = (1f - phase) * (1f - phase)
                pulseMaterials[group].setBaseColor(linearColor(0xFF7A45, 0.85f * fade))
                val growth = 0.4f + 3.6f * phase
                pulses.forEach { pulse ->
                    if (pulse.group == group) pulse.transform.setScaleVector(Vector3(growth * pulse.size, 1f, growth * pulse.size))
                }
            }
        }
        val halo = issHalo
        if (slow && positionsRoot.enabled && halo != null) {
            val phase = (now % 1800L) / 1800f
            val scale = 1f + 0.9f * phase
            halo.components[TransformComponent::class.java]?.setScaleVector(Vector3(scale, scale, scale))
            issHaloMaterial.setBaseColor(linearColor(0xFFD76B, 0.8f * (1f - phase)))
        }
    }

    private fun updatePulses(list: List<GlobeFeature>) {
        pulses.forEach { it.entity.destroy() }
        pulses.clear()
        list.sortedByDescending { it.observedAtMillis }.take(8).forEachIndexed { index, feature ->
            val p = position(feature)
            val length = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            if (length < 1e-6f) return@forEachIndexed
            val up = Vector3(p.x / length, p.y / length, p.z / length)
            val entity = ModelEntity(pulseMesh, pulseMaterials[index % 3])
            entity.enabled = revealed.getValue(FeatureLayer.EARTHQUAKES)[sectorOf(feature.longitude)]
            val transform = entity.components[TransformComponent::class.java] ?: return@forEachIndexed
            transform.setPosition(Vector3(up.x * radius * 1.004f, up.y * radius * 1.004f, up.z * radius * 1.004f))
            transform.setQuaternion(quaternionFromUp(up))
            pulseRoot.addChild(entity)
            val size = (1.0 + ((feature.magnitude ?: 5.4) - 5.4).coerceIn(0.0, 4.0) * 0.5).toFloat()
            pulses += Pulse(entity, transform, index % 3, size, sectorOf(feature.longitude))
        }
    }

    private fun updateWebcams(list: List<GlobeFeature>) {
        val ids = list.map { it.id }.toSet()
        webcams.keys.toList().filter { it !in ids }.forEach { webcams.remove(it)?.destroy() }
        list.forEach { feature ->
            val entity = webcams.getOrPut(feature.id) {
                Entity().apply {
                    setName("feature:${feature.id}")
                    components.set(CollisionComponent(listOf(webcamShape), physicsMaterial))
                    components.set(InteractableComponent())
                    addChild(ModelEntity(webcamMesh, webcamMaterial))
                    addChild(ModelEntity(webcamLens, webcamLensMaterial).apply {
                        components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, radius * 0.012f))
                    })
                    webcamsRoot.addChild(this)
                }
            }
            entity.components[TransformComponent::class.java]?.setPosition(position(feature))
        }
        orientWebcams()
    }

    private fun orientWebcams() {
        val inverse = Quat(-orientation.x, -orientation.y, -orientation.z, orientation.w)
        webcams.values.forEach { it.components[TransformComponent::class.java]?.setQuaternion(inverse) }
    }

    private fun updateBand(band: Band, list: List<GlobeFeature>, name: String) =
        updateBandTransforms(band, list.map { it to transform(it) }, name)

    private fun updateBandTransforms(band: Band, list: List<Pair<GlobeFeature, Transform>>, name: String) {
        val bySector = list.groupBy { sectorOf(it.first.longitude) }
        for (sector in 0 until SWEEP_SECTORS) {
            val entity = band.sectors[sector]
            val members = bySector[sector].orEmpty()
            entity.components[ModelComponent::class.java]?.meshInstances = null
            band.instances[sector]?.close()
            band.instances[sector] = null
            band.populated[sector] = members.isNotEmpty()
            if (members.isEmpty()) { entity.enabled = false; continue }
            val instances = MeshInstancesResource.create("$name-$sector",
                members.map { (feature, transform) -> MeshInstancesResource.Instance(feature.id, transform) }).also { it.toGlobal() }
            entity.components[ModelComponent::class.java]?.meshInstances = instances
            band.instances[sector] = instances
        }
    }

    private fun sectorOf(longitude: Double): Int =
        (((longitude + 180.0) / (360.0 / SWEEP_SECTORS)).toInt()).coerceIn(0, SWEEP_SECTORS - 1)

    private fun updateSatellites(list: List<GlobeFeature>) {
        val ids = list.map { it.id }.toSet()
        satellites.keys.toList().filter { it !in ids }.forEach { id ->
            if (id == issId) { issHalo = null; issId = null }
            satellites.remove(id)?.destroy()
        }
        list.forEach { feature ->
            val entity = satellites.getOrPut(feature.id) {
                Entity().apply {
                    setName("feature:${feature.id}")
                    components.set(CollisionComponent(listOf(if (feature.highlighted) issShape else satelliteShape), physicsMaterial))
                    components.set(InteractableComponent())
                    if (feature.highlighted) {
                        addChild(ModelEntity(issBody, issMaterial))
                        issHalo = ModelEntity(issHaloMesh, issHaloMaterial).also { addChild(it) }
                        issId = feature.id
                        for (side in listOf(-1, 1)) addChild(ModelEntity(issPanel, issPanelMaterial).apply {
                            components[TransformComponent::class.java]?.setPosition(Vector3(side * radius * 0.035f, 0f, 0f))
                        })
                    } else addChild(ModelEntity(satelliteMesh, satelliteMaterial))
                    positionsRoot.addChild(this)
                }
            }
            entity.components[TransformComponent::class.java]?.setPosition(position(feature))
        }
        positionIssLabel()
    }

    private fun updateSelection() {
        if (selectedId == null) {
            selectedCone.enabled = false
            selectedDot.enabled = false
            return
        }
        val selected = features.filterKeys(MapLayers::isVisible).values.flatten().firstOrNull { it.id == selectedId }
        selectedCone.enabled = selected?.layer == FeatureLayer.AIRCRAFT
        selectedDot.enabled = selected != null && selected.layer != FeatureLayer.AIRCRAFT && !selected.highlighted
        if (selected == null) return
        val entity = if (selected.layer == FeatureLayer.AIRCRAFT) selectedCone else selectedDot
        val transform = transform(selected, if (selected.layer == FeatureLayer.AIRCRAFT) 2.3f else 1.4f)
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(transform.position); setQuaternion(transform.quaternion); setScaleVector(transform.scale)
        }
    }

    private fun positionIssLabel() {
        val iss = features[FeatureLayer.POSITIONS]?.firstOrNull { it.highlighted }
        issLabel.enabled = iss != null && MapLayers.positionsVisible.value
        if (iss == null) return
        val inverse = Quat(-orientation.x, -orientation.y, -orientation.z, orientation.w)
        val offset = rotate(inverse, Vector3(0f, radius * 0.09f, 0f))
        val p = position(iss)
        issLabel.components[TransformComponent::class.java]?.apply {
            setPosition(Vector3(p.x + offset.x, p.y + offset.y, p.z + offset.z))
            setQuaternion(inverse)
        }
        // The halo ring lies in XZ; tilt it upright, then face the viewer like the label.
        issHalo?.components?.get(TransformComponent::class.java)
            ?.setQuaternion(multiply(inverse, Quat(0.70710678f, 0f, 0f, 0.70710678f)))
    }

    private fun createIssLabel(): ModelEntity {
        val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText("ISS", 128f, 73f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 220, 116)
            textAlign = Paint.Align.CENTER; textSize = 82f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        })
        val texture = try { keep(TextureResource.create(bitmap)) } finally { bitmap.recycle() }
        val material = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
            setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE)
        }
        return ModelEntity(keep(MeshResource.createPlane(radius * 0.17f, radius * 0.064f, 0.001f)), material)
    }

    private fun position(feature: GlobeFeature): Vector3 {
        val p = FeatureGeometry.direction(feature.latitude, feature.longitude) * FeatureGeometry.displayRadius(feature, radius.toDouble())
        return Vector3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
    }

    private fun transform(feature: GlobeFeature, size: Float = 1f): Transform {
        val scale = if (feature.layer == FeatureLayer.EARTHQUAKES)
            (1.0 + ((feature.magnitude ?: 5.4) - 5.4).coerceIn(0.0, 4.0) * 0.65).toFloat() * size else size
        return Transform(position(feature), if (feature.layer == FeatureLayer.AIRCRAFT)
            headingRotation(feature.latitude, feature.longitude, feature.headingDegrees) else Quat(0f, 0f, 0f, 1f), Vector3(scale))
    }

    private fun material(rgb: Int): UnlitMaterial = keep(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
        fun channel(shift: Int) = (((rgb shr shift) and 255) / 255.0).pow(2.2).toFloat()
        setBaseColor(Color4(channel(16), channel(8), channel(0), 1f))
        setCullingMode(MaterialCullingMode.NONE)
    }

    private fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources += it }

    fun destroy() {
        aircraftBands.forEach { it.close() }; quakeBands.forEach { it.close() }; densityBand.close()
        root.destroy()
        resources.asReversed().forEach { it.close() }
    }
}

/** Cone +Y points along the OpenSky true ground track; +Z points away from the globe. */
private fun headingRotation(latitude: Double, longitude: Double, heading: Double): Quat {
    val lat = Math.toRadians(latitude); val lon = Math.toRadians(longitude); val track = Math.toRadians(heading)
    val up = FeatureGeometry.direction(latitude, longitude)
    val north = DVec3(-sin(lat) * sin(lon), cos(lat), -sin(lat) * cos(lon))
    val east = DVec3(cos(lon), 0.0, -sin(lon))
    val forward = north * cos(track) + east * sin(track)
    val right = DVec3(forward.y * up.z - forward.z * up.y,
        forward.z * up.x - forward.x * up.z, forward.x * up.y - forward.y * up.x)
    val m00 = right.x; val m01 = forward.x; val m02 = up.x
    val m10 = right.y; val m11 = forward.y; val m12 = up.y
    val m20 = right.z; val m21 = forward.z; val m22 = up.z
    val trace = m00 + m11 + m22
    val q = if (trace > 0) {
        val s = 2 * sqrt(trace + 1)
        doubleArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, s / 4)
    } else if (m00 > m11 && m00 > m22) {
        val s = 2 * sqrt(1 + m00 - m11 - m22)
        doubleArrayOf(s / 4, (m10 + m01) / s, (m02 + m20) / s, (m21 - m12) / s)
    } else if (m11 > m22) {
        val s = 2 * sqrt(1 + m11 - m00 - m22)
        doubleArrayOf((m10 + m01) / s, s / 4, (m21 + m12) / s, (m02 - m20) / s)
    } else {
        val s = 2 * sqrt(1 + m22 - m00 - m11)
        doubleArrayOf((m02 + m20) / s, (m21 + m12) / s, s / 4, (m10 - m01) / s)
    }
    return Quat(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
}

private const val PULSE_PERIOD = 2_600L
private const val SWEEP_SECTORS = 12
private const val SWEEP_MILLIS = 1_900L
private const val SWEEP_DELAY_MILLIS = 2_000L
private const val AIRCRAFT_DETAIL_DEGREES = 16.0
private const val AIRCRAFT_DETAIL_LIMIT = 400
private const val AIRCRAFT_REBUILD_MILLIS = 2_500L
private const val DENSITY_DEGREES = 5.0
private const val DENSITY_LIMIT = 900

private fun axisAngle(x: Float, y: Float, z: Float, angle: Float): Quat {
    val s = sin(angle / 2f)
    return Quat(x * s, y * s, z * s, cos(angle / 2f))
}

private fun multiply(a: Quat, b: Quat) = Quat(
    a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
)

private fun rotate(q: Quat, v: Vector3): Vector3 {
    val cx = q.y * v.z - q.z * v.y; val cy = q.z * v.x - q.x * v.z; val cz = q.x * v.y - q.y * v.x
    return Vector3(v.x + 2 * (q.w * cx + q.y * cz - q.z * cy),
        v.y + 2 * (q.w * cy + q.z * cx - q.x * cz), v.z + 2 * (q.w * cz + q.x * cy - q.y * cx))
}
