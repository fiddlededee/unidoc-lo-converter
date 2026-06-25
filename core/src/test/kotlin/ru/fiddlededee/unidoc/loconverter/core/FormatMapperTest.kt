package ru.fiddlededee.unidoc.loconverter.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FormatMapperTest {

    @Test
    fun `handles case insensitivity`() {
        assertEquals("writer_pdf_Export", FormatMapper.filterNameByExtension("PDF"))
    }

    @Test
    fun `throws on unsupported extension`() {
        assertThrows<IllegalArgumentException> { FormatMapper.filterNameByExtension("txt") }
    }
}
