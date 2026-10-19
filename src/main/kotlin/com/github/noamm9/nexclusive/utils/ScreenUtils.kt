package com.github.noamm9.nexclusive.utils

import com.github.noamm9.NoammAddons.mc
import java.lang.reflect.Field
import java.lang.reflect.Method

object ScreenUtils {
    private val mcScreenField: Field? = runCatching {
        mc::class.java.getField("screen")
    }.getOrNull()

    private val guiMethod: Method? = runCatching {
        mc::class.java.getMethod("getGui")
    }.getOrElse {
        runCatching {
            mc::class.java.getField("gui")
        }.getOrNull()?.let { null }
    }

    private val guiField: Field? = if (guiMethod == null) {
        runCatching { mc::class.java.getField("gui") }.getOrNull()
    } else null

    private val guiScreenMethod: Method? = runCatching {
        val guiObj = getGuiObject()
        guiObj?.javaClass?.getMethod("screen")
    }.getOrNull()

    private fun getGuiObject(): Any? {
        return runCatching {
            guiMethod?.invoke(mc) ?: guiField?.get(mc)
        }.getOrNull()
    }

    val currentScreen: Any?
        get() {
            // 26.1.2: mc.screen field
            mcScreenField?.let { field ->
                runCatching { return field.get(mc) }
            }

            // 26.2+: mc.gui.screen() method
            val guiObj = getGuiObject() ?: return null
            val method = guiScreenMethod ?: runCatching {
                guiObj.javaClass.getMethod("screen")
            }.getOrNull()

            return runCatching { method?.invoke(guiObj) }.getOrNull()
        }

    val isScreenOpen: Boolean
        get() = currentScreen != null
}
