package tv.dandan.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitRulesTest {
    @Test fun secondBackWithinWindowExits() {
        // 第一次按返回只提示
        assertFalse(ExitRules.shouldExit(0, 10_000))
        // 提示期间再按一次退出
        assertTrue(ExitRules.shouldExit(10_000, 11_500))
        assertTrue(ExitRules.shouldExit(10_000, 10_000 + ExitRules.WINDOW_MS))
        // 隔太久重新开始计时；时钟异常时不退出
        assertFalse(ExitRules.shouldExit(10_000, 10_001 + ExitRules.WINDOW_MS))
        assertFalse(ExitRules.shouldExit(10_000, 9_000))
    }
}
