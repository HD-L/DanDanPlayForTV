package tv.dandan.app.ui

import tv.dandan.app.data.SourceRules
import tv.dandan.app.remote.PhoneCondition
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.remote.PhoneOption
import tv.dandan.core.MediaSource
import tv.dandan.core.SourceKind

fun SourceKind.chinese() = when (this) {
    SourceKind.DOCUMENT_TREE -> "本地授权目录"
    SourceKind.HTTP -> "HTTP 视频"
    SourceKind.WEBDAV -> "WebDAV"
    SourceKind.SMB -> "SMB"
    SourceKind.FTP -> "FTP"
}

object SourceFormRules {
    private val authenticated = setOf(SourceKind.WEBDAV, SourceKind.SMB, SourceKind.FTP)
    fun form(original: MediaSource? = null): PhoneForm = PhoneForm(
        if (original == null) "添加媒体源" else "修改媒体源",
        "选择类型并填写全部信息，提交后电视保存并刷新媒体库。本地目录需在电视系统授权，手机不能代替授权。地址不能内嵌账号密码；SMB 地址需要共享名。",
        listOf(
            PhoneField("kind", "来源类型", (original?.kind ?: SourceKind.DOCUMENT_TREE).name, required = true,
                options = SourceKind.entries.map { PhoneOption(it.name, it.chinese()) }),
            PhoneField("name", "显示名称", original?.name.orEmpty(), required = true, maxLength = 200),
            PhoneField("location", "连接地址（HTTP / WebDAV / SMB / FTP）", original?.location.takeIf { original?.kind != SourceKind.DOCUMENT_TREE }.orEmpty(),
                required = true, showWhen = PhoneCondition("kind", SourceKind.entries.filter { it != SourceKind.DOCUMENT_TREE }.map { it.name }.toSet())),
            PhoneField("username", "账号（可留空）", original?.username.orEmpty(), maxLength = 200,
                showWhen = PhoneCondition("kind", authenticated.map { it.name }.toSet())),
            PhoneField("password", "密码（留空保留已存密码）", original?.password.orEmpty(), secret = true, maxLength = 4096,
                showWhen = PhoneCondition("kind", authenticated.map { it.name }.toSet())),
        ),
    )
    fun candidate(values: Map<String, String>, original: MediaSource? = null, newId: String = "new-source"): MediaSource {
        val kind = SourceKind.entries.firstOrNull { it.name == values["kind"] } ?: throw IllegalArgumentException("请选择有效的来源类型")
        val name = values["name"].orEmpty().trim()
        require(name.isNotBlank() && name.length <= 200) { "请输入不超过200字符的来源名称" }
        val source = MediaSource(original?.id ?: newId, name, kind,
            if (kind == SourceKind.DOCUMENT_TREE) original?.location.takeIf { original?.kind == SourceKind.DOCUMENT_TREE }.orEmpty() else values["location"].orEmpty().trim(),
            if (kind in authenticated) values["username"].orEmpty() else "",
            if (kind in authenticated) values["password"].orEmpty() else "")
        return if (kind == SourceKind.DOCUMENT_TREE) source else SourceRules.validate(source)
    }
    fun validate(values: Map<String, String>, original: MediaSource? = null): String? =
        runCatching { candidate(values, original); null }.getOrElse { it.message ?: "来源配置无效，请检查类型、地址与账号" }

    fun withDirectory(source: MediaSource, directoryUri: String): MediaSource {
        require(source.kind == SourceKind.DOCUMENT_TREE) { "此来源不使用系统目录授权" }
        return SourceRules.validate(source.copy(location = directoryUri, username = "", password = ""))
    }
}
