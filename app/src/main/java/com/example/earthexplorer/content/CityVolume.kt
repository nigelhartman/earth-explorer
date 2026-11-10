package com.example.earthexplorer.content

import android.graphics.*
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.earthexplorer.data.*
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.ecs.simulation.CollisionCastHitMode
import com.pico.spatial.core.ecs.simulation.CollisionFilter
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import kotlin.math.*

private const val CITY_NAME = "CesiumCity"
internal const val CITY_STATUS_NAME = "CityStatus"
internal const val CITY_CLOSE_NAME = "CityClose"

internal data class CityLabelText(val city: String, val country: String?)

internal fun cityLabelText(city: String?, country: String?): CityLabelText = CityLabelText(
    city = city?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown",
    country = country?.trim()?.takeIf(String::isNotEmpty),
)

@Composable
fun NativeCityVolume(location: SelectedLocation) {
    val volumeSide = LocalPhysicalLengthConverter.current.dpToLength(1100.dp, LengthUnit.Meters)
    key(location.latitude, location.longitude) {
        // The native scene is stable when reverse geocoding updates the place name.
        val scene = remember(volumeSide) { CityScene(GeoFrame(location.latitude, location.longitude), volumeSide) }
        DisposableEffect(scene) { onDispose(scene::destroy) }
        SpatialView(modifier = Modifier.fillMaxSize()
            .explorerDragInput(scene),
        ) { content, _ -> scene.build(content) }
    }
}

private data class NativePart(val mesh: MeshResource, val material: UnlitMaterial, val texture: TextureResource,
    val positions: List<Vector3>) {
    fun close() { mesh.close(); material.close(); texture.close() }
}
private class NativeTile(val id: String, val entity: Entity, val parts: List<NativePart>, val copyright: String,
    val triangles: Int, val bounds: NativeTileBounds, val minimumHeight: Double, val estimatedBytes: Long,
    /** Lowest mesh height per cell of an 8×8 grid over the tile bounds (mesh units), for the underside beams. */
    val heightGrid: FloatArray,
    var lastVisible: Long, var displayed: Boolean,
    /** Cyan wireframe copies of the parts while the tile rises; released once it has developed. */
    var wireMaterial: UnlitMaterial? = null, var wireEntities: List<Entity> = emptyList(),
    var revealStarted: Long = -1L, var revealed: Boolean = false) {
    fun visible(center: CityCenter) = bounds.visible(center)
    fun close() { entity.destroy(); parts.forEach { it.close() }; wireMaterial?.close(); wireMaterial = null }
}

