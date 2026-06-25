package ru.fiddlededee.unidoc.pdfcompare

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.triple
import com.github.ajalt.clikt.parameters.types.path
import de.redsix.pdfcompare.CompareResultImpl
import de.redsix.pdfcompare.PdfComparator
import java.nio.file.Path

class CompareCommand : CliktCommand(
    name = "unidoc-pdf-compare",
) {
    override fun help(context: Context) = """
        Compare PDF files and output diff
    """.trimIndent()
    private val compares by option("--compare", help = "Approved PDF, received PDF, diff output PDF")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .triple()
        .multiple()

    override fun run() {
        if (compares.isEmpty()) {
            echo("No --compare triples provided.")
            return
        }

        compares.forEach { (approved, received, diff) ->
            echo("Comparing $approved vs $received -> $diff")
            comparePdfFiles(approved, received, diff)
        }
    }

    private fun comparePdfFiles(approved: Path, received: Path, diffOutput: Path) {
        val outputWithoutExtension = diffOutput.toString().removeSuffix(".pdf")
        PdfComparator<CompareResultImpl>(approved.toString(), received.toString())
            .compare()
            .apply {
                if (isNotEqual) {
                    writeTo(outputWithoutExtension)
                    echo("  [DIFF] Differences found, diff written to $diffOutput")
                } else {
                    echo("  [OK] PDFs are identical")
                }
            }
    }
}