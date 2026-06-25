package ru.fiddlededee.unidoc.loconverter.core

enum class DocumentFormat(val extension: String) {
    DOCX("docx"),
    PDF("pdf"),
    ODT("odt"),
    FODT("fodt"),
    HTML("html");

    companion object {
        fun fromExtension(ext: String): DocumentFormat =
            entries.find { it.extension == ext.lowercase() }
                ?: throw IllegalArgumentException("Unsupported format: $ext")
    }
}