package ru.fiddlededee.unidoc.loconverter.core

import com.sun.star.frame.XDispatchProvider
import com.sun.star.lang.XComponent

interface LibreOfficeSession : AutoCloseable {
    fun loadComponent(inputUrl: String): com.sun.star.lang.XComponent
    fun saveComponent(component: com.sun.star.lang.XComponent, outputUrl: String, filterName: String)
    fun createDispatchHelper(): com.sun.star.frame.XDispatchHelper
    fun getComponentDispatchProvider(component: com.sun.star.lang.XComponent): XDispatchProvider

    fun isAlive(): Boolean = true
    override fun close()
}