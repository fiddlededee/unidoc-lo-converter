package ru.fiddlededee.unidoc.loconverter.core

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileUtilsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `toFileUrl converts existing file to valid URL`() {
        val testFile = File("$tempDir/test.DOCX")
        val url = FileUtils.toFileUrl(testFile.absolutePath)
        assertAll(
            { assertTrue(url.startsWith("file:///")) },
            { assertTrue(url.endsWith("test.DOCX")) }
        )
    }


    @Test
    fun `Correctly throws on different cases`() {
        assertThrows<IllegalArgumentException> { FileUtils.toFileUrl("") }
    }

    @Test
    fun `getExtension returns correct lowercase extension`() {
        assertAll(
            { assertEquals("docx", FileUtils.getExtension("path/to/report.DOCX")) },
            { assertEquals("pdf", FileUtils.getExtension("file.PDF")) },
            { assertEquals("", FileUtils.getExtension("no-extension")) },
            { assertEquals("", FileUtils.getExtension(".hidden")) }
        )
    }

}