package ru.fiddlededee.unidoc.loconverter.core

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class DocumentConverterTest {

    private lateinit var session: LibreOfficeSession
    private lateinit var component: com.sun.star.lang.XComponent
    private lateinit var converter: DocumentConverter

    @BeforeEach
    fun setUp() {
        session = mockk()
        component = mockk(relaxed = true)
        converter = DocumentConverter(session)

        // Для Kotlin object используем mockkObject, а не mockkStatic
        mockkObject(FileUtils)
        every { FileUtils.toFileUrl(any()) } answers { "file://${firstArg<String>()}" }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `convert calls load, save and dispose in correct order`() {
        val inputFile = File("input.docx")
        val outputFile = File("output.pdf")
        val outputFormat = mockk<DocumentFormat>()
        every { outputFormat.extension } returns "pdf"

        every { session.loadComponent(any()) } returns component
        every { session.saveComponent(any(), any(), any()) } just Runs

        converter.convert(inputFile, outputFile, outputFormat)

        verifyOrder {
            session.loadComponent(any())
            session.saveComponent(component, any(), any())
            component.dispose()
        }
    }

    @Test
    fun `dispose is called even when saveComponent throws an exception`() {
        val inputFile = File("input.docx")
        val outputFile = File("output.pdf")
        val outputFormat = mockk<DocumentFormat>()
        every { outputFormat.extension } returns "pdf"

        every { session.loadComponent(any()) } returns component
        every { session.saveComponent(any(), any(), any()) } throws RuntimeException("IO Error")

        val exception = assertThrows<RuntimeException> {
            converter.convert(inputFile, outputFile, outputFormat)
        }
        assertEquals("IO Error", exception.message)

        verifyOrder {
            session.loadComponent(any())
            session.saveComponent(component, any(), any())
            component.dispose()
        }
    }
}
