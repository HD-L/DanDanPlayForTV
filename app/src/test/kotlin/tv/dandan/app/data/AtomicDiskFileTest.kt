package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class AtomicDiskFileTest {
    @Test fun writerCloseOrPreCommitValidationFailureLeavesOldFileUnchanged() {
        val directory = Files.createTempDirectory("tv-atomic-test").toFile()
        try {
            val target = java.io.File(directory, "store.json").apply { writeText("原始数据") }
            assertThrows(IOException::class.java) { AtomicDiskFile.replace(target, "新数据".toByteArray(), writer = { temp, _ -> temp.writeText("半写入"); throw IOException("关闭流失败") }) }
            assertEquals("原始数据", target.readText())
            assertThrows(IOException::class.java) { AtomicDiskFile.replace(target, "新数据".toByteArray(), writer = { temp, _ -> temp.writeText("内容错误") }) }
            assertEquals("原始数据", target.readText())
            assertEquals(listOf("store.json"), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
    @Test fun atomicMoveFailureKeepsOldFileAndSuccessfulCommitHasNoPostCommitRead() {
        val directory = Files.createTempDirectory("tv-atomic-test").toFile()
        try {
            val target = java.io.File(directory, "store.json").apply { writeText("原始数据") }
            assertThrows(IOException::class.java) { AtomicDiskFile.replace(target, "新数据".toByteArray(), mover = { _, _ -> throw IOException("提交失败") }) }
            assertEquals("原始数据", target.readText())
            AtomicDiskFile.replace(target, "全部新数据".toByteArray())
            assertEquals("全部新数据", target.readText())
            assertEquals(listOf("store.json"), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
}
