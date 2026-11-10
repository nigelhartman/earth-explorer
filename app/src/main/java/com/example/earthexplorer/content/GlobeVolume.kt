package com.example.earthexplorer.content

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.ASSISTANT_WINDOW
import com.example.earthexplorer.LOCATION_WINDOW
import com.example.earthexplorer.LAYERS_WINDOW
import com.example.earthexplorer.data.LocationStore
import com.example.earthexplorer.data.CapitalCities
import com.example.earthexplorer.data.GlobeSelection
import com.example.earthexplorer.data.MapLayers
import com.example.earthexplorer.data.PanelVisibility
import com.example.earthexplorer.data.AssistantDemo
import com.example.earthexplorer.data.AssistantState
import com.example.earthexplorer.data.RendererMode
import com.example.earthexplorer.data.SelectedLocation
import com.example.earthexplorer.data.AircraftLayerStore
import com.example.earthexplorer.data.EarthquakeLayerStore
import com.example.earthexplorer.data.PositionsLayerStore
import com.example.earthexplorer.data.WebcamLayerStore
import com.example.earthexplorer.data.FeatureLayer
import com.example.earthexplorer.data.GlobeFeature
import com.example.earthexplorer.data.FeatureGeometry
import com.example.earthexplorer.data.FeaturePickRay
import com.example.earthexplorer.data.DVec3
import com.example.earthexplorer.data.FeatureSelection
import com.example.earthexplorer.data.SolarPosition
import com.example.earthexplorer.data.SolarSnapshot
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.PolygonFillMode
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.math.Vector4
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.SpatialPointerInfo
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val GLOBE_NAME = "globe"

@Composable
fun GlobeVolume() {
    val selected by LocationStore.selected.collectAsState()
    val renderState by LocationStore.renderState.collectAsState()
    val capitalChoice by GlobeSelection.choice.collectAsState()
    val navigator = LocalSpatialNavigator.current

    val panelsVisible by PanelVisibility.visible.collectAsState()

    LaunchedEffect(renderState.mode, selected?.latitude, selected?.longitude, capitalChoice, panelsVisible) {
        if (!panelsVisible) {
            // Cinema mode: every panel, including the assistant orb, goes away.
            listOf(LOCATION_WINDOW, LAYERS_WINDOW, ASSISTANT_WINDOW).forEach { navigator.closeWindowContainer(it, it) }
            return@LaunchedEffect
        }
        // Keep navigation and layer controls beside the shared globe/city volume.
        navigator.openWindowContainer(LOCATION_WINDOW, LOCATION_WINDOW, Bundle())
        navigator.openWindowContainer(LAYERS_WINDOW, LAYERS_WINDOW, Bundle())
        // The assistant hangs below the layers window, so that one must be positioned first.
        delay(900)
        navigator.openWindowContainer(ASSISTANT_WINDOW, ASSISTANT_WINDOW, Bundle())
    }

    NativeExplorerVolume(selected.takeIf { renderState.mode == RendererMode.CITY_MESH })
}