internal class CityScene(private val frame: GeoFrame, private val volumeSide: Float,
    private val onReady: () -> Unit = {},
    /** Tiles rise out of the floor grid as wireframe and develop their photo texture. */
    private val revealTiles: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val service = CesiumTiles(frame)
    private val table = Entity()
    private val cityRoot = Entity()
    private val overlay = Entity()
    private val tiles = LinkedHashMap<String, NativeTile>()
    private val nativePolicy = NativeTileCachePolicy(nativeTileCacheBudget(Runtime.getRuntime().maxMemory()))
    private val preparing = mutableSetOf<String>()
    private val neighborhoodRequests = mutableMapOf<MeshRegion, Set<String>>()
    private val fixedResources = mutableListOf<Resource>()
    private var labelEntity: Entity? = null
    private var labelResources = emptyList<Resource>()
    private var groundHeight: Double? = null
    private var targetIds = emptySet<String>()
    private var coarseTiles = 0
    private var loadError: String? = null
    private var selecting = true
    private var streamingVisible = true
    private var lastStreamCenter: CityCenter? = null
    private var active = true
    private var built = false
    private var paused = false
    private var readyNotified = false
    private var requestedVisible = true
    private var requestedOverlayVisible = true
    private var nativeWarmupCenter: CityCenter? = null
    private val pendingReveals = ArrayDeque<String>()
    private var revealsAllowed = false
    private var lastRevealStart = 0L

    private var nativeHaloIds = emptySet<String>()
    private var cameraMarker: Entity? = null
    private var cameraMarkerResources = emptyList<Resource>()
    private var cameraMarkerXZ: Pair<Float, Float>? = null
    private var epicenter: Entity? = null
    private var epicenterResources = emptyList<Resource>()
    private var epicenterXZ: Pair<Float, Float>? = null
    private var epicenterPulse: TransformComponent? = null
    private var epicenterPulseMaterial: UnlitMaterial? = null
    private class Shockwave(val transform: TransformComponent, val material: UnlitMaterial)
    private var shockwaves = emptyList<Shockwave>()
    private var shockwaveSince = 0L
    private var epicenterReveal: Job? = null
    private var beams: UndersideParticles? = null
    private var hudMaterial: UnlitMaterial? = null
    private val tileVersion = MutableStateFlow(0)

    fun setVisible(visible: Boolean) {
        requestedVisible = visible
        table.enabled = visible
    }

    fun setOverlayVisible(visible: Boolean) {
        requestedOverlayVisible = visible
        overlay.enabled = visible
    }

    fun reportUnavailableCoverage() {
        if (!active) return
        selecting = false
        loadError = loadError ?: "Photorealistic city tiles are unavailable here"
        progress()
    }

    /** Freeze attachment/eviction while another animation owns the volume. */
    fun setPaused(paused: Boolean) { this.paused = paused }

    private suspend fun awaitResumed() {
        while (paused && active) delay(16)
        currentCoroutineContext().ensureActive()
    }

    /** Queued tiles may start rising; called once the Earth plates have settled into the floor. */
    fun allowReveals() { revealsAllowed = true }

    /** Suspends until the first tile has started rising out of the grid. */
    suspend fun awaitFirstTile() {
        while (active && tiles.values.none { it.revealStarted >= 0 || it.revealed }) delay(50)
    }

    /** Suspends until the first view has streamed and every queued tile has finished developing. */
    suspend fun awaitRevealsSettled() {
        while (active && (selecting || streamingVisible || pendingReveals.isNotEmpty() ||
                tiles.values.any { it.revealStarted >= 0 && !it.revealed })) delay(50)
    }

    /** Fallback: present every tile immediately, without the rise animation. */
    fun showAllTiles() {
        revealsAllowed = true
        pendingReveals.clear()
        tiles.values.forEach { finishReveal(it) }
    }

    private fun tickReveals(now: Long) {
        if (!revealsAllowed) return
        while (pendingReveals.isNotEmpty() && now - lastRevealStart >= REVEAL_STAGGER_MILLIS) {
            val tile = tiles[pendingReveals.removeFirst()] ?: continue
            tile.revealStarted = now
            lastRevealStart = now
        }
        tiles.values.forEach { tile ->
            if (tile.revealStarted < 0 || tile.revealed) return@forEach
            val k = (now - tile.revealStarted) / REVEAL_MILLIS.toFloat()
            if (k >= 1f) { finishReveal(tile); return@forEach }
            val rise = smooth((k / 0.42f).coerceIn(0f, 1f))
            tile.entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 0.01f + 0.99f * rise, 1f))
            val wire = if (k < 0.62f) (k / 0.18f).coerceIn(0f, 1f) else 1f - ((k - 0.62f) / 0.38f).coerceIn(0f, 1f)
            tile.wireMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.9f * wire))
            val develop = smooth(((k - 0.34f) / 0.55f).coerceIn(0f, 1f))
            val color = developColor(develop)
            tile.parts.forEach { it.material.setBaseColor(color) }
        }
    }

    private fun finishReveal(tile: NativeTile) {
        tile.revealed = true
        tile.entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f))
        tile.parts.forEach { it.material.setBaseColor(Color4(1f, 1f, 1f, 1f)) }
        tile.wireEntities.forEach { it.destroy() }
        tile.wireEntities = emptyList()
        tile.wireMaterial?.close()
        tile.wireMaterial = null
    }

    /** The city fades to cyan and sinks into the floor, tile by tile, before the Earth reforms. */
    suspend fun sinkTiles() {
        pendingReveals.clear()
        val order = tiles.values.filter { it.displayed }.also { list -> list.forEach { finishReveal(it) } }
        if (order.isEmpty()) return
        val started = android.os.SystemClock.elapsedRealtime()
        while (active) {
            val amount = ((android.os.SystemClock.elapsedRealtime() - started) / SINK_MILLIS.toFloat()).coerceIn(0f, 1f)
            order.forEachIndexed { index, tile ->
                val stagger = index.toFloat() / order.size * 0.4f
                val k = ((amount - stagger) / 0.6f).coerceIn(0f, 1f)
                val color = developColor(1f - smooth((k / 0.5f).coerceIn(0f, 1f)))
                tile.parts.forEach { it.material.setBaseColor(color) }
                val drop = smooth(((k - 0.3f) / 0.7f).coerceIn(0f, 1f))
                tile.entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 1f - 0.99f * drop, 1f))
            }
            if (amount >= 1f) break
            delay(16)
        }
    }

    /** Cyan-dark at 0, the untouched photo at 1. */
    private fun developColor(amount: Float) = Color4(0.16f + 0.84f * amount, 0.66f + 0.34f * amount, 0.86f + 0.14f * amount, 1f)
    private fun smooth(value: Float) = value * value * (3f - 2f * value)

    fun build(content: SpatialViewContent) {
        if (built || !active) return
        built = true
        table.components[TransformComponent::class.java]?.apply {
            // Local up is world up. Do not tilt the entire city toward the viewer.
            setPosition(Vector3(0f, CITY_TABLE_Y, 0f))
        }
        addPanTarget()
        addCompassHud()
        beams = try { UndersideParticles(overlay, volumeSide).also { it.setVisible(false) } } catch (error: Exception) {
            Log.w("CesiumCity", "Underside particles unavailable: ${error.javaClass.simpleName}"); null
        }
        table.addChild(cityRoot)
        content.addEntity(table)
        content.addEntity(overlay)
        table.enabled = requestedVisible
        overlay.enabled = requestedOverlayVisible
        if (com.example.earthexplorer.BuildConfig.DEBUG) scope.launch {
            delay(1000)
            // Exercise native hit testing as well as the pure Kotlin geometry checks.
            val scene = overlay.scene ?: return@launch
            for (x in listOf(-volumeSide * 0.3f, 0f, volumeSide * 0.3f)) {
                val hits = scene.rayCast(Vector3(x, 0f, volumeSide), Vector3(0f, 0f, -1f),
                    volumeSide * 2, CollisionCastHitMode.NEAREST, CollisionFilter.COLLISION_FILTER_ALL.mask, overlay)
                val hit = hits.results.firstOrNull()
                Log.i("ExplorerDrag", "Native hit test x=$x: ${hit?.entity?.getName() ?: "MISS"}; distance=${hit?.distance}")
            }
        }
        scope.launch {
            LocationStore.cityCenter.collect { center ->
                awaitResumed()
                // Only translation changes. PICO clips the moving 3D geometry at the volume boundary.
                positionCity(center)
                progress()
            }
        }
        scope.launch {
            LocationStore.viewLocation.map { it?.let { place -> place.city to place.country } }
                .distinctUntilChanged().collect { place -> updateLabel(place?.first, place?.second) }
        }
        scope.launch { WebcamSelection.camera.collect(::updateCameraMarker) }
        scope.launch {
            // A scenario epicenter stays hidden until the assistant releases it; catalog events show at once.
            combine(FeatureSelection.selected, AssistantDemo.epicenterRevealed) { selected, revealed ->
                selected?.takeIf { it.layer == FeatureLayer.EARTHQUAKES && (revealed || it.id != AssistantDemo.SCENARIO_ID) }
            }.distinctUntilChanged().collect { quake -> updateEpicenter(quake, animate = quake?.id == AssistantDemo.SCENARIO_ID) }
        }
        scope.launch {
            combine(LocationStore.cityCenter, tileVersion) { center, _ -> center }.conflate().collect { center ->
                delay(150)
                refreshBeams(center)
            }
        }
        scope.launch {
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                beams?.tick(now)
                // The compass HUD breathes between faint and full over about six seconds.
                hudMaterial?.setOpacity(0.55f + 0.45f * (0.5f + 0.5f * sin(now / 950f)))
                val phase = (now % 1500L) / 1500f
                epicenterPulse?.setScaleVector(Vector3(1f + 2.4f * phase, 1f, 1f + 2.4f * phase))
                epicenterPulseMaterial?.setBaseColor(linearColor(0x4FE3FF, 0.8f * (1f - phase) * (1f - phase)))
                // Shockwaves roll across the whole city floor from the epicenter, two rings 1.5 s apart.
                shockwaves.forEachIndexed { index, wave ->
                    val k = ((now - shockwaveSince + index * SHOCKWAVE_PERIOD / 2) % SHOCKWAVE_PERIOD) / SHOCKWAVE_PERIOD.toFloat()
                    val scale = 1f + 30f * k
                    wave.transform.setScaleVector(Vector3(scale, 1f, scale))
                    wave.material.setBaseColor(linearColor(0xFF7A45, 0.55f * (1f - k) * (1f - k)))
                }
                delay(50)
            }
        }
        if (revealTiles) scope.launch {
            while (isActive) {
                tickReveals(android.os.SystemClock.elapsedRealtime())
                delay(33)
            }
        }
        scope.launch {
            combine(LocationStore.cityCenter, LocationStore.retry) { center, retry -> center to retry }
                .conflate().collect {
                    delay(80)
                    stream(LocationStore.cityCenter.value)
                }
        }
        scope.launch {
            combine(LocationStore.cityCenter.map { MeshRegion.containing(it) }.distinctUntilChanged(),
                LocationStore.retry) { region, retry -> region to retry }
                .conflate().collect {
                    prefetchNeighborhood(MeshNeighborhood.around(LocationStore.cityCenter.value))
                }
        }
        scope.launch {
            combine(LocationStore.cityCenter, LocationStore.retry) { center, retry -> center to retry }
                .conflate().collect {
                    delay(220)
                    warmNativeHalo(LocationStore.cityCenter.value)
                }
        }
    }

    private suspend fun stream(center: CityCenter) {
        awaitResumed()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        streamingVisible = true
        selecting = true
        loadError = null
        progress()
        try {
            val requests = service.select(center)
            awaitResumed()
            currentCoroutineContext().ensureActive()
            val current = LocationStore.cityCenter.value
            if (hypot(current.east - center.east, current.north - center.north) > 55.0) return
            val missing = requests.filter { it.id !in tiles }
            val cacheHits = missing.count { service.isCached(it.id) }
            Log.i("CesiumCity", "View reuse: ${requests.size - missing.size} native; $cacheHits cached downloads; ${missing.size - cacheHits} new downloads")
            targetIds = requests.map { it.id }.toSet()
            nativeHaloIds = emptySet()
            prioritizeNeighborhood()
            coarseTiles = requests.count { it.geometricError > 4.5 }
            selecting = false
            trimNativeCache()
            progress()
            // Finish small batches during a continuous drag, then follow its newest center.
            // Cancelling every pointer update would otherwise starve all tile uploads.
            for (batch in requests.filter { it.id !in tiles }.chunked(3)) {
                awaitResumed()
                val latest = LocationStore.cityCenter.value
                if (hypot(latest.east-center.east, latest.north-center.north) > 55.0) break
                coroutineScope {
                    batch.map { request -> launch {
                        try {
                            val started = android.os.SystemClock.elapsedRealtime()
                            while (request.id in preparing) { delay(16); awaitResumed() }
                            if (request.id in tiles) return@launch
                            val tile = service.load(request)
                            val decodedAt = android.os.SystemClock.elapsedRealtime()
                            ensureActive()
                            awaitResumed()
                            val tileMinimum = tile.primitives.minOf { p -> p.vertices.minOf { it.y } }
                            groundHeight = min(groundHeight ?: tileMinimum, tileMinimum)
                            positionCity(LocationStore.cityCenter.value)
                            upload(tile)
                            Log.i("CesiumCity", "Visible tile prepared: decode/download=${decodedAt - started}ms; " +
                                "native=${android.os.SystemClock.elapsedRealtime() - decodedAt}ms")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            loadError = safeError(e)
                            Log.w("CesiumCity", "Tile failed: ${e.javaClass.simpleName}")
                        }
                        progress()
                    }}.joinAll()
                }
            }
            awaitResumed()
            trimNativeCache()
            Log.i("CesiumCity", "City view: ${tiles.size} textured tiles; center ${center.east.toInt()}m E / ${center.north.toInt()}m N; " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            progress()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            selecting = false
            loadError = safeError(e)
            Log.w("CesiumCity", "Tileset selection failed: ${e.javaClass.simpleName}")
            progress()
        } finally {
            lastStreamCenter = center
            streamingVisible = false
            if (active && !readyNotified) {
                readyNotified = true
                // A failed first view must also release navigation from its loading state.
                if (loadError == null && tiles.values.none { it.visible(LocationStore.cityCenter.value) }) {
                    reportUnavailableCoverage()
                }
                onReady()
            }
        }
    }

    private fun prioritizeNeighborhood() {
        val neighborhood = MeshNeighborhood.around(LocationStore.cityCenter.value)
        val priorities = targetIds.associateWith { 0 }.toMutableMap()
        nativeHaloIds.forEach { priorities.putIfAbsent(it, 1) }
        neighborhood.regions.forEachIndexed { index, region ->
            neighborhoodRequests[region]?.forEach { id ->
                priorities.putIfAbsent(id, index + 2)
            }
        }
        service.prioritizeNeighborhood(priorities)
    }

    private fun cacheEntries(): List<NativeTileCacheEntry> = tiles.map { (id, tile) ->
        NativeTileCacheEntry(id, tile.estimatedBytes, tile.bounds, tile.lastVisible)
    }

    private fun protectedNativeIds(center: CityCenter) = targetIds +
        tiles.filterValues { it.visible(center) }.keys

    private fun trimNativeCache() {
        val center = LocationStore.cityCenter.value
        nativePolicy.evictions(cacheEntries(), protectedNativeIds(center), center)
            .forEach { tiles.remove(it)?.close() }
    }

    private suspend fun warmNativeHalo(center: CityCenter) {
        fun current() = active && LocationStore.cityCenter.value == center
        suspend fun ready(): Boolean {
            while (current() && (paused || streamingVisible || lastStreamCenter != center)) delay(80)
            return current()
        }
        nativeWarmupCenter = center
        var prepared = 0
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            if (!ready()) return
            var requests: List<TileRequest>?
            do {
                if (!ready()) return
                requests = service.selectNativeHalo(center) { current() && !paused && !streamingVisible }
            } while (requests == null)
            nativeHaloIds = requests.map { it.id }.toSet()
            prioritizeNeighborhood()
            for (request in requests) {
                if (!ready()) return
                if (request.id in tiles || request.id in preparing) continue
                // One speculative upload at a time leaves foreground workers and the renderer free.
                val tile = service.load(request)
                if (!ready()) return
                if (upload(tile, speculative = true)) prepared++ else break
                delay(16)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w("CesiumCity", "Native halo deferred: ${e.javaClass.simpleName}")
        } finally {
            nativeWarmupCenter = null
            if (active) {
                Log.i("CesiumCity", "Native halo: $prepared prepared; ${tiles.size} retained tiles; " +
                    "${tiles.values.sumOf { it.estimatedBytes } / (1024 * 1024)}MiB / " +
                    "${nativePolicy.budgetBytes / (1024 * 1024)}MiB; ${android.os.SystemClock.elapsedRealtime() - started}ms")
            }
        }
    }

    private suspend fun prefetchNeighborhood(neighborhood: MeshNeighborhood) {
        fun current() = active && MeshRegion.containing(LocationStore.cityCenter.value) == neighborhood.central
        suspend fun waitForView(): Boolean {
            // Visible uploads have priority, but a finished/failed view never blocks the ring forever.
            while ((paused || streamingVisible || nativeWarmupCenter != null ||
                lastStreamCenter != LocationStore.cityCenter.value) && current()) delay(120)
            return current()
        }
        val seen = mutableSetOf<String>()
        var failed = 0
        var completedRegions = 0
        var budgetReached = false
        neighborhoodRequests.keys.retainAll(neighborhood.regions.toSet())
        prioritizeNeighborhood()
        regions@ for (region in neighborhood.regions) {
            if (!waitForView()) return
            try {
                var selection: List<TileRequest>?
                do {
                    if (!waitForView()) return
                    val selectionCenter = LocationStore.cityCenter.value
                    selection = service.selectRegion(region) {
                        !paused && !streamingVisible && nativeWarmupCenter == null &&
                            LocationStore.cityCenter.value == selectionCenter
                    }
                } while (selection == null)
                val regionTiles = selection
                neighborhoodRequests[region] = regionTiles.map { it.id }.toSet()
                prioritizeNeighborhood()
                val requests = regionTiles.filter { seen.add(it.id) }
                for (batch in requests.filterNot { service.isCached(it.id) }.chunked(4)) {
                    if (!waitForView()) return
                    coroutineScope {
                        batch.map { request -> launch {
                            try {
                                if (!service.prefetch(request)) budgetReached = true
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: IOException) {
                                failed++
                                // A speculative miss must not replace a successfully displayed city with an error.
                                Log.w("CesiumCity", "Neighbor download deferred: ${e.javaClass.simpleName}")
                            }
                        }}.joinAll()
                    }
                    // Distant speculative content must never churn the useful inner buffer.
                    if (budgetReached) break@regions
                }
                completedRegions++
                Log.i("CesiumCity", "Neighborhood ready: cell ${region.eastIndex},${region.northIndex}; ${seen.size} unique source tiles")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed++
                Log.w("CesiumCity", "Neighbor selection deferred: ${e.javaClass.simpleName}")
            }
        }
        val cached = seen.count { service.isCached(it) }
        val stats = service.cacheStats()
        Log.i("CesiumCity", "${MeshNeighborhood.DIAMETER}x${MeshNeighborhood.DIAMETER} neighborhood " +
            "${if (budgetReached) "memory-limited" else "complete"} around ${neighborhood.central.eastIndex},${neighborhood.central.northIndex}: " +
            "$completedRegions/${neighborhood.regions.size} regions, ${seen.size} source tiles, $cached cached, " +
            "$failed failures, ${stats.bytes / (1024 * 1024)}MiB / ${stats.budgetBytes / (1024 * 1024)}MiB budget")
    }

    private fun positionCity(center: CityCenter) {
        tiles.values.filter { it.visible(center) }.minOfOrNull { it.minimumHeight }?.let { minimum ->
            groundHeight = min(groundHeight ?: minimum, minimum)
        }
        // Keep one shared vertical origin as lower terrain arrives, instead of anchoring
        // every subsequent tile to the first tile (which can be a roof or a tower).
        cityRoot.components[TransformComponent::class.java]?.setPosition(Vector3(
            (-center.east * CityView.SCALE).toFloat(),
            (-(groundHeight ?: 0.0) * CityView.SCALE).toFloat(),
            (center.north * CityView.SCALE).toFloat()))
        val now = android.os.SystemClock.elapsedRealtime()
        tiles.values.forEach { tile ->
            val visible = tile.visible(center)
            if (tile.displayed != visible) {
                tile.entity.enabled = visible
                tile.displayed = visible
            }
            if (visible) tile.lastVisible = now
        }
    }

    private suspend fun upload(tile: PhotoTile, speculative: Boolean = false): Boolean {
        if (tile.id in tiles || !preparing.add(tile.id)) return true
        val parts = mutableListOf<NativePart>()
        val profile = LongArray(7)
        val uploadStarted = android.os.SystemClock.elapsedRealtime()
        var entity: Entity? = null
        try {
            val (bounds, estimatedBytes, heightGrid) = withContext(Dispatchers.Default) {
                val vertices = tile.primitives.asSequence().flatMap { it.vertices.asSequence() }
                val bounds = NativeTileBounds(vertices.minOf { it.x }, vertices.maxOf { it.x },
                    vertices.minOf { it.z }, vertices.maxOf { it.z })
                val grid = FloatArray(HEIGHT_GRID * HEIGHT_GRID) { Float.POSITIVE_INFINITY }
                val spanX = (bounds.maxX - bounds.minX).coerceAtLeast(1e-6); val spanZ = (bounds.maxZ - bounds.minZ).coerceAtLeast(1e-6)
                tile.primitives.forEach { primitive -> primitive.vertices.forEach { v ->
                    val cx = ((v.x - bounds.minX) / spanX * HEIGHT_GRID).toInt().coerceIn(0, HEIGHT_GRID - 1)
                    val cz = ((v.z - bounds.minZ) / spanZ * HEIGHT_GRID).toInt().coerceIn(0, HEIGHT_GRID - 1)
                    val slot = cz * HEIGHT_GRID + cx
                    if (v.y < grid[slot]) grid[slot] = v.y.toFloat()
                } }
                val bytes = tile.primitives.sumOf { primitive ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(primitive.image, 0, primitive.image.size, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid city photo texture")
                    // Texture plus mip levels, native buffers, and retained boxed CPU geometry.
                    options.outWidth.toLong() * options.outHeight * 16 / 3 +
                        primitive.vertices.size * 96L + primitive.indices.size * 24L
                }
                Triple(bounds, bytes, grid)
            }
            profile[0] = android.os.SystemClock.elapsedRealtime() - uploadStarted
            awaitResumed()
            fun admit(): Boolean {
                val center = LocationStore.cityCenter.value
                val incoming = NativeTileCacheEntry(tile.id, estimatedBytes, bounds,
                    android.os.SystemClock.elapsedRealtime())
                val protected = protectedNativeIds(center) + if (speculative) emptySet() else setOf(tile.id)
                val victims = nativePolicy.evictions(cacheEntries() + incoming, protected, center)
                if (tile.id in victims) return false
                victims.forEach { tiles.remove(it)?.close() }
                return true
            }
            if (!admit()) return false
            // Native resource creation can wait for the rendering service. Keep it off the UI thread.
            var wireMaterial: UnlitMaterial? = null
            withContext(Dispatchers.IO) {
                if (revealTiles) wireMaterial = UnlitMaterial.create(BlendingMode.FADE).apply {
                    toGlobal()
                    setBaseColor(linearColor(0x4FE3FF, 0f))
                    setCullingMode(MaterialCullingMode.NONE)
                    setPolygonFillMode(PolygonFillMode.LINE)
                }
                for ((index, p) in tile.primitives.withIndex()) {
                    ensureActive()
                    var stageStarted = android.os.SystemClock.elapsedRealtime()
                    val bitmap = BitmapFactory.decodeByteArray(p.image, 0, p.image.size)
                        ?: throw IOException("Could not decode city photo texture")
                    profile[1] += android.os.SystemClock.elapsedRealtime() - stageStarted
                    stageStarted = android.os.SystemClock.elapsedRealtime()
                    val texture = try { TextureResource.create(bitmap).also { it.toGlobal() } } finally { bitmap.recycle() }
                    profile[2] += android.os.SystemClock.elapsedRealtime() - stageStarted
                    var material: UnlitMaterial? = null
                    try {
                        stageStarted = android.os.SystemClock.elapsedRealtime()
                        material = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                            toGlobal()
                            setBaseColor(Color4(1f, 1f, 1f, 1f))
                            setBaseColorTexture(texture)
                            setCullingMode(MaterialCullingMode.NONE)
                        }
                        profile[3] += android.os.SystemClock.elapsedRealtime() - stageStarted
                        stageStarted = android.os.SystemClock.elapsedRealtime()
                        val positions = p.vertices.map { v -> Vector3((v.x*CityView.SCALE).toFloat(),
                            (v.y*CityView.SCALE).toFloat(), (v.z*CityView.SCALE).toFloat()) }
                        val uv = p.vertices.map { Vector2(it.u.toFloat(), (1.0-it.v).toFloat()) }
                        val indices = p.indices.toList()
                        if (revealTiles) material.setBaseColor(developColor(0f))
                        profile[4] += android.os.SystemClock.elapsedRealtime() - stageStarted
                        stageStarted = android.os.SystemClock.elapsedRealtime()
                        val mesh = MeshResource.createWithMeshModel(MeshModel(positions, indices, uv0=uv),
                            null, "CesiumTile${tile.id.hashCode()}Part$index").also { it.toGlobal() }
                        profile[5] += android.os.SystemClock.elapsedRealtime() - stageStarted
                        // The volume's stationary pan box handles input; photogrammetry does not
                        // need expensive collision cooking for every detailed building surface.
                        parts += NativePart(mesh, material, texture, positions)
                    } catch (e: Exception) { material?.close(); texture.close(); throw e }
                }
            }
            currentCoroutineContext().ensureActive()
            awaitResumed()
            if (!active || !admit()) { wireMaterial?.close(); return false }
            if (tile.id in tiles) { wireMaterial?.close(); return true }
            val attachStarted = android.os.SystemClock.elapsedRealtime()
            entity = Entity()
            parts.forEach { part -> entity.addChild(ModelEntity(part.mesh, part.material).apply {
                setName(CITY_NAME)
            }) }
            val wires = wireMaterial?.let { wire -> parts.map { part -> ModelEntity(part.mesh, wire).also { entity.addChild(it) } } }
            if (revealTiles) entity.components[TransformComponent::class.java]?.setScaleVector(Vector3(1f, 0.01f, 1f))
            val center = LocationStore.cityCenter.value
            entity.enabled = bounds.visible(center)
            cityRoot.addChild(entity)
            tiles[tile.id] = NativeTile(tile.id, entity, parts.toList(), tile.copyright,
                tile.primitives.sumOf { it.indices.size / 3 }, bounds,
                tile.primitives.minOf { primitive -> primitive.vertices.minOf { it.y } }, estimatedBytes, heightGrid,
                android.os.SystemClock.elapsedRealtime(), bounds.visible(center),
                wireMaterial = wireMaterial, wireEntities = wires.orEmpty())
            wireMaterial = null
            if (revealTiles) pendingReveals.addLast(tile.id)
            tileVersion.value++
            profile[6] = android.os.SystemClock.elapsedRealtime() - attachStarted
            cameraMarkerXZ?.let { (x, z) ->
                if (bounds.contains(x / CityView.SCALE, z / CityView.SCALE, 12.0)) refreshCameraMarkerHeight()
            }
            epicenterXZ?.let { (x, z) ->
                if (bounds.contains(x / CityView.SCALE, z / CityView.SCALE, 12.0)) groundHeightNear(x, z)?.let { ground ->
                    epicenter?.components?.get(TransformComponent::class.java)?.setPosition(Vector3(x, ground, z))
                }
            }
            if (com.example.earthexplorer.BuildConfig.DEBUG) {
                Log.i("CesiumCity", "Native stages ${if (speculative) "halo" else "visible"}: " +
                    "parts=${parts.size}; estimate=${profile[0]}ms; bitmap=${profile[1]}ms; " +
                    "texture=${profile[2]}ms; material=${profile[3]}ms; geometry=${profile[4]}ms; " +
                    "mesh=${profile[5]}ms; attach=${profile[6]}ms; " +
                    "total=${android.os.SystemClock.elapsedRealtime() - uploadStarted}ms")
            }
            entity = null
            parts.clear()
            return true
        } finally {
            preparing.remove(tile.id)
            entity?.destroy()
            parts.forEach { it.close() }
        }
    }

    private companion object {
        const val REVEAL_MILLIS = 1_700L
        const val REVEAL_STAGGER_MILLIS = 70L
        const val SINK_MILLIS = 1_400L
        const val REVEAL_EPICENTER_MILLIS = 1_600L
        /** The city table sits 5 cm lower than the original floor so tall towers clear the label. */
        const val CITY_TABLE_Y = -0.35f
        const val SHOCKWAVE_PERIOD = 3_000L
        const val HEIGHT_GRID = 8
    }

    private fun safeError(e: Exception): String = if (e is IOException) {
        e.message?.takeIf { !it.contains("http:") && !it.contains("https:") && !it.contains("key=") }?.take(180)
            ?: "Tile network request failed; retry when online"
    } else "City tile could not load (${e.javaClass.simpleName})"

    private fun progress() {
        if (!active) return
        val center = LocationStore.cityCenter.value
        val visible = tiles.values.filter { it.visible(center) }
        val ready = targetIds.count { it in tiles }
        val status = when {
            selecting -> "Finding tiles around the view…"
            loadError != null -> if (visible.isNotEmpty()) "City visible · some tiles failed" else "City tiles could not load"
            ready < targetIds.size -> "Streaming photorealistic tiles · $ready / ${targetIds.size}"
            else -> "3D tiles loaded · drag to explore"
        }
        val credits = tiles.values.flatMap { it.copyright.split(';') }.map { it.trim() }.filter { it.isNotBlank() }.toSortedSet()
        LocationStore.updateCityProgress(status, visible.size, targetIds.size,
            "Google Maps · Cesium ion · ${credits.joinToString("; ")}", loadError,
            visible.sumOf { it.triangles }, coarseTiles)
    }

    private fun addPanTarget() {
        val bounds = ExplorerHitBox.withinVolume(volumeSide.toDouble())
        // Keep the stationary fallback separate from the translated city and within the clip box.
        overlay.addChild(Entity().apply {
            setName(CITY_NAME)
            components[TransformComponent::class.java]?.setPosition(Vector3(0f, bounds.center.y.toFloat(), 0f))
            val shape = ShapeResource.createBox(Vector3(bounds.size.x.toFloat(), bounds.size.y.toFloat(), bounds.size.z.toFloat()))
            val physics = PhysicsMaterialResource()
            fixedResources += listOf(shape, physics)
            components.set(CollisionComponent(listOf(shape), physics))
            components.set(InteractableComponent())
        })
        if (com.example.earthexplorer.BuildConfig.DEBUG) Log.i("ExplorerDrag", "City volume side=$volumeSide; hit box=$bounds")
    }

    /** Compass rose, north mark and a 100 m scale bar drawn on the floor, always readable through the mesh. */
    private fun addCompassHud() {
        val size = 1024
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cyan = Color.argb(200, 79, 227, 255)
        val cx = size / 2f; val cy = size / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(110, 79, 227, 255)
        canvas.drawCircle(cx, cy, 150f, paint)
        canvas.drawCircle(cx, cy, 108f, paint)
        paint.color = cyan
        for (degrees in 0 until 360 step 30) {
            val a = Math.toRadians(degrees.toDouble())
            val major = degrees % 90 == 0
            paint.strokeWidth = if (major) 6f else 3f
            val r0 = if (major) 128f else 138f
            canvas.drawLine((cx + r0 * sin(a)).toFloat(), (cy - r0 * cos(a)).toFloat(),
                (cx + 150f * sin(a)).toFloat(), (cy - 150f * cos(a)).toFloat(), paint)
        }
        paint.strokeWidth = 2f
        canvas.drawLine(cx - 40f, cy, cx + 40f, cy, paint)
        canvas.drawLine(cx, cy - 40f, cx, cy + 40f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 44f
        paint.color = Color.argb(240, 255, 200, 87)
        canvas.drawText("N", cx, cy - 168f, paint)
        paint.color = cyan
        paint.textSize = 34f
        canvas.drawText("E", cx + 182f, cy + 12f, paint)
        canvas.drawText("S", cx, cy + 196f, paint)
        canvas.drawText("W", cx - 182f, cy + 12f, paint)
        // Scale bar: 100 m in the same units as the mesh, in the near-right corner (closest to the viewer).
        val barPixels = (100.0 * CityView.SCALE / volumeSide * size).toFloat()
        val barRight = size - 60f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawLine(barRight - barPixels, size - 70f, barRight, size - 70f, paint)
        canvas.drawLine(barRight - barPixels, size - 84f, barRight - barPixels, size - 56f, paint)
        canvas.drawLine(barRight, size - 84f, barRight, size - 56f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 30f
        paint.letterSpacing = 0.12f
        canvas.drawText("100 M", barRight, size - 96f, paint)
        val texture = TextureResource.create(bitmap)
        bitmap.recycle()
        val material = UnlitMaterial.create(BlendingMode.FADE).apply {
            setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE); setDepthTest(false); setDepthWrite(false)
        }
        hudMaterial = material
        val mesh = floorImageQuad(volumeSide * 0.985f)
        fixedResources += listOf(texture, material, mesh)
        overlay.addChild(ModelEntity(mesh, material).apply {
            setName("CityHud")
            components[TransformComponent::class.java]?.setPosition(Vector3(0f, SCENE_FLOOR_Y + 0.002f, 0f))
        })
    }

    /** The underside motes start only once the loading grid has retracted and the city stands on its own. */
    fun setUndersideActive(active: Boolean) { beams?.setVisible(active) }

    /** Column tops come from the lowest loaded terrain over each column, in stationary volume coordinates. */
    private fun refreshBeams(center: CityCenter) {
        val beams = beams ?: return
        val s = CityView.SCALE
        val offsetY = (-(groundHeight ?: 0.0) * s + CITY_TABLE_Y).toFloat()
        val visible = tiles.values.filter { it.displayed }
        val tops = FloatArray(beams.columns.size) { index ->
            val column = beams.columns[index]
            val mx = column.x / s + center.east
            val mz = column.z / s - center.north
            var lowest = Float.POSITIVE_INFINITY
            visible.forEach { tile ->
                val b = tile.bounds
                if (mx < b.minX || mx > b.maxX || mz < b.minZ || mz > b.maxZ) return@forEach
                val cx = ((mx - b.minX) / (b.maxX - b.minX).coerceAtLeast(1e-6) * HEIGHT_GRID).toInt().coerceIn(0, HEIGHT_GRID - 1)
                val cz = ((mz - b.minZ) / (b.maxZ - b.minZ).coerceAtLeast(1e-6) * HEIGHT_GRID).toInt().coerceIn(0, HEIGHT_GRID - 1)
                val value = tile.heightGrid[cz * HEIGHT_GRID + cx]
                if (value < lowest) lowest = value
            }
            if (lowest.isFinite()) (lowest * s).toFloat() + offsetY - 0.002f else Float.NaN
        }
        beams.update(tops)
    }

    /** The globe's pulsing cyan lock-on reticle at the epicenter, with a floating magnitude label. */
    private fun updateEpicenter(quake: GlobeFeature?, animate: Boolean = false) {
        epicenterReveal?.cancel()
        epicenterReveal = null
        epicenter?.destroy()
        epicenter = null
        epicenterPulse = null
        epicenterPulseMaterial = null
        shockwaves = emptyList()
        epicenterResources.forEach { it.close() }
        epicenterResources = emptyList()
        epicenterXZ = null
        if (quake == null || !active) return
        val local = frame.local(GeoFrame.ecef(quake.latitude, quake.longitude))
        val s = CityView.SCALE.toFloat()
        val x = (local.x * CityView.SCALE).toFloat()
        val z = (local.z * CityView.SCALE).toFloat()
        epicenterXZ = x to z
        val ground = groundHeightNear(x, z) ?: ((groundHeight ?: 0.0) * CityView.SCALE).toFloat()
        val owned = ArrayList<Resource>()
        fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); owned += it }
        try {
            // Drawn without depth testing so facades never hide the marker or its label.
            fun overlay(rgb: Int, alpha: Float) = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColor(linearColor(rgb, alpha)); setCullingMode(MaterialCullingMode.NONE)
                setDepthTest(false); setDepthWrite(false)
            }
            val ringMaterial = overlay(0x4FE3FF, 0.95f)
            val pulseMaterial = overlay(0x4FE3FF, 0.6f)
            val ring = keep(ringMesh(16f * s, 1.3f * s, "epicenter-ring"))
            val dot = keep(MeshResource.createSphere(2.6f * s))
            val stem = keep(MeshResource.createBox(Vector3(0.9f * s, 60f * s, 0.9f * s), 0f))
            val bitmap = Bitmap.createBitmap(640, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(225, 8, 15, 28)
            canvas.drawRoundRect(RectF(0f, 0f, 640f, 256f), 40f, 40f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.argb(200, 79, 227, 255)
            canvas.drawRoundRect(RectF(3f, 3f, 637f, 253f), 40f, 40f, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            paint.letterSpacing = 0.22f
            paint.textSize = 40f
            paint.color = Color.rgb(255, 122, 69)
            canvas.drawText("EPICENTER", 320f, 76f, paint)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.letterSpacing = 0f
            paint.textSize = 132f
            paint.color = Color.WHITE
            val magnitude = quake.magnitude?.let { "M %.1f".format(java.util.Locale.US, it) } ?: "M –"
            canvas.drawText(magnitude, 320f, 206f, paint)
            val texture = try { keep(TextureResource.create(bitmap)) } finally { bitmap.recycle() }
            val labelMaterial = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE)
                setDepthTest(false); setDepthWrite(false)
            }
            val label = keep(MeshResource.createPlane(90f * s, 36f * s, 0.001f))
            epicenterPulseMaterial = pulseMaterial
            val waveMesh = keep(ringMesh(10f * s, 0.9f * s, "shockwave"))
            val waves = List(2) { overlay(0xFF7A45, 0f) }
            var labelTransform: TransformComponent? = null
            var stemTransform: TransformComponent? = null
            var ringTransform: TransformComponent? = null
            epicenter = Entity().apply {
                setName("Epicenter")
                components[TransformComponent::class.java]?.setPosition(Vector3(x, ground, z))
                addChild(ModelEntity(ring, ringMaterial).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 1f * s, 0f))
                    ringTransform = components[TransformComponent::class.java]
                })
                waves.forEach { material ->
                    addChild(ModelEntity(waveMesh, material).apply {
                        components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0.6f * s, 0f))
                        components[TransformComponent::class.java]?.let { shockwaves = shockwaves + Shockwave(it, material) }
                    })
                }
                addChild(ModelEntity(ring, pulseMaterial).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 1f * s, 0f))
                    epicenterPulse = components[TransformComponent::class.java]
                })
                addChild(ModelEntity(dot, ringMaterial).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 1.5f * s, 0f))
                })
                addChild(ModelEntity(stem, ringMaterial).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 31f * s, 0f))
                    stemTransform = components[TransformComponent::class.java]
                })
                addChild(ModelEntity(label, labelMaterial).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 80f * s, 0f))
                    labelTransform = components[TransformComponent::class.java]
                })
                cityRoot.addChild(this)
            }
            epicenterResources = owned
            shockwaveSince = android.os.SystemClock.elapsedRealtime()
            if (animate) {
                // Release: the ring snaps open, the stem grows and the label rises out of the ground.
                ringTransform?.setScaleVector(Vector3(0.05f, 1f, 0.05f))
                stemTransform?.setScaleVector(Vector3(1f, 0.01f, 1f))
                labelTransform?.setPosition(Vector3(0f, 2f * s, 0f))
                labelTransform?.setScaleVector(Vector3(0.01f))
                epicenterReveal = scope.launch {
                    val started = android.os.SystemClock.elapsedRealtime()
                    while (isActive) {
                        val k = ((android.os.SystemClock.elapsedRealtime() - started) / REVEAL_EPICENTER_MILLIS.toFloat()).coerceIn(0f, 1f)
                        val ring = smooth((k / 0.45f).coerceIn(0f, 1f))
                        val rise = smooth(((k - 0.25f) / 0.6f).coerceIn(0f, 1f))
                        val pop = smooth(((k - 0.55f) / 0.45f).coerceIn(0f, 1f))
                        ringTransform?.setScaleVector(Vector3(0.05f + 0.95f * ring, 1f, 0.05f + 0.95f * ring))
                        stemTransform?.setScaleVector(Vector3(1f, 0.01f + 0.99f * rise, 1f))
                        stemTransform?.setPosition(Vector3(0f, 31f * s * (0.01f + 0.99f * rise), 0f))
                        labelTransform?.setPosition(Vector3(0f, (2f + 78f * rise) * s, 0f))
                        labelTransform?.setScaleVector(Vector3(0.01f + 0.99f * pop))
                        if (k >= 1f) break
                        delay(16)
                    }
                }
            }
            Log.i("CesiumCity", "Epicenter marker $magnitude at ${local.x.toInt()}m E / ${(-local.z).toInt()}m N")
        } catch (error: Exception) {
            owned.asReversed().forEach { it.close() }
            Log.w("CesiumCity", "Epicenter marker unavailable: ${error.javaClass.simpleName}")
        }
    }

    /** A teal beacon at the camera's published position with an estimated view cone. */
    private fun updateCameraMarker(camera: WebcamCamera?) {
        cameraMarker?.destroy()
        cameraMarker = null
        cameraMarkerResources.forEach { it.close() }
        cameraMarkerResources = emptyList()
        cameraMarkerXZ = null
        if (camera == null || !active) return
        val local = frame.local(GeoFrame.ecef(camera.latitude, camera.longitude))
        val s = CityView.SCALE.toFloat()
        val x = (local.x * CityView.SCALE).toFloat()
        val z = (local.z * CityView.SCALE).toFloat()
        cameraMarkerXZ = x to z
        val ground = groundHeightNear(x, z) ?: ((groundHeight ?: 0.0) * CityView.SCALE).toFloat()
        val owned = ArrayList<Resource>()
        fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); owned += it }
        try {
            val solid = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColor(linearColor(0x3EE8C5, 0.95f)); setCullingMode(MaterialCullingMode.NONE)
            }
            val glass = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColor(linearColor(0x3EE8C5, 0.36f)); setCullingMode(MaterialCullingMode.NONE); setDepthWrite(false)
            }
            val pole = keep(MeshResource.createBox(Vector3(2.5f * s, 18f * s, 2.5f * s), 0f))
            val head = keep(MeshResource.createBox(Vector3(7f * s, 4f * s, 4.5f * s), 0f))
            val ring = keep(ringMesh(14f * s, 1f * s, "webcam-ring"))
            val rangeRings = listOf(40f, 80f).map { keep(ringMesh(it * s, 0.45f * s, "webcam-range")) }
            val range = keep(UnlitMaterial.create(BlendingMode.FADE)).apply {
                setBaseColor(linearColor(0x3EE8C5, 0.22f)); setCullingMode(MaterialCullingMode.NONE); setDepthTest(false); setDepthWrite(false)
            }
            val cone = camera.headingDegrees?.let { keep(viewConeMesh(100f * s, 24f, 17f * s, it.toFloat())) }
            cameraMarker = Entity().apply {
                setName("WebcamBeacon")
                components[TransformComponent::class.java]?.setPosition(Vector3(x, ground, z))
                addChild(ModelEntity(pole, solid).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 9f * s, 0f))
                })
                addChild(ModelEntity(head, solid).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 19f * s, 0f))
                })
                addChild(ModelEntity(ring, solid).apply {
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0.6f * s, 0f))
                })
                rangeRings.forEach { mesh ->
                    addChild(ModelEntity(mesh, range).apply {
                        components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0.8f * s, 0f))
                    })
                }
                cone?.let { mesh ->
                    addChild(ModelEntity(mesh, glass).apply {
                        components[TransformComponent::class.java]?.setPosition(Vector3(0f, 19f * s, 0f))
                    })
                }
                cityRoot.addChild(this)
            }
            cameraMarkerResources = owned
            Log.i("CesiumCity", "Webcam beacon at ${local.x.toInt()}m E / ${(-local.z).toInt()}m N; heading ${camera.headingDegrees}")
        } catch (error: Exception) {
            owned.asReversed().forEach { it.close() }
            Log.w("CesiumCity", "Webcam beacon unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun refreshCameraMarkerHeight() {
        val entity = cameraMarker ?: return
        val (x, z) = cameraMarkerXZ ?: return
        val ground = groundHeightNear(x, z) ?: return
        entity.components[TransformComponent::class.java]?.setPosition(Vector3(x, ground, z))
        Log.i("CesiumCity", "Webcam beacon street level ${(ground / CityView.SCALE).toInt()}m (mesh units)")
    }

    /** Street level near a point: the lower quartile of nearby photo-mesh heights, below facades and roofs. */
    private fun groundHeightNear(x: Float, z: Float): Float? {
        val radius = 12f * CityView.SCALE.toFloat()
        val heights = ArrayList<Float>()
        tiles.values.forEach { tile ->
            if (!tile.bounds.contains(x / CityView.SCALE, z / CityView.SCALE, 12.0)) return@forEach
            tile.parts.forEach { part ->
                part.positions.forEach { p -> if (abs(p.x - x) < radius && abs(p.z - z) < radius) heights += p.y }
            }
        }
        if (heights.size < 4) {
            Log.i("CesiumCity", "Webcam beacon: only ${heights.size} mesh vertices within 12m; keeping previous height")
            return null
        }
        heights.sort()
        return heights[heights.size / 4]
    }

    private fun updateLabel(city: String?, country: String?) {
        labelEntity?.destroy()
        labelEntity = null
        labelResources.forEach { it.close() }
        labelResources = emptyList()
        if (!active) return
        val label = cityLabelText(city, country)
        val bitmap = Bitmap.createBitmap(1536, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cyan = Color.rgb(79, 227, 255)
        paint.color = Color.argb(218, 8, 15, 28)
        canvas.drawRoundRect(RectF(0f, 0f, 1536f, 240f), 120f, 120f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(160, 79, 227, 255)
        canvas.drawRoundRect(RectF(2f, 2f, 1534f, 238f), 119f, 119f, paint)
        // A small accent dot on the left, echoing the panels button.
        paint.style = Paint.Style.FILL
        paint.color = cyan
        canvas.drawCircle(96f, 120f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // The close control belongs to the native 3D label and has its own hit target below.
        paint.strokeWidth = 5f
        canvas.drawCircle(1414f, 120f, 74f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(46, 79, 227, 255)
        canvas.drawCircle(1414f, 120f, 74f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 12f
        canvas.drawLine(1388f, 94f, 1440f, 146f, paint)
        canvas.drawLine(1440f, 94f, 1388f, 146f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 98f
        paint.letterSpacing = 0f
        paint.typeface = Typeface.DEFAULT_BOLD
        if (paint.measureText(label.city) > 1150f) paint.textSize *= 1150f / paint.measureText(label.city)
        canvas.drawText(label.city, 690f, if (label.country == null) 154f else 116f, paint)
        label.country?.let { countryName ->
            paint.color = cyan
            paint.textSize = 46f
            paint.letterSpacing = 0.18f
            paint.typeface = Typeface.DEFAULT
            val subtitle = countryName.uppercase()
            if (paint.measureText(subtitle) > 1150f) paint.textSize *= 1150f / paint.measureText(subtitle)
            canvas.drawText(subtitle, 690f, 198f, paint)
        }
        val texture = TextureResource.create(bitmap)
        bitmap.recycle()
        val material = UnlitMaterial.create(BlendingMode.FADE).apply { setBaseColorTexture(texture); setCullingMode(MaterialCullingMode.NONE) }
        val width = volumeSide * 0.60f
        val height = width * 240f / 1536f
        val mesh = MeshResource.createPlane(width, height, 0.01f)
        val closeShape = ShapeResource.createBox(Vector3(width * 180f / 1536f, height * 0.82f, 0.04f))
        val closePhysics = PhysicsMaterialResource()
        labelResources = listOf(texture, material, mesh, closeShape, closePhysics)
        labelEntity = Entity().apply {
            // Center in both horizontal axes, with the label's top just inside the volume ceiling.
            // Right of center so the panels button in the corner never overlaps it.
            components[TransformComponent::class.java]?.setPosition(Vector3(volumeSide * 0.14f, volumeSide / 2 - height / 2 - 0.008f, 0f))
            addChild(ModelEntity(mesh, material).apply { setName(CITY_STATUS_NAME) })
            addChild(Entity().apply {
                setName(CITY_CLOSE_NAME)
                components[TransformComponent::class.java]?.setPosition(Vector3(width * 646f / 1536f, 0f, 0.012f))
                components.set(CollisionComponent(listOf(closeShape), closePhysics))
                components.set(InteractableComponent())
            })
        }.also { overlay.addChild(it) }
    }

    fun destroy() {
        active = false
        scope.cancel()
        pendingReveals.clear()
        service.clearMemoryCache()
        tiles.values.forEach { it.close() }
        tiles.clear()
        table.destroy()
        overlay.destroy()
        beams?.destroy()
        cameraMarkerResources.forEach { it.close() }
        epicenterResources.forEach { it.close() }
        labelResources.forEach { it.close() }
        fixedResources.forEach { it.close() }
    }
}
