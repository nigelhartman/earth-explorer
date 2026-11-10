package com.example.earthexplorer.data

data class MeshCacheStats(val entries: Int, val bytes: Long, val budgetBytes: Long)

/** Leave heap space for decoding, textures and the rest of the application. */
internal fun meshTileCacheBudget(maxHeapBytes: Long): Long {
    require(maxHeapBytes > 0)
    return minOf(192L * 1024 * 1024, (maxHeapBytes / 3).coerceAtLeast(1))
}

/** Session-only compressed content; no persistent or offline provider tile cache. */
internal class MeshTileCache(private val budgetBytes: Long = 192L * 1024 * 1024) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var priorityById = emptyMap<String, Int>()
    private var bytes = 0L

    init { require(budgetBytes > 0) }

    @Synchronized
    fun get(id: String): ByteArray? = entries[id]

    @Synchronized
    fun contains(id: String): Boolean = id in entries

    @Synchronized
    fun put(id: String, content: ByteArray): Boolean {
        if (content.size > budgetBytes) return false
        val previousSize = entries.entries.firstOrNull { it.key == id }?.value?.size ?: 0
        var prospectiveBytes = bytes - previousSize + content.size
        val victims = mutableListOf<String>()
        if (prospectiveBytes > budgetBytes) {
            // Plan admission before changing the cache. A far-away tile that cannot fit
            // must not evict useful content and then get evicted itself.
            val candidates = entries.entries.filter { it.key != id }
                .map { it.key to it.value.size } + (id to content.size)
            for ((candidateId, size) in candidates.sortedByDescending {
                priorityById[it.first] ?: Int.MAX_VALUE
            }) {
                if (candidateId == id) return false
                victims += candidateId
                prospectiveBytes -= size
                if (prospectiveBytes <= budgetBytes) break
            }
        }
        victims.forEach { victim -> bytes -= requireNotNull(entries.remove(victim)).size }
        entries.remove(id)?.let { bytes -= it.size }
        entries[id] = content
        bytes += content.size
        return true
    }

    @Synchronized
    fun prefer(ids: Set<String>) { prioritize(ids.associateWith { 0 }) }

    /** Lower ranks survive longer; equal ranks retain least-recently-used eviction. */
    @Synchronized
    fun prioritize(priorityById: Map<String, Int>) {
        this.priorityById = priorityById.toMap()
    }

    @Synchronized
    fun stats(): MeshCacheStats = MeshCacheStats(entries.size, bytes, budgetBytes)

    @Synchronized
    fun clear() {
        entries.clear()
        priorityById = emptyMap()
        bytes = 0
    }
}
