package tv.dandan.app.remote

import java.util.UUID

data class PhoneOption(val value: String, val label: String)
data class PhoneCondition(val key: String, val values: Set<String>)
data class PhoneField(
    val key: String,
    val label: String,
    val initialValue: String = "",
    val secret: Boolean = false,
    val multiline: Boolean = false,
    val required: Boolean = false,
    val maxLength: Int = 8192,
    val options: List<PhoneOption> = emptyList(),
    val showWhen: PhoneCondition? = null,
)
data class PhoneForm(val title: String, val hint: String = "", val fields: List<PhoneField>)
data class PublicPhoneField(val key: String, val label: String, val value: String, val secret: Boolean, val multiline: Boolean, val required: Boolean, val maxLength: Int, val hasStoredValue: Boolean, val options: List<PhoneOption>, val showWhen: PhoneCondition?)

class PhoneFormSession(
    val form: PhoneForm,
    val id: String = UUID.randomUUID().toString(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 600_000,
) {
    private val expires = nowMs() + ttlMs
    private var open = true
    init {
        require(ttlMs in 1..600_000 && form.title.isNotBlank() && form.title.length <= 200) { "手机表单定义无效" }
        require(form.fields.size in 1..16 && form.fields.map { it.key }.distinct().size == form.fields.size) { "手机表单字段定义无效" }
        form.fields.forEach { field ->
            require(field.key.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && field.label.isNotBlank() && field.label.length <= 200 && field.maxLength in 1..65536) { "手机表单字段定义无效" }
            require(field.options.size <= 32 && field.options.map { it.value }.distinct().size == field.options.size) { "手机表单选项无效" }
            require(field.options.isEmpty() || (!field.secret && !field.multiline && field.options.all { it.value.isNotBlank() && it.value.length <= field.maxLength && it.label.isNotBlank() && it.label.length <= 200 })) { "手机表单选项无效" }
            field.showWhen?.let { condition ->
                val parent = form.fields.firstOrNull { it.key == condition.key }
                require(parent != null && parent.key != field.key && parent.showWhen == null && parent.options.isNotEmpty() && condition.values.isNotEmpty() && condition.values.all { value -> parent.options.any { it.value == value } }) { "手机表单显示条件无效" }
            }
        }
    }
    fun fields(): List<PublicPhoneField> = form.fields.map { field ->
        PublicPhoneField(field.key, field.label, if (field.secret) "" else field.initialValue, field.secret, field.multiline, field.required, field.maxLength, field.secret && field.initialValue.isNotEmpty(), field.options, field.showWhen)
    }
    @Synchronized fun isOpen(): Boolean {
        if (nowMs() >= expires) open = false
        return open
    }
    @Synchronized fun cancel() { open = false }
    @Synchronized fun submit(requestId: String, values: Map<String, String>, clearSecrets: Set<String> = emptySet(), validator: (Map<String, String>) -> String? = { null }): Map<String, String> {
        require(requestId == id && isOpen()) { "这次输入已取消、过期或完成，请在电视上重新打开" }
        val keys = form.fields.map { it.key }.toSet()
        require(values.keys.all { it in keys } && clearSecrets.all { key -> form.fields.any { it.key == key && it.secret } }) { "表单含未知字段" }
        val typedValues = form.fields.associate { field ->
            val typed = values[field.key].orEmpty()
            val value = if (field.secret && typed.isEmpty() && field.key !in clearSecrets) field.initialValue else typed
            field.key to value
        }
        val normalized = form.fields.associate { field ->
            val visible = field.showWhen?.let { typedValues[it.key] in it.values } ?: true
            val value = if (visible) typedValues.getValue(field.key) else ""
            require(value.length <= field.maxLength && '\u0000' !in value) { "${field.label}长度超出范围" }
            require(!visible || !field.required || value.isNotBlank()) { "请填写${field.label}" }
            require(!visible || field.options.isEmpty() || field.options.any { it.value == value }) { "请选择有效的${field.label}" }
            field.key to value
        }
        val error = validator(normalized)
        require(error == null) { error.orEmpty().take(300) }
        open = false
        return normalized
    }
}