@Composable
private fun NativeExplorerVolume(location: SelectedLocation?) {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val volumeSide = remember(converter) { converter.dpToLength(1100.dp, LengthUnit.Meters) }
    val radius = volumeSide * 0.33f
    val scene = remember(radius) { GlobeScene() }
    val explorer = remember(scene, volumeSide) { ExplorerScene(scene, volumeSide, context.assets) }
    val sunVisible by MapLayers.sunVisible.collectAsState()

    DisposableEffect(explorer) {
        onDispose(explorer::destroy)
    }
    LaunchedEffect(explorer, location?.latitude, location?.longitude) {
        explorer.show(location)
    }
    LaunchedEffect(scene) {
        launch { MapLayers.capitalsVisible.collect(scene::setCapitalsVisible) }
        launch { MapLayers.gridVisible.collect(scene::setGridVisible) }
        launch { MapLayers.aircraftVisible.collect { scene.setLayerVisible(FeatureLayer.AIRCRAFT, it) } }
        launch { MapLayers.earthquakesVisible.collect { scene.setLayerVisible(FeatureLayer.EARTHQUAKES, it) } }
        launch { MapLayers.positionsVisible.collect { scene.setLayerVisible(FeatureLayer.POSITIONS, it) } }
        launch { MapLayers.webcamsVisible.collect { scene.setLayerVisible(FeatureLayer.WEBCAMS, it) } }
        launch { AircraftLayerStore.state.map { it.features }.distinctUntilChanged()
            .collect { scene.setFeatures(FeatureLayer.AIRCRAFT, it) } }
        launch { EarthquakeLayerStore.state.map { it.features }.distinctUntilChanged()
            .collect { scene.setFeatures(FeatureLayer.EARTHQUAKES, it) } }
        launch { PositionsLayerStore.state.map { it.features }.distinctUntilChanged()
            .collect { scene.setFeatures(FeatureLayer.POSITIONS, it) } }
        launch { WebcamLayerStore.state.map { it.features }.distinctUntilChanged()
            .collect { scene.setFeatures(FeatureLayer.WEBCAMS, it) } }
        launch { FeatureSelection.selected.collect { scene.selectFeature(it?.id) } }
        launch { GlobeSelection.focusRequest.filterNotNull().collect {
            if (explorer.interaction == ExplorerInteraction.EARTH) {
                scene.aimAt(it.point.latitude, it.point.longitude, reticle = it.blink)
            } else scene.lookAt(it.point.latitude, it.point.longitude)
            GlobeSelection.consumeFocus(it.sequence)
        } }
        launch { FeatureSelection.selected.collect { if (it == null) scene.clearTarget() } }
    }
    LaunchedEffect(scene, sunVisible) {
        scene.setSunVisible(sunVisible)
        while (sunVisible) {
            scene.setSun(SolarPosition.at(System.currentTimeMillis()))
            delay(30_000)
        }
    }

    SpatialView(
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(explorer) {
                    detectCapitalHover { hit ->
                        scene.hover(hit.takeIf { explorer.interaction == ExplorerInteraction.EARTH })
                    }
                }
                .pointerInput(explorer) {
                    detectSpatialPointerEvent(context, TargetEntity.any { isGlobeTarget(it.getName()) }) { events ->
                        if (explorer.interaction == ExplorerInteraction.EARTH) {
                            events.firstOrNull()?.let(scene::observePointer)
                        }
                        false
                    }
                }
                .pointerInput(explorer) {
                    detectSpatialTapGesture(
                        context,
                        TargetEntity.any { it.getName() == CITY_CLOSE_NAME || it.getName() == PANELS_TOGGLE_NAME ||
                            (explorer.interaction == ExplorerInteraction.EARTH && isGlobeTarget(it.getName())) },
                    ) { tap ->
                        val targetName = tap.targetEntity?.getName()
                        if (targetName == PANELS_TOGGLE_NAME) PanelVisibility.toggle()
                        else if (targetName == CITY_CLOSE_NAME) LocationStore.showGlobe()
                        else if (explorer.interaction == ExplorerInteraction.EARTH) {
                            val (feature, coordinate) = scene.pickAt(tap.position, tap.interactionKind, targetName)
                            if (feature != null) FeatureSelection.select(feature)
                            else if (coordinate != null) GlobeSelection.tap(coordinate.latitude, coordinate.longitude, targetName)
                        }
                    }
                }
                .pointerInput(explorer) {
                    var dragMode = ExplorerInteraction.LOCKED
                    detectSpatialDragGesture(
                        context,
                        onDragStart = { _, value ->
                            // A pinch held on the assistant orb must never pan the city or spin the globe.
                            val assistantBusy = AssistantDemo.presentation.value.state.let {
                                it == AssistantState.LISTENING || it == AssistantState.THINKING
                            }
                            dragMode = explorer.interaction.takeIf {
                                !assistantBusy && value.targetEntity?.getName() !in setOf(CITY_CLOSE_NAME, CITY_STATUS_NAME, PANELS_TOGGLE_NAME)
                            } ?: ExplorerInteraction.LOCKED
                            if (dragMode == ExplorerInteraction.CITY) {
                                LocationStore.beginPanGesture(value.interactionKind.toString())
                            }
                        },
                        onDragEnd = {
                            if (dragMode == ExplorerInteraction.CITY) LocationStore.endPanGesture()
                            dragMode = ExplorerInteraction.LOCKED
                        },
                        onDragCancel = {
                            if (dragMode == ExplorerInteraction.CITY) LocationStore.endPanGesture()
                            dragMode = ExplorerInteraction.LOCKED
                        },
                    ) { drag ->
                        if (dragMode == explorer.interaction) when (dragMode) {
                            ExplorerInteraction.EARTH -> scene.spin(drag.dragAmount.x, drag.dragAmount.y)
                            ExplorerInteraction.CITY -> LocationStore.panCityFromGesture(
                                drag.dragAmount.x, drag.dragAmount.y, drag.dragAmount.z)
                            ExplorerInteraction.LOCKED -> Unit
                        }
                    }
                },
    ) { content, _ ->
        explorer.build(content)
    }
}

