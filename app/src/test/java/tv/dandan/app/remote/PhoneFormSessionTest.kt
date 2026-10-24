package tv.dandan.app.remote

import org.junit.Assert.*
import org.junit.Test

class PhoneFormSessionTest {
    private fun form() = PhoneForm("来源", fields = listOf(PhoneField("name", "名称", "旧来源", required = true, maxLength = 20), PhoneField("password", "密码", "原密码", secret = true)))
    @Test fun storedSecretNeverAppearsInPublicForm() {
        val fields = PhoneFormSession(form()).fields()
        assertEquals("旧来源", fields[0].value)
        assertEquals("", fields[1].value)
        assertTrue(fields[1].hasStoredValue)
    }
    @Test fun blankPasswordPreservesButExplicitClearRemovesIt() {
        val first = PhoneFormSession(form())
        assertEquals("原密码",first.submit(first.id,mapOf("name" to "新来源", "password" to ""))["password"])
        val second = PhoneFormSession(form())
        assertEquals("",second.submit(second.id,mapOf("name" to "新来源"), setOf("password"))["password"])
    }
    @Test fun successfulInputIsConsumedOnce() {
        val session = PhoneFormSession(form())
        session.submit(session.id,mapOf("name" to "新来源"))
        reject { session.submit(session.id,mapOf("name" to "迟到")) }
        assertFalse(session.isOpen())
    }
    @Test fun cancelledAndExpiredInputsCannotApplyToNewPage() {
        val cancelled = PhoneFormSession(form()); cancelled.cancel()
        reject { cancelled.submit(cancelled.id,mapOf("name" to "迟到")) }
        var clock = 0L
        val expired = PhoneFormSession(form(),nowMs = { clock },ttlMs = 100)
        clock = 100
        reject { expired.submit(expired.id,mapOf("name" to "过期")) }
        assertFalse(expired.isOpen())
    }
    @Test fun wrongIdAndUnknownFieldsAreRejectedWithoutConsumingForm() {
        val s = PhoneFormSession(form())
        reject { s.submit("其他请求",mapOf("name" to "新")) }
        reject { s.submit(s.id,mapOf("name" to "新", "unexpected" to "内容")) }
        assertTrue(s.isOpen())
    }
    @Test fun fieldAndBusinessErrorsKeepFormOpen() {
        val s = PhoneFormSession(form())
        reject { s.submit(s.id,mapOf("name" to "")) }
        reject { s.submit(s.id,mapOf("name" to "x".repeat(21))) }
        reject { s.submit(s.id,mapOf("name" to "无效"),validator = { "地址无效" }) }
        assertTrue(s.isOpen())
        assertEquals("有效",s.submit(s.id,mapOf("name" to "有效"))["name"])
    }
    private fun sourceForm() = PhoneForm("媒体库", fields = listOf(
        PhoneField("kind", "类型", "LOCAL", required = true, options = listOf(PhoneOption("LOCAL", "本地"), PhoneOption("DAV", "WebDAV"), PhoneOption("HTTP", "视频链接"))),
        PhoneField("location", "地址", required = true, showWhen = PhoneCondition("kind", setOf("DAV", "HTTP"))),
        PhoneField("password", "密码", "已存密码", secret = true, showWhen = PhoneCondition("kind", setOf("DAV"))),
    ))
    @Test fun selectedTypeIsValidatedBeforeConsumingForm() {
        val s = PhoneFormSession(sourceForm())
        reject { s.submit(s.id, mapOf("kind" to "UNKNOWN", "location" to "http://test")) }
        assertTrue(s.isOpen())
        assertEquals("HTTP", s.submit(s.id, mapOf("kind" to "HTTP", "location" to "http://test"))["kind"])
    }
    @Test fun hiddenRequiredFieldsAreNotNeededAndSecretsDoNotCrossTypes() {
        val s = PhoneFormSession(sourceForm())
        val local = s.submit(s.id, mapOf("kind" to "LOCAL"))
        assertEquals("", local["location"])
        assertEquals("", local["password"])
        val http = PhoneFormSession(sourceForm())
        assertEquals("", http.submit(http.id, mapOf("kind" to "HTTP", "location" to "http://test"))["password"])
    }
    @Test fun visibleConditionalFieldsKeepTheirValidationAndStoredSecret() {
        val s = PhoneFormSession(sourceForm())
        reject { s.submit(s.id, mapOf("kind" to "DAV")) }
        assertTrue(s.isOpen())
        assertEquals("已存密码", s.submit(s.id, mapOf("kind" to "DAV", "location" to "http://test"))["password"])
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("应拒绝无效或迟到输入") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.isNullOrBlank()) }
    }
}
