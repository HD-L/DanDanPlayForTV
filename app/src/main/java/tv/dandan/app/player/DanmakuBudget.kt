package tv.dandan.app.player

object DanmakuBudget {
    fun count(configured: Int): Int = if (configured <= 0) 300 else configured.coerceAtMost(300)
}
