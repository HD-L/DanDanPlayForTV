package tv.dandan.core

import java.util.Locale

object LibraryRules {
    val naturalOrder: Comparator<MediaEntry> = Comparator { a, b ->
        if (a.directory != b.directory) if (a.directory) -1 else 1
        else naturalCompare(a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id)
    }
    /** 空格分词均需命中；排除词只对文件生效，目录仍可进入。 */
    fun visible(entries: List<MediaEntry>, query: String = "", excludedWords: String = ""): List<MediaEntry> {
        require(query.length <= 200 && excludedWords.length <= 1000) { "搜索或过滤词过长" }
        val wanted = words(query)
        val excluded = words(excludedWords)
        return entries.filter { entry ->
            val name = entry.title.lowercase(Locale.ROOT)
            wanted.all { it in name } && (entry.directory || excluded.none { it in name })
        }.sortedWith(naturalOrder)
    }
    private fun words(text: String) = text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun naturalCompare(first: String, second: String): Int {
        val a = first.lowercase(Locale.ROOT); val b = second.lowercase(Locale.ROOT)
        var ai = 0; var bi = 0
        while (ai < a.length && bi < b.length) {
            if (a[ai] in '0'..'9' && b[bi] in '0'..'9') {
                val as0 = ai; val bs0 = bi
                while (ai < a.length && a[ai] in '0'..'9') ai++
                while (bi < b.length && b[bi] in '0'..'9') bi++
                val an = a.substring(as0, ai).trimStart('0').ifEmpty { "0" }
                val bn = b.substring(bs0, bi).trimStart('0').ifEmpty { "0" }
                val order = an.length.compareTo(bn.length).takeIf { it != 0 } ?: an.compareTo(bn)
                if (order != 0) return order
            } else {
                val order = a[ai].compareTo(b[bi]); if (order != 0) return order
                ai++; bi++
            }
        }
        return (a.length - ai).compareTo(b.length - bi)
    }
}
