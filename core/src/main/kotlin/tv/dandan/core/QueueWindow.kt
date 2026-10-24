package tv.dandan.core

/** 控制跨 Activity 传递的队列体积，当前项目始终保留，顺序与原列表一致。 */
object QueueWindow {
    data class Selection(val entries: List<MediaEntry>, val truncated: Boolean)
    fun estimatedBytes(media: MediaEntry): Int = 256 + listOf(media.id, media.title, media.uri, media.sourceId, media.subtitleUri, media.danmakuUri).sumOf { it.toByteArray(Charsets.UTF_8).size }
    fun select(current: MediaEntry, queue: List<MediaEntry>, maxEntries: Int = 101, maxBytes: Int = 200 * 1024): Selection {
        require(maxEntries in 1..201 && maxBytes in 1024..200 * 1024) { "播放队列预算无效" }
        require(estimatedBytes(current) <= maxBytes) { "当前媒体信息超过播放队列预算，请缩短名称或地址" }
        val original = queue.filterNot { it.directory }.distinctBy { it.id }
        val position = original.indexOfFirst { it.id == current.id }
        if (position < 0) return Selection(listOf(current), original.isNotEmpty())
        val selected = sortedMapOf(position to current)
        var bytes = estimatedBytes(current)
        var left = true; var right = true
        for (distance in 1..100) {
            if (selected.size >= maxEntries || (!left && !right)) break
            for (index in listOf(position - distance, position + distance)) {
                val isLeft = index < position
                if ((isLeft && !left) || (!isLeft && !right)) continue
                if (index !in original.indices) { if (isLeft) left = false else right = false; continue }
                val entry = original[index]; val cost = estimatedBytes(entry)
                if (selected.size < maxEntries && bytes + cost <= maxBytes) { selected[index] = entry; bytes += cost }
                else if (isLeft) left = false else right = false
            }
        }
        return Selection(selected.values.toList(), selected.size < original.size)
    }
}
