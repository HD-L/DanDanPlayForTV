package tv.dandan.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import tv.dandan.core.AppSettings
import tv.dandan.core.DanmakuComment
import tv.dandan.core.DanmakuMode
import tv.dandan.core.DanmakuTimeline

/** 使用媒体毫秒时钟绘制，暂停与倍速无需额外墙钟补偿。 */
class DanmakuView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var timeline = DanmakuTimeline(emptyList())
    private var settings = AppSettings()
    private var positionMs = 0L
    private var shiftMs = 0L
    private data class Slot(val comment: DanmakuComment, val line: Int, val textWidth: Float)
    private val slots = LinkedHashMap<DanmakuComment, Slot>()

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun load(prepared: DanmakuTimeline, shift: Long = 0) {
        timeline = prepared
        shiftMs = shift
        slots.clear()
        invalidate()
    }
    fun update(position: Long, configuration: AppSettings) {
        if (position < positionMs || configuration != settings) slots.clear()
        positionMs = position
        settings = configuration
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!settings.danmakuEnabled || width <= 0 || height <= 0) { slots.clear(); return }
        val clock = (positionMs - shiftMs).coerceAtLeast(0)
        val visible = timeline.window(clock, settings)
        val active = visible.toHashSet()
        slots.keys.retainAll(active)
        paint.textSize = settings.danmakuSizeSp.coerceIn(10f, 72f) * resources.displayMetrics.scaledDensity
        val lineHeight = paint.fontSpacing * 1.2f
        val maxLines = settings.danmakuMaxLines.coerceIn(1, 30).coerceAtMost((height * .65f / lineHeight).toInt().coerceAtLeast(1))
        visible.take(DanmakuBudget.count(settings.danmakuMaxCount)).forEach { comment ->
            if (!slots.containsKey(comment)) {
                // 复用已完全离屏的行，密集输入超过区域预算时跳过。
                val bottom = comment.mode == DanmakuMode.BOTTOM
                val occupied = slots.values.filter { (it.comment.mode == DanmakuMode.BOTTOM) == bottom }.map { it.line }.toHashSet()
                val available = if (bottom) (maxLines / 3).coerceAtLeast(1) else (maxLines * 2 / 3).coerceAtLeast(1)
                val line = (0 until available).firstOrNull { it !in occupied }
                if (line != null) slots[comment] = Slot(comment, line, paint.measureText(comment.text))
            }
        }
        slots.values.forEach { slot ->
            val elapsed = (clock - slot.comment.timeMs).toFloat()
            val duration = settings.danmakuDurationMs.coerceIn(1000, 30_000).toFloat()
            if (elapsed !in 0f..duration) return@forEach
            val x = if (slot.comment.mode == DanmakuMode.SCROLL) width - (width + slot.textWidth) * (elapsed / duration) else (width - slot.textWidth) / 2
            val y = if (slot.comment.mode == DanmakuMode.BOTTOM) height - 36 * resources.displayMetrics.density - slot.line * lineHeight else 32 * resources.displayMetrics.density + (slot.line + 1) * lineHeight
            paint.alpha = (settings.danmakuAlpha.coerceIn(0f, 1f) * 255).toInt()
            if (settings.danmakuStroke > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = settings.danmakuStroke.coerceIn(0f, 8f) * resources.displayMetrics.density
                paint.color = android.graphics.Color.BLACK
                paint.alpha = (settings.danmakuAlpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawText(slot.comment.text, x, y, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = slot.comment.color
            paint.alpha = (settings.danmakuAlpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawText(slot.comment.text, x, y, paint)
        }
    }
}