internal const val PANELS_TOGGLE_NAME = "PanelsToggle"

data class GlobeCoordinate(val latitude: Double, val longitude: Double)

private fun isGlobeTarget(name: String?) = name == GLOBE_NAME || name?.startsWith("capital:") == true ||
    name?.startsWith("feature:") == true || name?.startsWith("layer:") == true

internal class GlobeScene {
    private val root = Entity()
    private val capitalsRoot = Entity()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var graticule: ModelEntity? = null
    private var reticle: Entity? = null
    private var reticleTransform: TransformComponent? = null
    private var reticlePulse: TransformComponent? = null
    private var reticlePulseMaterial: UnlitMaterial? = null
    private var targetingSince = 0L
    private val hologram = Entity().apply { enabled = false }
    private var hologramMaterial: UnlitMaterial? = null
    private var hologramRegistered = false
    private var earthMaterial: UnlitMaterial? = null
    private var nightLights: ModelEntity? = null
    private var lastInteraction = 0L
    private var lastTick = 0L
    private var idleAllowed = false
    private var sunJob: kotlinx.coroutines.Job? = null
    private var lastHologramTick = 0L
    private var content: SpatialViewContent? = null
    private var radius = 0.3f
    private var orientation = Quat(0f, 0f, 0f, 1f)
    private var viewLatitude = GlobeSelection.focus.value.latitude.toFloat()
    private var viewLongitude = GlobeSelection.focus.value.longitude.toFloat()
    private val sharedResources = mutableListOf<Resource>()
    private var built = false
    private lateinit var marker: ModelEntity
    private var layers: GlobeLayerScene? = null
    private var solar: SolarOverlay? = null
    private var capitalLabel: CapitalHoverLabel? = null
    private val capitalEntities = mutableMapOf<Long, com.example.earthexplorer.data.CapitalCity>()
    private val latestFeatures = mutableMapOf<FeatureLayer, List<GlobeFeature>>()
    private var latestSun: SolarSnapshot? = null
    private data class PointerOrigin(val point: DVec3, val kind: InteractionKind, val uptime: Long)
    private var pointerOrigin: PointerOrigin? = null

