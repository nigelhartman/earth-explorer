package com.example.earthexplorer.data

import com.example.earthexplorer.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class PhotoPrimitive(
    val vertices: List<TileVertex>,
    val indices: IntArray,
    val image: ByteArray,
)

data class PhotoTile(val id: String, val primitives: List<PhotoPrimitive>, val copyright: String) {
    val byteSize: Int get() = primitives.sumOf { it.image.size + it.vertices.size * 40 + it.indices.size * 4 }
}

data class TileRequest(val id: String, val url: URI, val transform: DMatrix, val geometricError: Double)

/** Live Cesium ion / Google Photorealistic 3D Tiles session. No disk/offline tile cache. */
class CesiumTiles(private val frame: GeoFrame) {
    private data class Document(val root: JSONObject, val url: URI)
    private class SelectionYielded : RuntimeException()
    private val documents = object : LinkedHashMap<String, Document>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>?) = size > 384
    }
    private val regionRequests = object : LinkedHashMap<MeshRegion, List<TileRequest>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshRegion, List<TileRequest>>?) =
            size > MeshNeighborhood.REGION_COUNT * 2
    }
    private val selectionMutex = Mutex()
    // Leave room for parallel glTF decoding, image buffers and the rest of the app's heap.
    private val contentCache = MeshTileCache(meshTileCacheBudget(Runtime.getRuntime().maxMemory()))
    private val downloadMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private var root: Document? = null
    private var connectedAt = 0L
    private var sessionKey: String? = null

    suspend fun select(center: CityCenter): List<TileRequest> = withContext(Dispatchers.IO) {
        selectionMutex.withLock {
            ensureConnected()
            selectFootprint(center, 65.0).also {
                if (it.isEmpty()) throw IOException("Photorealistic city tiles are unavailable here")
            }
        }
    }

    /** Null yields speculative work to the foreground; a nonnull empty list means no coverage. */
    suspend fun selectRegion(
        region: MeshRegion,
        shouldContinue: () -> Boolean = { true },
    ): List<TileRequest>? = withContext(Dispatchers.IO) {
        try {
            if (!shouldContinue()) return@withContext null
            selectionMutex.withLock {
                if (!shouldContinue()) throw SelectionYielded()
                ensureConnected()
                if (!shouldContinue()) throw SelectionYielded()
                regionRequests[region] ?: selectFootprint(region.center, 0.0, shouldContinue)
                    .also { regionRequests[region] = it }
            }
        } catch (_: SelectionYielded) {
            // Yield the shared hierarchy cache to the foreground without canceling its worker.
            null
        }
    }

    /** Prepare the next short pan in native memory after the visible view is complete. */
    suspend fun selectNativeHalo(center: CityCenter, shouldContinue: () -> Boolean): List<TileRequest>? =
        withContext(Dispatchers.IO) {
            try {
                if (!shouldContinue()) return@withContext null
                selectionMutex.withLock {
                    if (!shouldContinue()) throw SelectionYielded()
                    ensureConnected()
                    selectFootprint(center, CityView.HALF_WIDTH, shouldContinue, maxTiles = 420)
                }
            } catch (_: SelectionYielded) { null }
        }

    private suspend fun ensureConnected() {
        if (root == null || System.currentTimeMillis() - connectedAt > 90 * 60_000) connect()
    }

    private suspend fun selectFootprint(
        center: CityCenter,
        margin: Double,
        shouldContinue: () -> Boolean = { true },
        maxTiles: Int = 180,
    ): List<TileRequest> {
        currentCoroutineContext().ensureActive()
        if (!shouldContinue()) throw SelectionYielded()
        val result = mutableListOf<TileRequest>()
        var visited = 0
        var fetched = 0
        suspend fun visit(tile: JSONObject, base: URI, inherited: DMatrix, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (!shouldContinue()) throw SelectionYielded()
            if (++visited > 20_000 || depth > 64) throw IOException("City tile hierarchy exceeded its display budget")
            val transform = inherited * tile.optJSONArray("transform").matrixOrIdentity()
            if (!intersects(tile.optJSONObject("boundingVolume"), transform, center, margin)) return
            val content = tile.optJSONObject("content")
            val raw = content?.optString("uri")?.ifBlank { null }
                ?: content?.optString("url")?.ifBlank { null }
            val url = raw?.let { resolve(base, it) }
            val children = tile.optJSONArray("children")
            if (url?.path?.endsWith(".json") == true) {
                val document = documents[url.toString()] ?: run {
                    if (++fetched > 120) throw IOException("Too many city tile requests; drag a shorter distance and retry")
                    Document(JSONObject(fetch(url).toString(Charsets.UTF_8)).getJSONObject("root"), url)
                        .also { documents[url.toString()] = it }
                }
                // A completed document remains reusable even when this speculative walk yields.
                if (!shouldContinue()) throw SelectionYielded()
                visit(document.root, document.url, transform, depth + 1)
            } else if (children != null && children.length() > 0 && tile.optDouble("geometricError", 1e10) > 4.5) {
                val ordered = (0 until children.length()).map { children.getJSONObject(it) }
                    .sortedBy { distance(it.optJSONObject("boundingVolume"), transform, center) }
                for (child in ordered) visit(child, base, transform, depth + 1)
            } else if (url != null && url.path.endsWith(".glb")) {
                if (result.size >= maxTiles) throw IOException("City detail exceeds the tile budget")
                result += TileRequest(url.path, url, transform, tile.optDouble("geometricError", 0.0))
            } else if (children != null) {
                for (i in 0 until children.length()) visit(children.getJSONObject(i), base, transform, depth + 1)
            }
        }
        val document = requireNotNull(root)
        visit(document.root, document.url, DMatrix.IDENTITY, 0)
        if (!shouldContinue()) throw SelectionYielded()
        return result.distinctBy { it.id }
    }

    suspend fun load(request: TileRequest): PhotoTile = withContext(Dispatchers.IO) {
        val bytes = compressedContent(request)
        currentCoroutineContext().ensureActive()
        GlbTileDecoder.decode(bytes, request.id, frame, request.transform)
    }

    /** Download neighboring tiles without decoding or allocating native textures/meshes. */
    suspend fun prefetch(request: TileRequest): Boolean = withContext(Dispatchers.IO) {
        compressedContent(request)
        contentCache.contains(request.id)
    }

    fun prioritizeNeighborhood(priorityById: Map<String, Int>) { contentCache.prioritize(priorityById) }
    fun isCached(id: String): Boolean = contentCache.contains(id)
    fun cacheStats(): MeshCacheStats = contentCache.stats()
    fun clearMemoryCache() { contentCache.clear() }

    private suspend fun compressedContent(request: TileRequest): ByteArray {
        while (true) {
            currentCoroutineContext().ensureActive()
            var ownsRequest = false
            val pending = downloadMutex.withLock {
                contentCache.get(request.id)?.let { return it }
                inFlight[request.id] ?: CompletableDeferred<ByteArray>().also {
                    ownsRequest = true
                    inFlight[request.id] = it
                }
            }
            if (!ownsRequest) {
                try { return pending.await() }
                catch (e: CancellationException) {
                    // A canceled prefetch must not cancel another active caller awaiting its tile.
                    currentCoroutineContext().ensureActive()
                    downloadMutex.withLock {
                        if (inFlight[request.id] === pending) inFlight.remove(request.id)
                    }
                    continue
                }
            }
            try {
                val content = fetch(request.url)
                currentCoroutineContext().ensureActive()
                contentCache.put(request.id, content)
                pending.complete(content)
                return content
            } catch (e: Throwable) {
                pending.completeExceptionally(e)
                throw e
            } finally {
                // Removal must also run when its owner has been canceled.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    downloadMutex.withLock {
                        if (inFlight[request.id] === pending) inFlight.remove(request.id)
                    }
                }
            }
        }
    }

    private suspend fun connect() {
        val token = BuildConfig.CESIUM_ION_TOKEN.trim()
        if (token.isEmpty()) throw IOException("CESIUM_ION_TOKEN is missing from local.properties")
        val endpoint = JSONObject(fetch(URI("https://api.cesium.com/v1/assets/2275207/endpoint?access_token=${URLEncoder.encode(token, "UTF-8")}")).toString(Charsets.UTF_8))
        val address = endpoint.optJSONObject("options")?.optString("url")
            ?: endpoint.optString("url")
        if (address.isNullOrBlank()) throw IOException("Cesium did not return a photorealistic tiles endpoint")
        val uri = URI(address)
        checkService(uri)
        sessionKey = query(uri)["key"]
        val document = JSONObject(fetch(uri).toString(Charsets.UTF_8))
        root = Document(document.getJSONObject("root"), uri)
        documents.clear()
        regionRequests.clear()
        connectedAt = System.currentTimeMillis()
    }

    private fun resolve(base: URI, relative: String): URI {
        val uri = base.resolve(relative)
        checkService(uri)
        val params = query(uri).toMutableMap()
        if (uri.host == base.host) query(base).forEach { (key, value) -> params.putIfAbsent(key, value) }
        if (uri.host == "tile.googleapis.com") sessionKey?.let { params.putIfAbsent("key", it) }
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return URI("${uri.scheme}://${uri.rawAuthority}${uri.rawPath}" + if (query.isNotEmpty()) "?$query" else "")
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery?.split('&')?.associate {
        val parts = it.split('=', limit = 2)
        parts[0] to parts.getOrElse(1) { "" }
    } ?: emptyMap()

    private fun checkService(uri: URI) {
        if (uri.scheme != "https" || uri.host !in setOf("api.cesium.com", "tile.googleapis.com")) {
            throw IOException("Unexpected city tile service endpoint")
        }
    }

    private suspend fun fetch(uri: URI): ByteArray {
        checkService(uri)
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if ((code == 429 || code >= 500) && attempt < 2) {
                    delay(700L * (attempt + 1))
                } else {
                    if (code !in 200..299) throw IOException(when (code) {
                        401, 403 -> "Cesium access denied (HTTP $code); check token access to Photorealistic 3D Tiles"
                        429 -> "Cesium tile request quota reached; try again later"
                        else -> "City tile service returned HTTP $code"
                    })
                    return connection.inputStream.use { input ->
                        val bytes = input.readNBytes(24 * 1024 * 1024 + 1)
                        if (bytes.size > 24 * 1024 * 1024) throw IOException("City tile exceeds the memory budget")
                        bytes
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("City tile service is unavailable")
    }

    private fun distance(volume: JSONObject?, transform: DMatrix, center: CityCenter): Double {
        val box = volume?.optJSONArray("box") ?: return 0.0
        val p = frame.local(transform.point(box.vector(0)))
        return hypot(p.x - center.east, p.z + center.north)
    }

    private fun intersects(volume: JSONObject?, transform: DMatrix, center: CityCenter, margin: Double): Boolean {
        if (volume == null) return true
        val box = volume.optJSONArray("box")
        if (box != null) {
            val c = transform.point(box.vector(0))
            val p = frame.local(c)
            val axes = (3..9 step 3).map { transform.point(box.vector(0) + box.vector(it)) - c }
            val eastExtent = axes.sumOf { abs(it.dot(frame.east)) }
            val northExtent = axes.sumOf { abs(it.dot(frame.north)) }
            val upExtent = axes.sumOf { abs(it.dot(frame.up)) }
            // Ignore terrain altitude, but reject the far side of the planet.
            return abs(p.x - center.east) <= CityView.HALF_WIDTH + margin + eastExtent &&
                abs(p.z + center.north) <= CityView.HALF_WIDTH + margin + northExtent &&
                p.y + upExtent > -50_000 && p.y - upExtent < 50_000
        }
        val sphere = volume.optJSONArray("sphere")
        if (sphere != null) {
            val p = frame.local(transform.point(sphere.vector(0)))
            val radius = sphere.getDouble(3) * (0..2).maxOf { axis ->
                val v = transform.values
                DVec3(v[axis*4], v[axis*4+1], v[axis*4+2]).length()
            }
            return abs(p.x-center.east) < radius+CityView.HALF_WIDTH+margin &&
                abs(p.z+center.north) < radius+CityView.HALF_WIDTH+margin && p.y+radius > -50_000
        }
        val region = volume.optJSONArray("region")
        if (region != null) {
            val (lat, lon) = frame.coordinate(center)
            val angular = Math.toDegrees((CityView.HALF_WIDTH + margin) / 6_378_137.0)
            val south = Math.toDegrees(region.getDouble(1)) - angular
            val north = Math.toDegrees(region.getDouble(3)) + angular
            val west = Math.toDegrees(region.getDouble(0)) - angular / max(0.01, cos(Math.toRadians(lat)))
            val east = Math.toDegrees(region.getDouble(2)) + angular / max(0.01, cos(Math.toRadians(lat)))
            return lat in south..north && if (west <= east) lon in west..east else lon >= west || lon <= east
        }
        throw IOException("Unsupported city tile bounding volume")
    }
}

private fun JSONArray.vector(offset: Int) = DVec3(getDouble(offset), getDouble(offset + 1), getDouble(offset + 2))
private fun JSONArray?.matrixOrIdentity() = if (this == null) DMatrix.IDENTITY else DMatrix(DoubleArray(16) { getDouble(it) })

/** Decodes the embedded textured glTF meshes served by Google's live tiles endpoint. */
object GlbTileDecoder {
    fun decode(bytes: ByteArray, id: String, frame: GeoFrame, tileTransform: DMatrix): PhotoTile {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 28 || buffer.int != 0x46546c67 || buffer.int != 2 || buffer.int != bytes.size) throw IOException("Invalid city glTF header")
        val jsonLength = buffer.int
        if (buffer.int != 0x4e4f534a || jsonLength < 2 || jsonLength > bytes.size-28) throw IOException("Invalid city glTF JSON")
        val json = JSONObject(bytes.copyOfRange(20, 20 + jsonLength).toString(Charsets.UTF_8))
        buffer.position(20 + jsonLength)
        val binLength = buffer.int
        if (buffer.int != 0x004e4942 || binLength < 0 || binLength > buffer.remaining()) throw IOException("Invalid city glTF binary chunk")
        val binaryOffset = buffer.position()
        val views = json.getJSONArray("bufferViews")
        val accessors = json.getJSONArray("accessors")
        fun view(index: Int): JSONObject = views.getJSONObject(index).also {
            if (it.optInt("buffer", 0) != 0) throw IOException("External tile buffers are unsupported")
            val start = it.optInt("byteOffset", 0)
            val length = it.getInt("byteLength")
            if (start < 0 || length < 0 || start.toLong() + length > binLength) throw IOException("Invalid tile buffer bounds")
        }
        fun readAccessor(index: Int, components: Int): DoubleArray {
            val accessor = accessors.getJSONObject(index)
            if (accessor.has("sparse")) throw IOException("Sparse tile attributes are unsupported")
            val expected = when (components) { 1 -> "SCALAR"; 2 -> "VEC2"; else -> "VEC3" }
            if (accessor.getString("type") != expected) throw IOException("Invalid tile attribute type")
            val count = accessor.getInt("count")
            if (count !in 0..1_500_000) throw IOException("Tile vertex budget exceeded")
            val v = view(accessor.getInt("bufferView"))
            val type = accessor.getInt("componentType")
            val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw IOException("Unsupported tile component type") }
            val stride = v.optInt("byteStride", components * size)
            val offset = accessor.optInt("byteOffset", 0)
            if (offset < 0 || stride < components * size || (count > 0 && offset.toLong()+(count-1L)*stride+components*size > v.getInt("byteLength"))) throw IOException("Invalid tile accessor bounds")
            val start = binaryOffset + v.optInt("byteOffset", 0) + offset
            val normalized = accessor.optBoolean("normalized", false)
            return DoubleArray(count * components) { i ->
                val p = start + (i / components) * stride + (i % components) * size
                val value = when (type) {
                    5120 -> buffer.get(p).toDouble()
                    5121 -> (buffer.get(p).toInt() and 255).toDouble()
                    5122 -> buffer.getShort(p).toDouble()
                    5123 -> (buffer.getShort(p).toInt() and 65535).toDouble()
                    5125 -> (buffer.getInt(p).toLong() and 0xffffffffL).toDouble()
                    else -> buffer.getFloat(p).toDouble()
                }
                if (!normalized) value else when (type) {
                    5120 -> max(-1.0, value / 127.0)
                    5121 -> value / 255.0
                    5122 -> max(-1.0, value / 32767.0)
                    5123 -> value / 65535.0
                    else -> value
                }
            }
        }
        val primitives = mutableListOf<PhotoPrimitive>()
        val nodes = json.getJSONArray("nodes")
        val path = mutableSetOf<Int>()
        fun visit(index: Int, inherited: DMatrix) {
            if (!path.add(index) || path.size > 64) throw IOException("Invalid tile scene hierarchy")
            val node = nodes.getJSONObject(index)
            fun array(name: String, default: DoubleArray) = node.optJSONArray(name)?.let { a -> DoubleArray(default.size) { a.getDouble(it) } } ?: default
            val local = node.optJSONArray("matrix")?.matrixOrIdentity() ?: DMatrix.trs(
                array("translation", doubleArrayOf(0.0, 0.0, 0.0)),
                array("rotation", doubleArrayOf(0.0, 0.0, 0.0, 1.0)),
                array("scale", doubleArrayOf(1.0, 1.0, 1.0)),
            )
            val matrix = inherited * local
            if (node.has("mesh")) {
                val mesh = json.getJSONArray("meshes").getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
                for (i in 0 until mesh.length()) {
                    val primitive = mesh.getJSONObject(i)
                    if (primitive.optInt("mode", 4) != 4) throw IOException("Non-triangle city geometry")
                    if (primitive.optJSONObject("extensions")?.has("KHR_draco_mesh_compression") == true) throw IOException("Service returned compressed Draco tiles; decoder is required")
                    val attributes = primitive.getJSONObject("attributes")
                    val positions = readAccessor(attributes.getInt("POSITION"), 3)
                    val uv = readAccessor(attributes.getInt("TEXCOORD_0"), 2)
                    if (uv.size / 2 != positions.size / 3) throw IOException("Invalid tile UV count")
                    val vertices = (positions.indices step 3).map { p ->
                        val point = frame.local(matrix.point(DVec3(positions[p], positions[p+1], positions[p+2])))
                        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) throw IOException("Invalid tile position")
                        TileVertex(point.x, point.y, point.z, uv[p/3*2], uv[p/3*2+1])
                    }
                    val indices = if (primitive.has("indices")) readAccessor(primitive.getInt("indices"), 1).map { it.toInt() }.toIntArray() else IntArray(vertices.size) { it }
                    if (indices.size % 3 != 0 || indices.any { it !in vertices.indices }) throw IOException("Invalid tile indices")
                    val material = json.getJSONArray("materials").getJSONObject(primitive.getInt("material"))
                    val textureIndex = material.getJSONObject("pbrMetallicRoughness").getJSONObject("baseColorTexture").getInt("index")
                    val imageIndex = json.getJSONArray("textures").getJSONObject(textureIndex).getInt("source")
                    val image = json.getJSONArray("images").getJSONObject(imageIndex)
                    val imageView = view(image.getInt("bufferView"))
                    val imageStart = binaryOffset + imageView.optInt("byteOffset", 0)
                    primitives += PhotoPrimitive(vertices, indices, bytes.copyOfRange(imageStart, imageStart + imageView.getInt("byteLength")))
                }
            }
            node.optJSONArray("children")?.let { children -> for (i in 0 until children.length()) visit(children.getInt(i), matrix) }
            path.remove(index)
        }
        var transform = tileTransform
        json.optJSONObject("extensions")?.optJSONObject("CESIUM_RTC")?.getJSONArray("center")?.let { c ->
            transform *= DMatrix.trs(DoubleArray(3) { c.getDouble(it) }, doubleArrayOf(0.0,0.0,0.0,1.0), doubleArrayOf(1.0,1.0,1.0))
        }
        transform *= DMatrix.Y_UP_TO_Z_UP
        val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0)).getJSONArray("nodes")
        for (i in 0 until scene.length()) visit(scene.getInt(i), transform)
        if (primitives.isEmpty()) throw IOException("City tile contained no textured geometry")
        return PhotoTile(id, primitives, json.getJSONObject("asset").optString("copyright", "Google"))
    }
}
