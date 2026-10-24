package tv.dandan.core

/** 首页返回键：按一次只提示，提示期间再按一次才退出应用，避免误触。 */
object ExitRules {
    /** 两次返回之间允许的最长间隔，和提示的显示时长相当。 */
    const val WINDOW_MS = 3_000L

    /** [armedAtMs] 是上一次在首页按返回的时刻（单调时钟毫秒，0 表示还没按过）。 */
    fun shouldExit(armedAtMs: Long, nowMs: Long): Boolean = armedAtMs > 0 && nowMs - armedAtMs in 0..WINDOW_MS
}
