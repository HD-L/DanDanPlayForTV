package tv.dandan.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.app.data.SettingsRules
import tv.dandan.core.AppSettings

class BlockedFormRulesTest {
    @Test fun a201CharacterKeywordIsRejectedBeforeThePhoneSessionIsConsumed() {
        assertNotNull(BlockedFormRules.validate(AppSettings(), keywordText = "字".repeat(201)))
    }

    @Test fun moreThan200CompleteKeywordsIsRejectedWithoutTruncation() {
        val text = (1..201).joinToString("\n") { "关键词$it" }
        assertNotNull(BlockedFormRules.validate(AppSettings(), keywordText = text))
        assertThrows(IllegalArgumentException::class.java) { BlockedFormRules.apply(AppSettings(), keywordText = text) }
    }

    @Test fun all200ValidKeywordsAnd100PatternsReachTheActualSaveContract() {
        val value = BlockedFormRules.apply(AppSettings(subtitleDelayMs = 1750),
            keywordText = (1..200).joinToString("\n") { "关键词$it" }, patternText = (1..100).joinToString("\n") { "正则$it" })
        assertEquals(200, value.blockedKeywords.size)
        assertEquals(100, value.blockedPatterns.size)
        assertEquals("关键词200", value.blockedKeywords.last())
        assertEquals(1750, value.subtitleDelayMs)
        SettingsRules.validate(value)
    }

    @Test fun exactly200CharactersIsAcceptedAndInvalidRegexStillFails() {
        val value = BlockedFormRules.apply(AppSettings(), keywordText = "字".repeat(200))
        assertEquals(200, value.blockedKeywords.single().length)
        SettingsRules.validate(value)
        assertNotNull(BlockedFormRules.validate(AppSettings(), patternText = "("))
    }

    @Test fun regexCountBudgetUsesTheWholeSubmittedText() {
        assertNotNull(BlockedFormRules.validate(AppSettings(), patternText = (1..101).joinToString("\n") { "规则$it" }))
    }

    @Test fun validatorUsesTheSameSettingsRulesAsPersistence() {
        assertNotNull(BlockedFormRules.validate(AppSettings(danmakuSizeSp = 100f), keywordText = "有效关键字"))
    }
}
