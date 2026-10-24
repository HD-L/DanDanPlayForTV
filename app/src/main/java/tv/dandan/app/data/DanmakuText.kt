package tv.dandan.app.data

import com.github.houbb.opencc4j.util.ZhConverterUtil
import tv.dandan.core.DanmakuComment
import tv.dandan.core.DanmakuLanguage

object DanmakuText {
    fun convert(comments: List<DanmakuComment>, language: DanmakuLanguage): List<DanmakuComment> {
        if (language == DanmakuLanguage.ORIGINAL) return comments
        return comments.map { comment -> comment.copy(text = when (language) {
            DanmakuLanguage.SIMPLIFIED -> ZhConverterUtil.toSimple(comment.text)
            DanmakuLanguage.TRADITIONAL -> ZhConverterUtil.toTraditional(comment.text)
            else -> comment.text
        }) }
    }
}
