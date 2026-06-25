package ru.fiddlededee.unidoc.loconverter.core

import java.io.File

fun LibreOfficeSession.convert(
    inputFile: File,
    outputFile: File,
    outputFormat: DocumentFormat
) {
    DocumentConverter(this).convert(inputFile, outputFile, outputFormat)
}