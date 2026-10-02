package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.dungeon.LeapMenu
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.dungeons.DungeonPlayer
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawFloatingRect
import com.github.noamm9.utils.render.Render2D.drawPlayerHead
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object LeapMenuFeatures: Feature(
    name = "Leap Menu Features",
    description = "Enhances Noamm's Leap Menu with phase-specific targets, scaling, highlights, and leap protection."
) {
    // 1. Visual Features
    private val highlightCorrect by ToggleSetting("Highlight Correct Player", true).section("Visuals")
    private val highlightColor by ColorSetting("Highlight Color", Color(0, 255, 120, 220), true).showIf { highlightCorrect.value }

    private val changeSize by ToggleSetting("Change Size", true)
    private val targetScale by SliderSetting("Target Scale", 1.25, 1.0, 2.0, 0.05).showIf { changeSize.value }
    private val otherScale by SliderSetting("Other Scale", 0.85, 0.5, 1.0, 0.05).showIf { changeSize.value }

    // 2. Protection Features
    private val blockWrongLeap by ToggleSetting("Block Wrong Leap", false).section("Protection")
    private val overrideClicks by SliderSetting("Override Clicks", 3, 1, 10, 1).showIf { blockWrongLeap.value }

    // Class selection choices
    private val classOptions = listOf("None", "Archer", "Mage", "Berserk", "Healer", "Tank")

    // 3. Maxor Configuration
    private val maxorEnabled by ToggleSetting("Enable Maxor", true).section("Maxor")
    private val maxorLeap by DropdownSetting("Maxor Leap", 0, classOptions).showIf { maxorEnabled.value }

    // 4. Storm Configuration
    private val stormEnabled by ToggleSetting("Enable Storm", true).section("Storm")
    private val pyLeap by DropdownSetting("PY Leap", 0, classOptions).showIf { stormEnabled.value }
    private val sscLeap by DropdownSetting("SSC Leap", 0, classOptions).showIf { stormEnabled.value }

    // 5. Goldor Configuration
    private val goldorEnabled by ToggleSetting("Enable Goldor", true).section("Goldor")
    private val s1Leap1 by DropdownSetting("S1 First Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s1Leap2 by DropdownSetting("S1 Second Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s1Leap3 by DropdownSetting("S1 Third Leap", 0, classOptions).showIf { goldorEnabled.value }

    private val s2Leap1 by DropdownSetting("S2 First Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s2Leap2 by DropdownSetting("S2 Second Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s2Leap3 by DropdownSetting("S2 Third Leap", 0, classOptions).showIf { goldorEnabled.value }

    private val s3Leap1 by DropdownSetting("S3 First Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s3Leap2 by DropdownSetting("S3 Second Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s3Leap3 by DropdownSetting("S3 Third Leap", 0, classOptions).showIf { goldorEnabled.value }

    private val s4Leap1 by DropdownSetting("S4 First Leap", 0, classOptions).showIf { goldorEnabled.value }
    private val s4Leap2 by DropdownSetting("S4 Second Leap", 0, classOptions).showIf { goldorEnabled.value }

    // 6. P5 (M7) Configuration
    private val p5Enabled by ToggleSetting("Enable P5", true).section("P5 (M7)")
    private val p5RelicLeap by DropdownSetting("P5 Relic Leap", 0, classOptions).showIf { p5Enabled.value }

    // Runtime state
    private var consecutiveWrongClicks = 0
    private val boxBg = Color(33, 33, 33)
    private val boxBgHover = Color(67, 67, 67)

    private val menuScaleSetting by lazy {
        LeapMenu.configSettings.find { it.name == "Menu Scale" } as? SliderSetting
    }

    override fun init() {
        BossPhaseDetector.init()

        register<WorldChangeEvent> {
            consecutiveWrongClicks = 0
            BossPhaseDetector.reset()
        }

        register<ScreenEvent.PreRender>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register

            val targetIndex = getTargetPlayerIndex()
            // If no target or neither highlight nor resize is enabled, let Noamm render as default
            if (targetIndex == null && ! changeSize.value && ! highlightCorrect.value) return@register

            event.isCanceled = true
            LeapMenu.updateLeapMenu()

            if (LeapMenu.players.filterNotNull().isEmpty()) {
                event.context.drawCenteredString("§4§lNo players found", Resolution.width / 2, Resolution.height / 2)
                return@register
            }

            renderCustomMenu(event, targetIndex)
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register
            if (! blockWrongLeap.value || event.button != 0) return@register

            val targetIndex = getTargetPlayerIndex() ?: return@register
            val hoveredIndex = getHoveredIndex() ?: return@register

            if (hoveredIndex != targetIndex) {
                consecutiveWrongClicks++
                if (consecutiveWrongClicks < overrideClicks.value) {
                    event.isCanceled = true
                    mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
                    val needed = overrideClicks.value - consecutiveWrongClicks
                    ChatUtils.modMessage("§cBlocked wrong leap! Click §e$needed §cmore time(s) to override.")
                    return@register
                }
            }

            consecutiveWrongClicks = 0
        }
    }

    private fun isApplicable(screen: Screen): Boolean {
        if (! enabled || ! LeapMenu.enabled) return false
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return false
        val title = screen.title.string.lowercase()
        return title.contains("spirit leap") || title.contains("teleport to player")
    }

    fun getTargetClass(): DungeonClass? {
        BossPhaseDetector.updateState()
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return null

        val choice = when (BossPhaseDetector.currentPhase) {
            BossPhaseDetector.Phase.MAXOR -> {
                if (maxorEnabled.value && ! BossPhaseDetector.isMaxorDead) maxorLeap.value else 0
            }
            BossPhaseDetector.Phase.STORM -> {
                if (stormEnabled.value) {
                    if (BossPhaseDetector.isPyActive) pyLeap.value
                    else if (BossPhaseDetector.isSscActive) sscLeap.value
                    else 0
                } else 0
            }
            BossPhaseDetector.Phase.GOLDOR -> {
                if (goldorEnabled.value) {
                    when (BossPhaseDetector.goldorSection) {
                        1 -> resolveMultiLeap(BossPhaseDetector.s1LeapCount, s1Leap1.value, s1Leap2.value, s1Leap3.value)
                        2 -> resolveMultiLeap(BossPhaseDetector.s2LeapCount, s2Leap1.value, s2Leap2.value, s2Leap3.value)
                        3 -> resolveMultiLeap(BossPhaseDetector.s3LeapCount, s3Leap1.value, s3Leap2.value, s3Leap3.value)
                        4 -> resolveMultiLeap(BossPhaseDetector.s4LeapCount, s4Leap1.value, s4Leap2.value, 0)
                        else -> 0
                    }
                } else 0
            }
            BossPhaseDetector.Phase.P5 -> {
                if (p5Enabled.value && BossPhaseDetector.isRelicWindowActive()) p5RelicLeap.value else 0
            }
            else -> 0
        }

        if (choice <= 0 || choice !in classOptions.indices) return null
        return DungeonClass.fromName(classOptions[choice])
    }

    private fun resolveMultiLeap(count: Int, leap1: Int, leap2: Int, leap3: Int): Int {
        return when {
            count == 0 -> leap1
            count == 1 -> if (leap2 > 0) leap2 else leap1
            else -> if (leap3 > 0) leap3 else if (leap2 > 0) leap2 else leap1
        }
    }

    fun getTargetPlayerIndex(): Int? {
        val targetClass = getTargetClass() ?: return null
        val index = LeapMenu.players.indexOfFirst { entry ->
            entry != null && ! entry.player.isDead && entry.player.clazz == targetClass
        }
        return if (index != - 1) index else null
    }

    private fun renderCustomMenu(event: ScreenEvent.PreRender, targetIndex: Int?) {
        Resolution.push(event.context)

        val rawScale = menuScaleSetting?.value?.toFloat() ?: 50f
        val userScale = (rawScale / 100f) * 2.0f
        val screenWidth = Resolution.width / userScale
        val screenHeight = Resolution.height / userScale

        val pose = event.context.pose()
        pose.pushMatrix()
        pose.scale(userScale)

        val baseBoxW = 128f * 1.3f
        val baseBoxH = 80f * 0.8f
        val padding = 40f
        val baseHeadSize = 50f

        val gridW = (baseBoxW * 2) + padding
        val gridH = (baseBoxH * 2) + padding
        val startX = (screenWidth - gridW) / 2f
        val startY = (screenHeight - gridH) / 2f

        val slotCenters = listOf(
            (startX + baseBoxW / 2f) to (startY + baseBoxH / 2f),
            (startX + baseBoxW + padding + baseBoxW / 2f) to (startY + baseBoxH / 2f),
            (startX + baseBoxW / 2f) to (startY + baseBoxH + padding + baseBoxH / 2f),
            (startX + baseBoxW + padding + baseBoxW / 2f) to (startY + baseBoxH + padding + baseBoxH / 2f)
        )

        val hoveredIndex = getHoveredIndex()

        LeapMenu.players.forEachIndexed { i, entry ->
            if (entry == null) return@forEachIndexed

            val isTarget = i == targetIndex
            val scaleFactor = if (changeSize.value) {
                if (isTarget) targetScale.value.toFloat()
                else if (targetIndex != null) otherScale.value.toFloat()
                else 1.0f
            } else 1.0f

            val boxWidth = baseBoxW * scaleFactor
            val boxHeight = baseBoxH * scaleFactor
            val headSize = (baseHeadSize * scaleFactor).toInt()

            val (cx, cy) = slotCenters[i]
            val x = cx - (boxWidth / 2f)
            val y = cy - (boxHeight / 2f)
            val isHovered = i == hoveredIndex

            val baseBg = when {
                entry.player.isDead -> boxBg.withAlpha(210)
                isHovered -> boxBgHover
                else -> boxBg
            }

            // Draw card background
            event.context.drawFloatingRect(x, y, boxWidth, boxHeight, baseBg.withAlpha(190))

            // Highlight border for target player
            if (isTarget && highlightCorrect.value) {
                event.context.drawBorder(x, y, boxWidth, boxHeight, highlightColor.value, thickness = 3)
            }

            // Draw player head
            val headX = (x + (10f * scaleFactor)).toInt()
            val headY = (y + (boxHeight / 2f) - (headSize / 2f)).toInt()

            event.context.drawPlayerHead(headX, headY, headSize, entry.player.skin)
            event.context.drawBorder(headX, headY, headSize, headSize, entry.player.clazz.color)

            // Draw text info
            val textX = (x + (10f * scaleFactor) + headSize + (5f * scaleFactor)).toInt()
            val textY = (y + (boxHeight / 2f) - mc.font.lineHeight).toInt()

            event.context.drawString(entry.player.name, textX, textY + 2, entry.player.clazz.color)

            val statusText = when {
                entry.player.isDead -> "§cDEAD"
                isTarget && highlightCorrect.value -> "§a★ TARGET"
                else -> entry.player.clazz.name
            }
            event.context.drawString(statusText, textX, textY + 12, entry.player.clazz.color)
        }

        pose.popMatrix()
        Resolution.pop(event.context)
    }

    private fun getHoveredIndex(): Int? {
        val window = mc.window
        val cx = window.screenWidth / 2
        val cy = window.screenHeight / 2
        val mx = mc.mouseHandler.xpos()
        val my = mc.mouseHandler.ypos()

        return when {
            mx < cx && my < cy -> 0
            mx > cx && my < cy -> 1
            mx < cx && my > cy -> 2
            mx > cx && my > cy -> 3
            else -> null
        }
    }
}
