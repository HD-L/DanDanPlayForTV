package tv.dandan.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 校验与关闭全部发生于提交前；原子移动成功后不再执行可能失败的校验。 */
internal object AtomicDiskFile {
    fun replace(
        target: File,
        bytes: ByteArray,
        writer: (File, ByteArray) -> Unit = ::writeAndSync,
        mover: (Path, Path) -> Unit = ::atomicMove,
    ) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("存储目录无效")
        if (!parent.isDirectory) throw IOException("存储目录不存在")
        val temporary = File.createTempFile(".${target.name}.", ".pending", parent)
        try {
            writer(temporary, bytes)
            if (temporary.length() != bytes.size.toLong() || !temporary.readBytes().contentEquals(bytes)) throw IOException("提交前存储校验失败")
            mover(temporary.toPath(), target.toPath())
        } finally { runCatching { temporary.delete() } }
    }
    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
    }
    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
