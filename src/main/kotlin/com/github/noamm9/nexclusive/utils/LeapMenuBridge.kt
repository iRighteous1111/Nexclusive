package com.github.noamm9.nexclusive.utils

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.features.Feature
import com.github.noamm9.features.FeatureManager
import com.github.noamm9.utils.dungeons.DungeonPlayer

object LeapMenuBridge {
    private val feature: Feature? by lazy {
        FeatureManager.getFeatureByName("Leap Menu")
            ?: FeatureManager.features.find { it::class.simpleName == "LeapMenu" }
    }

    private val updateMethod by lazy {
        feature?.let { f ->
            runCatching { f.javaClass.getMethod("updateLeapMenu") }.getOrNull()
        }
    }

    private val playersField by lazy {
        feature?.let { f ->
            runCatching { f.javaClass.getDeclaredField("players").apply { isAccessible = true } }.getOrNull()
        }
    }

    private var slotIndexField: java.lang.reflect.Field? = null
    private var playerField: java.lang.reflect.Field? = null

    private val customLeapMenuSetting by lazy {
        feature?.configSettings?.find { it.name == "Leap Menu" } as? ToggleSetting
    }

    private val menuScaleSetting by lazy {
        feature?.configSettings?.find { it.name == "Menu Scale" } as? SliderSetting
    }

    private val showLastDoorOpenerSetting by lazy {
        feature?.configSettings?.find { it.name == "Show Last Door Opener" } as? ToggleSetting
    }

    private val tintDeadPlayersSetting by lazy {
        feature?.configSettings?.find { it.name == "Tint Dead Players" } as? ToggleSetting
    }

    private val highlightInCoreSetting by lazy {
        feature?.configSettings?.find { it.name == "Highlight Players in core" } as? ToggleSetting
    }

    val enabled: Boolean
        get() = feature?.enabled == true && (customLeapMenuSetting?.value ?: true)

    val menuScale: Float
        get() = menuScaleSetting?.value?.toFloat() ?: 50f

    val showLastDoorOpener: Boolean
        get() = showLastDoorOpenerSetting?.value == true

    val tintDeadPlayers: Boolean
        get() = tintDeadPlayersSetting?.value ?: true

    val highlightInCore: Boolean
        get() = highlightInCoreSetting?.value == true

    fun updateLeapMenu() {
        val f = feature ?: return
        runCatching { updateMethod?.invoke(f) }
    }

    fun getPlayers(): Array<DungeonPlayer?> {
        val f = feature ?: return arrayOfNulls(4)
        val rawArray = runCatching { playersField?.get(f) as? Array<*> }.getOrNull() ?: return arrayOfNulls(4)
        return Array(4) { i ->
            val entry = rawArray.getOrNull(i) ?: return@Array null
            runCatching {
                val pField = playerField ?: entry.javaClass.getDeclaredField("player").apply {
                    isAccessible = true
                    playerField = this
                }
                pField.get(entry) as? DungeonPlayer
            }.getOrNull()
        }
    }
}
