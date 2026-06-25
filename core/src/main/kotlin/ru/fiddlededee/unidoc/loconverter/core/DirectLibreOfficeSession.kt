package ru.fiddlededee.unidoc.loconverter.core

import com.sun.star.bridge.XBridgeFactory
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.connection.XConnection
import com.sun.star.connection.XConnector
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.uno.XComponentContext
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XDispatchHelper
import com.sun.star.frame.XDispatchProvider
import com.sun.star.frame.XStorable
import com.sun.star.uno.UnoRuntime
import java.util.logging.Logger
import kotlin.concurrent.thread

inline fun <reified T> Any.query(): T =
    UnoRuntime.queryInterface(T::class.java, this)
        ?: throw IllegalStateException("Failed to get ${T::class.simpleName}")

/**
 * Manages a direct UNO connection to a running LibreOffice instance.
 */
class DirectLibreOfficeSession(
    host: String,
    port: Int,
    private val connectRetries: Int = 10,
    private val connectRetryDelayMs: Long = 500,
    private val process: Process? = null,
) : LibreOfficeSession {

    private val logger = Logger.getLogger(DirectLibreOfficeSession::class.java.name)

    private val context: XComponentContext
    private val connection: XConnection
    private val remoteOffice: XMultiComponentFactory

    init {
        val localContext = Bootstrap.createInitialComponentContext(null)

        val connectorObj = localContext.serviceManager.createInstanceWithContext(
            "com.sun.star.connection.Connector", localContext
        )
        val connector: XConnector = connectorObj.query()

        val connectString = "socket,host=$host,port=$port,tcpNoDelay=1"

        var lastException: Exception? = null
        var attempt = 0
        connection = run {
            var connectionLocal: XConnection? = null
            while (attempt < connectRetries) {
                attempt++
                try {
                    connectionLocal = connector.connect(connectString)
                    logger.info("Successfully connected to LibreOffice on attempt $attempt/$connectRetries")
                    break
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < connectRetries) {
                        logger.fine("Connection attempt $attempt/$connectRetries failed: ${e.message}. Retrying in ${connectRetryDelayMs}ms...")
                        Thread.sleep(connectRetryDelayMs)
                    }
                }
            }

            if (connectionLocal == null) {
                throw java.net.ConnectException(
                    "Failed to connect to LibreOffice at $host:$port after $connectRetries attempts. " +
                            "Last error: ${lastException?.message}"
                )
            }
            connectionLocal
        }

        val bridgeFactoryObj = localContext.serviceManager.createInstanceWithContext(
            "com.sun.star.bridge.BridgeFactory", localContext
        )
        val bridgeFactory: XBridgeFactory = bridgeFactoryObj.query()

        val bridge = bridgeFactory.createBridge(
            "",
            "urp",
            connection,
            null
        )

        val serviceManagerObj = bridge.getInstance("StarOffice.ServiceManager")
        remoteOffice = serviceManagerObj.query()
        val props = remoteOffice.query<com.sun.star.beans.XPropertySet>()
        val defaultContext = props.getPropertyValue("DefaultContext")
        context = defaultContext.query()
    }

    /**
     * Loads a document from the specified URL into LibreOffice.
     */
    override fun loadComponent(inputUrl: String): XComponent {
        require(inputUrl.isNotBlank()) { "Input URL must not be blank" }

        val desktopObj = remoteOffice.createInstanceWithContext(
            "com.sun.star.frame.Desktop",
            context
        )
        val desktop: XComponentLoader = desktopObj.query()

        val props = arrayOf(
            com.sun.star.beans.PropertyValue().apply {
                Name = "Hidden"
                Value = true
            }
        )

        return desktop.loadComponentFromURL(inputUrl, "_blank", 0, props)
    }

    /**
     * Saves the loaded component to the specified URL using the given filter.
     */
    override fun saveComponent(component: XComponent, outputUrl: String, filterName: String) {
        require(outputUrl.isNotBlank()) { "Output URL must not be blank" }
        require(filterName.isNotBlank()) { "Filter name must not be blank" }

        val store: XStorable = component.query()

        val props = arrayOf(
            com.sun.star.beans.PropertyValue().apply { Name = "FilterName"; Value = filterName },
            com.sun.star.beans.PropertyValue().apply { Name = "Overwrite"; Value = true }
        )

        store.storeToURL(outputUrl, props)
    }

    /**
     * Creates a dispatch helper for executing UNO commands.
     */
    override fun createDispatchHelper(): XDispatchHelper {
        val dispatchHelperObj = remoteOffice.createInstanceWithContext(
            "com.sun.star.frame.DispatchHelper",
            context
        )
        val dispatchHelper: XDispatchHelper = dispatchHelperObj.query()
        return dispatchHelper
    }

    /**
     * Gets XDispatchProvider from a loaded component's controller frame.
     * This works reliably in both headless and GUI modes.
     */
    override fun getComponentDispatchProvider(component: XComponent): XDispatchProvider {
        val model = UnoRuntime.queryInterface(
            com.sun.star.frame.XModel::class.java, component
        )
        val controller = model?.currentController
        val frame = controller?.frame

        return UnoRuntime.queryInterface(XDispatchProvider::class.java, frame)
            ?: throw IllegalStateException("Component's controller frame does not support XDispatchProvider")
    }

    /**
     * Closes the connection and releases resources.
     */
    override fun close() {
        try {
            (context as? XComponent)?.dispose()
        } catch (e: Exception) {
            logger.warning("Failed to dispose context: ${e.message}")
        }
        try {
            connection.close()
        } catch (e: Exception) {
            logger.warning("Failed to close connection: ${e.message}")
        }
        try {
            process?.destroy()
        } catch (e: Exception) {
            logger.warning("Failed to destroy process: ${e.message}")
        }
    }
}