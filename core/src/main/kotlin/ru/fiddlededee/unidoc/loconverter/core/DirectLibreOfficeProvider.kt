package ru.fiddlededee.unidoc.loconverter.core

import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.time.Duration

/**
 * Provides LibreOffice sessions via direct UNO connection over socket.
 */
class DirectLibreOfficeProvider(
    private val port: Int = 2002,
    private val host: String = "127.0.0.1",
    private val startLocal: Boolean = false,
    private val sofficeCmd: String = "soffice",
    private val startRetries: Int = 20,
    private val startRetryDelayMs: Long = 500,
    private val connectRetries: Int = 20,
    private val connectRetryDelayMs: Long = 500
) : LibreOfficeProvider<DirectLibreOfficeSession> {

    fun start(): DirectLibreOfficeSession {
        val process: Process? = if (startLocal) launchLocalProcess() else null
        return DirectLibreOfficeSession(
            host, port, process = process,
            connectRetries = connectRetries,
            connectRetryDelayMs = connectRetryDelayMs,        )
    }


    override fun <R> withSession(block: DirectLibreOfficeSession.() -> R): R {
        val session = if (startLocal) {
            start()
        } else {
            DirectLibreOfficeSession(host, port, connectRetries = connectRetries)
        }
        return session.use { session.block() }
    }

    private fun launchLocalProcess(): Process {
        val process = ProcessBuilder(
            sofficeCmd, "--headless", "--norestore", "--nologo", "--nodefault",
            "--accept=socket,host=$host,port=$port;urp;"
        ).start()

        var retries = startRetries
        while (retries-- > 0) {
            try {
                Socket("127.0.0.1", port).use { return process }
            } catch (_: ConnectException) {
                Thread.sleep(connectRetryDelayMs)
            }
        }
        process.destroy()
        throw IOException("LibreOffice failed to start on port $port")
    }
}
