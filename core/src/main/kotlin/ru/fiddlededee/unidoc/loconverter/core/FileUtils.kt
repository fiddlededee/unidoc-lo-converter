package ru.fiddlededee.unidoc.loconverter.core

import java.io.File

object FileUtils {

    fun toFileUrl(path: String): String {
        require(path.isNotBlank()) { "Path must not be blank" }
        val absolutePath = if (File(path).isAbsolute) {
           path
        } else {
            try {
                File(path).absolutePath
            } catch (_: Exception) {
                throw Exception("Can't determine absolute path")
            }
        }

        return "file://${absolutePath.replace(File.separatorChar, '/')}"
    }

    fun getExtension(string: String): String {
        val dotIndex = string.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < string.length - 1) {
            string.substring(dotIndex + 1).lowercase()
        } else {
            ""
        }
    }
}
