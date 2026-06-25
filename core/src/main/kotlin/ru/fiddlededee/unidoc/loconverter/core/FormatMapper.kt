package ru.fiddlededee.unidoc.loconverter.core

object FormatMapper {

    private val FORMATS = mapOf(
        "docx" to "Office Open XML Text",
        "pdf" to "writer_pdf_Export",
        "odt" to "writer8",
        "fodt" to "OpenDocument Text Flat XML",
        "html" to "HTML (StarWriter)"
    )

    fun filterNameByExtension(extension: String): String {
        return FORMATS[extension.lowercase()]
            ?: throw IllegalArgumentException("Output format [$extension] not supported")
    }
}
