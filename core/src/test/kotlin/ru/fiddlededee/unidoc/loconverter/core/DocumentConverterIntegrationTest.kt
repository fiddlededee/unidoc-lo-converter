package ru.fiddlededee.unidoc.loconverter.core

import converter.fodt.FodtConverter
import model.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "RUN_LO_INTEGRATION_TESTS", matches = "true")
class DocumentConverterIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `updates fields`() {
        val inputFile = File("src/test/test-data/fields-update.fodt")
        val outputFile = File(tempDir, "fields-update-result.fodt")
        DirectLibreOfficeProvider(startLocal = true).withSession {
            convert(inputFile, outputFile, DocumentFormat.FODT)
        }
        assertTrue(
            outputFile.readText().contains(">2<"),
            "File contains one page, so page + 1 should be equal to 2"
        )
    }

    @Test
    fun `converts fodt to pdf`() {
        val inputFile = createSampleFodt(tempDir)
        assertTrue(File(tempDir, "sample.fodt").exists())
        val outputFile = File(tempDir, "output.pdf")

        DirectLibreOfficeProvider(startLocal = true).withSession {
            convert(inputFile, outputFile, DocumentFormat.PDF)
        }

        assertTrue(outputFile.exists(), "PDF file should be created")
        assertTrue(outputFile.length() > 0, "PDF file should not be empty")
    }

    private fun createSampleFodt(dir: File): File {
        val templateFile = File("src/test/resources/template.fodt")
        require(templateFile.exists()) { "Template file not found: ${templateFile.absolutePath}. Place a .fodt template there." }

        val ast = Document().apply {
            p { +"Integration test document" }
            p { +"Created by unidoc-publisher" }
        }

        val fodtContent = FodtConverter {
            this.ast = ast
            template = templateFile.readText()
            ast2fodt()
        }.fodt()

        val fodtFile = File(dir, "sample.fodt")
        fodtFile.writeText(fodtContent)
        return fodtFile
    }
}