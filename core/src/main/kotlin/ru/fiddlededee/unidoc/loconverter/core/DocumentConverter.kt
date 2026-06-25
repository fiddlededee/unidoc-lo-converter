package ru.fiddlededee.unidoc.loconverter.core

import com.sun.star.lang.XComponent
import com.sun.star.text.XDocumentIndexesSupplier
import com.sun.star.uno.UnoRuntime
import com.sun.star.util.XRefreshable
import mu.KotlinLogging
import java.io.File


/**
 * Converts documents via LibreOffice.
 */
class DocumentConverter(
    private val session: LibreOfficeSession
) {

    private val logger = KotlinLogging.logger {}

    fun convert(inputFile: File, outputFile: File, outputFormat: DocumentFormat) {
        val inputUrl = FileUtils.toFileUrl(inputFile.path)
        val outputUrl = FileUtils.toFileUrl(outputFile.path)
        val filterName = FormatMapper.filterNameByExtension(outputFormat.extension)

        logger.info("Converting ${inputFile.name} -> ${outputFile.name} (filter=$filterName)")

        var component: XComponent? = null
        try {
            component = session.loadComponent(inputUrl)

            updateIndexes(component)
            updateFields(component)

            session.saveComponent(component, outputUrl, filterName)
            logger.info("Conversion completed: ${outputFile.name}")
        } catch (e: Exception) {
            logger.error("Conversion failed: ${e.message}")
            throw e
        } finally {
            component?.dispose()
        }
    }

    private fun updateIndexes(component: XComponent) {
        val indexesSupplier = UnoRuntime.queryInterface(
            XDocumentIndexesSupplier::class.java, component
        ) ?: return
        logger.info("Updating document indexes")
        val indexes = indexesSupplier.documentIndexes
        for (i in 0 until indexes.count) {
            val index = UnoRuntime.queryInterface(com.sun.star.text.XDocumentIndex::class.java, indexes.getByIndex(i))
            index?.update()
        }
    }

    private fun updateFields(component: XComponent) {
        val refreshable = UnoRuntime.queryInterface(
            XRefreshable::class.java, component
        )
        if (refreshable != null) {
            logger.info("Updating fields via XRefreshable")
            refreshable.refresh()
        }

        runCatching {
            val dispatcher = session.createDispatchHelper()
            val dispatchProvider = session.getComponentDispatchProvider(component)
            logger.info("Updating fields via dispatch .uno:UpdateFields")
            dispatcher.executeDispatch(
                dispatchProvider,
                ".uno:UpdateFields",
                "",
                0,
                emptyArray()
            )
        }.onFailure {
            logger.warn("Dispatch update failed (non-critical): ${it.message}")
        }
    }
}