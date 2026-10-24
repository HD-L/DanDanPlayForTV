package tv.dandan.app.ui

object AnimeSearchRules {
    fun validate(keyword: String): String? = if (keyword.trim().length !in 2..200) "搜索关键词需要2至200个字符" else null
}
