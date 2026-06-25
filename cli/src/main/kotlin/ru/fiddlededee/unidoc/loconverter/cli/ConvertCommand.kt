package ru.fiddlededee.unidoc.loconverter.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import ru.fiddlededee.unidoc.loconverter.core.DirectLibreOfficeProvider
import ru.fiddlededee.unidoc.loconverter.core.DocumentConverter
import ru.fiddlededee.unidoc.loconverter.core.DocumentFormat
import java.io.File

class ConvertCommand : CliktCommand(name = "convert") {
    override fun help(context: Context) = """
        Converts a document to the specified format.
        
        Converts the input document file to the output format,
        updating all fields and indexes in the process.
        
        The output file is saved in the same directory as the input file,
        with the extension changed to match the output format.
        If the output format matches the input format, use --add-suffix
        to append "_converted" to the filename.
    """.trimIndent()

    val inputFiles: List<File> by argument("INPUT_FILES", help = "Path to the input document(s)").file(
        mustExist = false,
        canBeFile = true,
        canBeDir = false,
    ).multiple(required = true)

    val formats: List<DocumentFormat> by option("--format", "-f", help = "Output format (docx, pdf, odt, fodt, html). Can be specified multiple times.")
        .convert { DocumentFormat.fromExtension(it.trim()) }
        .multiple(required = false)

    val host: String by option("--host", help = "LibreOffice host")
        .default("127.0.0.1")

    val port: Int by option("--port", help = "LibreOffice port")
        .int()
        .default(2002)

    val startLocal: Boolean by option("--start-local", help = "Start local LibreOffice instance")
        .flag()

    val sofficeCmd: String by option("--soffice", help = "Path to soffice executable")
        .default("soffice")

    val addSuffix: Boolean by option(
        "--add-suffix",
        help = "Add _converted suffix when input and output formats are the same"
    ).flag()

    override fun run() {
        val provider = DirectLibreOfficeProvider(
            host = host,
            port = port,
            startLocal = startLocal,
            sofficeCmd = sofficeCmd
        )

        provider.withSession {
            val converter = DocumentConverter(this)

            for (inputFile in inputFiles) {
                println(inputFile)
                val inputExt = inputFile.extension.lowercase()

                for (format in formats) {
                    val outputExt = format.extension
                    val outputFile = if (inputExt == outputExt && addSuffix) {
                        val baseName = inputFile.nameWithoutExtension
                        File(inputFile.parentFile, "${baseName}_converted.$outputExt")
                    } else {
                        File(inputFile.parentFile, "${inputFile.nameWithoutExtension}.$outputExt")
                    }

                    converter.convert(inputFile, outputFile, format)
                    echo("Successfully converted ${inputFile.name} -> ${outputFile.name} ($format)")
                }
            }
        }
    }
}