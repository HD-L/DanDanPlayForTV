package tv.dandan.app.ui

import org.junit.Assert.*
import org.junit.Test

class AnimeSearchRulesTest {
    @Test fun tooShortOrLongQueriesRemainInThePhoneForm() {
        assertNotNull(AnimeSearchRules.validate(" "))
        assertNotNull(AnimeSearchRules.validate(" 番 "))
        assertNotNull(AnimeSearchRules.validate("字".repeat(201)))
    }

    @Test fun trimmingKeepsBothValidSearchBoundaries() {
        assertNull(AnimeSearchRules.validate(" 番剧 "))
        assertNull(AnimeSearchRules.validate("字".repeat(200)))
    }
}