    suspend fun build(content: SpatialViewContent, radius: Float, earth: Bitmap, night: Bitmap?) {
        if (built) return
        built = true
        this.content = content
        this.radius = radius
        lastInteraction = SystemClock.elapsedRealtime()

        class Prepared(
            val earthMaterial: UnlitMaterial, val globeMesh: MeshResource, val globeShape: ShapeResource,
            val globePhysics: PhysicsMaterialResource, val halos: List<Pair<MeshResource, UnlitMaterial>>,
            val gridMesh: MeshResource, val gridMaterial: UnlitMaterial, val ringMaterial: UnlitMaterial,
            val pulseMaterial: UnlitMaterial, val ringMesh: MeshResource, val dotMesh: MeshResource,
            val markerMaterial: UnlitMaterial, val markerMesh: MeshResource,
            val nightMesh: MeshResource?, val nightMaterial: UnlitMaterial?,
            val hologramRings: List<MeshResource>, val hologramDisc: MeshResource, val hologramMaterial: UnlitMaterial,
            val hologramDiscMaterial: UnlitMaterial,
        )
        // Native resource creation can wait for the rendering service. Keep it off the UI thread.
        val prepared = withContext(Dispatchers.IO) {
            fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); sharedResources += it }
            val earthTexture = keep(TextureResource.create(earth))
            val earthMaterial = keep(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
                setBaseColorTexture(earthTexture)
                setCullingMode(MaterialCullingMode.NONE)
            }
            // Command-deck atmosphere: back-face shells read as a soft cyan halo around the limb.
            val halos = listOf(1.035f to 0.22f, 1.075f to 0.12f, 1.13f to 0.05f).map { (scale, alpha) ->
                keep(MeshResource.createSphere(radius * scale)) to keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, alpha))
                    setCullingMode(MaterialCullingMode.FRONT)
                    setDepthWrite(false)
                }
            }
            Prepared(
                earthMaterial = earthMaterial,
                globeMesh = keep(texturedSphere(radius)),
                globeShape = keep(ShapeResource.createSphere(radius)),
                globePhysics = keep(PhysicsMaterialResource()),
                halos = halos,
                gridMesh = keep(graticuleMesh(radius * 1.004f, radius * 0.0011f)),
                gridMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, 0.30f))
                    setCullingMode(MaterialCullingMode.NONE)
                },
                ringMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, 0.95f))
                    setCullingMode(MaterialCullingMode.NONE)
                },
                pulseMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, 0.6f))
                    setCullingMode(MaterialCullingMode.NONE)
                },
                ringMesh = keep(ringMesh(radius * 0.05f, radius * 0.0035f, "reticle-ring")),
                dotMesh = keep(MeshResource.createSphere(radius * 0.009f)),
                markerMaterial = keep(UnlitMaterial.create(BlendingMode.OPAQUE)).apply {
                    setBaseColor(Color4(1f, 0.55f, 0.05f, 1f))
                },
                markerMesh = keep(MeshResource.createSphere(radius * 0.022f)),
                nightMesh = night?.let { keep(texturedSphere(radius * 1.006f, 96, 48)) },
                nightMaterial = night?.let { bitmap ->
                    val lights = keep(TextureResource.create(bitmap))
                    keep(UnlitMaterial.create(BlendingMode.ADD)).apply {
                        setBaseColorTexture(lights)
                        setBaseColor(Color4(1.9f, 1.6f, 1.0f, 1f))
                        setCullingMode(MaterialCullingMode.BACK)
                        setDepthWrite(false)
                    }
                },
                hologramRings = listOf(0.62f, 0.9f, 1.18f).map { keep(ringMesh(radius * it, radius * 0.0022f, "hologram-ring")) },
                hologramDisc = keep(discMesh(radius * 1.24f)),
                hologramMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, 0.22f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                },
                hologramDiscMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                    setBaseColor(linearColor(0x4FE3FF, 0.05f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
                },
            )
        }
        earthMaterial = prepared.earthMaterial
        // Boot: the globe starts as a cyan wireframe and develops into the photo texture.
        prepared.earthMaterial.setPolygonFillMode(PolygonFillMode.LINE)
        prepared.earthMaterial.setBaseColor(Color4(0.45f, 0.95f, 1f, 1f))
        val globe = ModelEntity(prepared.globeMesh, prepared.earthMaterial).apply {
            setName(GLOBE_NAME)
            components.set(CollisionComponent(listOf(prepared.globeShape), prepared.globePhysics))
            components.set(InteractableComponent())
        }
        prepared.halos.forEach { (haloMesh, haloMaterial) ->
            root.addChild(ModelEntity(haloMesh, haloMaterial).apply { setName("atmosphere") })
        }
        graticule = ModelEntity(prepared.gridMesh, prepared.gridMaterial).apply {
            setName("graticule")
            enabled = MapLayers.gridVisible.value
            root.addChild(this)
        }
        if (prepared.nightMesh != null && prepared.nightMaterial != null) {
            nightLights = ModelEntity(prepared.nightMesh, prepared.nightMaterial).apply {
                setName("night-lights")
                enabled = MapLayers.sunVisible.value
                root.addChild(this)
            }
        }
        // A restrained hologram emitter under the globe: three faint rings and a barely-there disc.
        hologramMaterial = prepared.hologramMaterial
        hologram.components[TransformComponent::class.java]?.setPosition(Vector3(0f, -radius / 0.33f / 2f + 0.006f, 0f))
        hologram.addChild(ModelEntity(prepared.hologramDisc, prepared.hologramDiscMaterial))
        prepared.hologramRings.forEach { ring -> hologram.addChild(ModelEntity(ring, prepared.hologramMaterial)) }
        reticlePulseMaterial = prepared.pulseMaterial
        reticle = Entity().apply {
            setName("reticle")
            enabled = false
            addChild(ModelEntity(prepared.ringMesh, prepared.ringMaterial))
            addChild(ModelEntity(prepared.dotMesh, prepared.ringMaterial))
            addChild(ModelEntity(prepared.ringMesh, prepared.pulseMaterial).also { pulse ->
                reticlePulse = pulse.components[TransformComponent::class.java]
            })
            root.addChild(this)
        }
        reticleTransform = reticle?.components?.get(TransformComponent::class.java)
        marker = ModelEntity(prepared.markerMesh, prepared.markerMaterial)
        marker.components[TransformComponent::class.java]
            ?.setScaleVector(Vector3(0f))

        root.addChild(globe)
        root.addChild(marker)
        root.addChild(capitalsRoot)
        capitalLabel = CapitalHoverLabel(root, radius)
        setCapitalsVisible(MapLayers.capitalsVisible.value)
        applyOrientation()
        // Attach the small Earth first; attaching the complete 200-pin tree is one long native call.
        content.addEntity(root)
        if (!hologramRegistered) { content.addEntity(hologram); hologramRegistered = true }
        hologram.enabled = true
        delay(16)
        addCapitals()
        delay(16)
        layers = GlobeLayerScene(root, radius).also { overlay ->
            latestFeatures.forEach { (kind, features) -> overlay.setFeatures(kind, features) }
            overlay.select(FeatureSelection.selected.value?.id)
        }
        delay(16)
        solar = SolarOverlay(root, radius).also {
            latestSun?.let(it::update)
            it.setVisible(MapLayers.sunVisible.value)
        }
        setCapitalsVisible(MapLayers.capitalsVisible.value)
        applyOrientation()
        scope.launch {
            while (isActive) {
                tick(SystemClock.elapsedRealtime())
                delay(33)
            }
        }
    }

    fun setGridVisible(visible: Boolean) { graticule?.enabled = visible }

    /** Idle rotation runs only on the Earth view, after five seconds without input or a selection. */
    fun setIdleAllowed(allowed: Boolean) { idleAllowed = allowed; if (allowed) lastInteraction = SystemClock.elapsedRealtime() }

    /** The boot sequence: wireframe cyan globe develops into the textured Earth over about two seconds. */
    suspend fun bootReveal() {
        val material = earthMaterial ?: return
        val started = SystemClock.elapsedRealtime()
        var filled = false
        while (true) {
            val amount = ((SystemClock.elapsedRealtime() - started) / BOOT_MILLIS.toFloat()).coerceIn(0f, 1f)
            val eased = amount * amount * (3f - 2f * amount)
            if (!filled && amount > 0.45f) { filled = true; material.setPolygonFillMode(PolygonFillMode.FILL) }
            material.setBaseColor(Color4(0.45f + 0.55f * eased, 0.95f + 0.05f * eased, 1f, 1f))
            if (amount >= 1f) break
            delay(16)
        }
    }

    /** Turn the place toward the viewer along the shortest path, optionally with the lock-on reticle. */
    suspend fun aimAt(latitude: Double, longitude: Double, reticle: Boolean = true) {
        val direction = FeatureGeometry.direction(latitude, longitude)
        val up = Vector3(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        if (reticle) {
            reticleTransform?.apply {
                setPosition(Vector3(up.x * radius * 1.02f, up.y * radius * 1.02f, up.z * radius * 1.02f))
                setQuaternion(quaternionFromUp(up))
            }
            targetingSince = SystemClock.elapsedRealtime()
            this.reticle?.enabled = true
        }
        capitalLabel?.clear()
        val startLatitude = viewLatitude
        val startLongitude = viewLongitude
        val endLatitude = latitude.toFloat().coerceIn(-85f, 85f)
        var deltaLongitude = (longitude.toFloat() - startLongitude) % 360f
        if (deltaLongitude > 180f) deltaLongitude -= 360f
        if (deltaLongitude < -180f) deltaLongitude += 360f
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val amount = ((SystemClock.elapsedRealtime() - started) / AIM_MILLIS.toFloat()).coerceIn(0f, 1f)
            val eased = amount * amount * (3f - 2f * amount)
            viewLatitude = startLatitude + (endLatitude - startLatitude) * eased
            viewLongitude = startLongitude + deltaLongitude * eased
            applyOrientation()
            if (amount >= 1f) break
            delay(16)
        }
        viewLatitude = endLatitude
        viewLongitude = longitude.toFloat()
        applyOrientation()
    }

    fun clearTarget() { reticle?.enabled = false }

    private fun tick(now: Long) {
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 100L)
        lastTick = now
        if (idleAllowed && reticle?.enabled != true && FeatureSelection.selected.value == null &&
            now - lastInteraction > IDLE_AFTER_MILLIS && dt > 0) {
            viewLongitude += IDLE_DEGREES_PER_SECOND * dt / 1000f
            applyOrientation()
        }
        if (now - lastHologramTick >= 200) {
            lastHologramTick = now
            hologramMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.16f + 0.08f * (0.5f + 0.5f * sin(now / 900f))))
        }
        if (reticle?.enabled == true) {
            val phase = ((now - targetingSince) % 1500L) / 1500f
            val scale = 1f + 2.4f * phase
            reticlePulse?.setScaleVector(Vector3(scale, 1f, scale))
            reticlePulseMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.8f * (1f - phase) * (1f - phase)))
        }
        layers?.tick(now)
    }

    private suspend fun addCapitals() {
        // Identical pins share both render and collision resources; native creation can block input.
        val pinMesh = MeshResource.createSphere(radius * 0.012f).also { it.toGlobal() }
        val pinShape = ShapeResource.createSphere(radius * 0.032f).also { it.toGlobal() }
        val pinPhysics = PhysicsMaterialResource().also { it.toGlobal() }
        val pinMaterial = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            toGlobal()
            setBaseColor(linearColor(0xFFC857))
        }
        sharedResources += pinMesh
        sharedResources += pinMaterial
        sharedResources += pinShape
        sharedResources += pinPhysics
        CapitalCities.all.forEachIndexed { index, capital ->
            capitalsRoot.addChild(ModelEntity(pinMesh, pinMaterial).apply {
                setName("capital:${capital.id}")
                components[TransformComponent::class.java]?.setPosition(
                    surfacePoint(capital.latitude, capital.longitude, radius * 1.012f))
                components.set(CollisionComponent(
                    listOf(pinShape), pinPhysics))
                components.set(InteractableComponent())
                capitalEntities[getUUID()] = capital
            })
            // A timed suspension also lets Android focus/input callbacks run on cold startup.
            if (index % 8 == 7) delay(8)
        }
    }

    fun setCapitalsVisible(visible: Boolean) {
        capitalsRoot.enabled = visible
        if (!visible) capitalLabel?.clear()
    }

    fun setVisible(visible: Boolean) {
        root.enabled = visible
        hologram.enabled = visible
        if (!visible) capitalLabel?.clear()
    }

    fun hover(hit: CapitalHoverHit?) {
        if (hit == null || !MapLayers.capitalsVisible.value) { capitalLabel?.clear(); return }
        val direct = capitalEntities[hit.entityId]
        if (direct != null) { capitalLabel?.show(direct); return }
        val origin = hit.origin.takeUnless { it.x == 0f && it.y == 0f && it.z == 0f }
            ?.let { localPoint(it, ViewCoordinateSpace.Global) }
        val point = localPoint(hit.position, ViewCoordinateSpace.Local)
        val ray = if (origin != null && point != null) FeatureGeometry.rayThrough(origin, point) else null
        capitalLabel?.show(ray?.let { hoveredCapital(it, radius.toDouble()) })
    }

    fun setFeatures(layer: FeatureLayer, features: List<GlobeFeature>) {
        latestFeatures[layer] = features
        layers?.setFeatures(layer, features)
    }

    fun setLayerVisible(layer: FeatureLayer, visible: Boolean) { layers?.setVisible(layer, visible) }
    fun selectFeature(id: String?) { layers?.select(id) }
    fun setSunVisible(visible: Boolean) {
        sunJob?.cancel()
        if (!visible) { solar?.setVisible(false); nightLights?.enabled = false; return }
        // A second to look at the plain globe before the Sun, night side and sweep arrive.
        layers?.startSweep(FeatureLayer.SUN)
        sunJob = scope.launch {
            delay(1_000)
            if (MapLayers.sunVisible.value) { solar?.setVisible(true); nightLights?.enabled = true }
        }
    }
    fun setSun(snapshot: SolarSnapshot) { latestSun = snapshot; solar?.update(snapshot) }

    fun observePointer(event: SpatialPointerInfo) {
        val raw = event.inputDevicePose.rawPosition
        if (raw.x == 0f && raw.y == 0f && raw.z == 0f) { pointerOrigin = null; return }
        localPoint(raw, ViewCoordinateSpace.Global)?.let {
            pointerOrigin = PointerOrigin(it, event.kind, event.uptimeMillis)
        }
    }

    fun pickAt(tap: Offset3D, kind: InteractionKind, entityName: String?): Pair<GlobeFeature?, GlobeCoordinate?> {
        val point = localPoint(tap, ViewCoordinateSpace.Local) ?: return null to null
        val ray: FeaturePickRay? = if (kind == InteractionKind.Poke || kind == InteractionKind.DirectPinch) {
            // A physical finger touching the spherical interaction shell addresses its radial GPS.
            FeatureGeometry.rayThrough(point * 1.3, DVec3(0.0, 0.0, 0.0))
        } else pointerOrigin?.takeIf {
            it.kind == kind && SystemClock.uptimeMillis() - it.uptime in 0..750
        }?.let { FeatureGeometry.rayThrough(it.point, point) }
        val feature = if (entityName?.startsWith("capital:") == true) null else layers?.pick(
            ray, point, entityName, listOfNotNull(solar?.feature?.takeIf { MapLayers.sunVisible.value }))
        val ground = when {
            ray != null -> FeatureGeometry.pointOnEarth(ray, radius.toDouble())
            entityName == GLOBE_NAME || entityName?.startsWith("capital:") == true -> point
            else -> null // Never turn an unprojected outer-shell hit into unrelated ground GPS.
        }
        val coordinate = ground?.let { coordinateFromVector(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
        return feature to coordinate
    }

    fun lookAt(latitude: Double, longitude: Double) {
        viewLatitude = latitude.toFloat().coerceIn(-85f, 85f)
        viewLongitude = longitude.toFloat()
        applyOrientation()
    }

    fun coordinateAt(tap: Offset3D): GlobeCoordinate? {
        val local = localPoint(tap, ViewCoordinateSpace.Local) ?: return null
        return coordinateFromVector(local.x.toFloat(), local.y.toFloat(), local.z.toFloat())
    }

    private fun localPoint(point: Offset3D, space: ViewCoordinateSpace): DVec3? {
        val spatialContent = content ?: return null
        val converted = spatialContent.convertPosition(
            Vector3(point.x, point.y, point.z),
            space,
            spatialContent.localSpatialCoordinateSpace,
        )
        val local = rotate(conjugate(orientation), converted.x, converted.y, converted.z)
        return DVec3(local[0].toDouble(), local[1].toDouble(), local[2].toDouble())
    }

    fun select(coordinate: GlobeCoordinate) {
        marker.components[TransformComponent::class.java]?.apply {
            setPosition(
                surfacePoint(
                    coordinate.latitude,
                    coordinate.longitude,
                    radius * 1.025f,
                )
            )
            setScaleVector(Vector3(1f))
        }
    }

    fun spin(dxPx: Float, dyPx: Float) {
        capitalLabel?.clear()
        lastInteraction = SystemClock.elapsedRealtime()
        viewLongitude -= dxPx * 0.12f
        viewLatitude = (viewLatitude + dyPx * 0.12f).coerceIn(-85f, 85f)
        applyOrientation()
    }

    private fun applyOrientation() {
        orientation = multiply(
            axisAngle(1f, 0f, 0f, viewLatitude),
            axisAngle(0f, 1f, 0f, -viewLongitude),
        )
        root.components[TransformComponent::class.java]
            ?.setQuaternion(orientation)
        layers?.setOrientation(orientation)
        capitalLabel?.setOrientation(orientation)
        solar?.setOrientation(orientation)
        GlobeSelection.face(viewLatitude.toDouble(), viewLongitude.toDouble())
    }

    fun destroy() {
        scope.cancel()
        layers?.destroy()
        solar?.destroy()
        capitalLabel?.clear()
        hologram.destroy()
        root.destroy()
        sharedResources.forEach { it.close() }
    }
}

private const val AIM_MILLIS = 1_000L
private const val BOOT_MILLIS = 2_200L
private const val IDLE_AFTER_MILLIS = 5_000L
private const val IDLE_DEGREES_PER_SECOND = 1.4f

internal fun coordinateFromVector(
    x: Float,
    y: Float,
    z: Float,
): GlobeCoordinate? {
    val length = sqrt(x * x + y * y + z * z)
    if (length < 1e-6f) return null
    val latitude =
        asin((y / length).coerceIn(-1f, 1f).toDouble()) * 180.0 / PI
    val longitude = atan2(x.toDouble(), z.toDouble()) * 180.0 / PI
    return GlobeCoordinate(latitude, longitude)
}

private fun unit(latitude: Double, longitude: Double): DoubleArray {
    val latitudeRadians = latitude * PI / 180.0
    val longitudeRadians = longitude * PI / 180.0
    return doubleArrayOf(
        cos(latitudeRadians) * sin(longitudeRadians),
        sin(latitudeRadians),
        cos(latitudeRadians) * cos(longitudeRadians),
    )
}

private fun surfacePoint(
    latitude: Double,
    longitude: Double,
    radius: Float,
): Vector3 {
    val direction = unit(latitude, longitude)
    return Vector3(
        (direction[0] * radius).toFloat(),
        (direction[1] * radius).toFloat(),
        (direction[2] * radius).toFloat(),
    )
}

private fun texturedSphere(
    radius: Float,
    longitudeSegments: Int = 128,
    latitudeSegments: Int = 64,
): MeshResource {
    val positions = ArrayList<Vector3>(
        (longitudeSegments + 1) * (latitudeSegments + 1)
    )
    val normals = ArrayList<Vector3>(positions.size)
    val tangents = ArrayList<Vector4>(positions.size)
    val textureCoordinates = ArrayList<Vector2>(positions.size)
    for (latitudeIndex in 0..latitudeSegments) {
        val v = latitudeIndex.toFloat() / latitudeSegments
        val latitude = 90.0 - 180.0 * v
        for (longitudeIndex in 0..longitudeSegments) {
            val u = longitudeIndex.toFloat() / longitudeSegments
            val longitude = -180.0 + 360.0 * u
            val normal = unit(latitude, longitude)
            val longitudeRadians = longitude * PI / 180.0
            normals += Vector3(
                normal[0].toFloat(),
                normal[1].toFloat(),
                normal[2].toFloat(),
            )
            positions += Vector3(
                (normal[0] * radius).toFloat(),
                (normal[1] * radius).toFloat(),
                (normal[2] * radius).toFloat(),
            )
            tangents += Vector4(
                cos(longitudeRadians).toFloat(),
                0f,
                (-sin(longitudeRadians)).toFloat(),
                1f,
            )
            textureCoordinates += Vector2(u, 1f - v)
        }
    }

    val indices = ArrayList<Int>(
        longitudeSegments * latitudeSegments * 6
    )
    val stride = longitudeSegments + 1
    for (latitudeIndex in 0 until latitudeSegments) {
        for (longitudeIndex in 0 until longitudeSegments) {
            val northWest = latitudeIndex * stride + longitudeIndex
            val southWest = northWest + stride
            val northEast = northWest + 1
            val southEast = southWest + 1
            indices += listOf(
                northWest,
                southWest,
                northEast,
                northEast,
                southWest,
                southEast,
            )
        }
    }

    return MeshResource.createWithMeshModel(
        MeshModel(positions, indices, normals, tangents, textureCoordinates),
        BoundingBox(Vector3(-radius), Vector3(radius)),
        "earth",
    )
}

private fun axisAngle(x: Float, y: Float, z: Float, degrees: Float): Quat {
    val halfAngle = degrees * PI.toFloat() / 360f
    val scale = sin(halfAngle)
    return Quat(x * scale, y * scale, z * scale, cos(halfAngle))
}

private fun multiply(left: Quat, right: Quat): Quat = Quat(
    left.w * right.x + left.x * right.w + left.y * right.z - left.z * right.y,
    left.w * right.y - left.x * right.z + left.y * right.w + left.z * right.x,
    left.w * right.z + left.x * right.y - left.y * right.x + left.z * right.w,
    left.w * right.w - left.x * right.x - left.y * right.y - left.z * right.z,
)

private fun conjugate(quaternion: Quat): Quat = Quat(
    -quaternion.x,
    -quaternion.y,
    -quaternion.z,
    quaternion.w,
)

private fun rotate(
    quaternion: Quat,
    x: Float,
    y: Float,
    z: Float,
): FloatArray {
    val crossX = quaternion.y * z - quaternion.z * y
    val crossY = quaternion.z * x - quaternion.x * z
    val crossZ = quaternion.x * y - quaternion.y * x
    return floatArrayOf(
        x + 2f * (
            quaternion.w * crossX + quaternion.y * crossZ -
                quaternion.z * crossY
        ),
        y + 2f * (
            quaternion.w * crossY + quaternion.z * crossX -
                quaternion.x * crossZ
        ),
        z + 2f * (
            quaternion.w * crossZ + quaternion.x * crossY -
                quaternion.y * crossX
        ),
    )
}
