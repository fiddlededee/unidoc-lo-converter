package ru.fiddlededee.unidoc.loconverter.core

interface LibreOfficeProvider<T : LibreOfficeSession> {
    fun <R> withSession(block: T.() -> R): R
}