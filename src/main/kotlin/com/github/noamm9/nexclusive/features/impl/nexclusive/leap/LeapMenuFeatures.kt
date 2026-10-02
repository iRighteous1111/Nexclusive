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
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object LeapMenuFeatures: Feature(
    name = "Leap Menu Features",
    description = "Enhances Noamm's Leap Menu with boss mode selections, targets, scaling, highlights, and protection."
) {
    // 1. General Visuals & Protection
    private val highlightCorrect by ToggleSetting("Highlight Correct Player", true).section("Visuals")
    private val useClassColor by ToggleSetting("Use Class Color for Highlight", true).showIf { highlightCorrect.value }
    private val customHighlightColor by ColorSetting("Custom Highlight Color", Color(0, 255, 120, 220), true).showIf { highlightCorrect.value && ! useClassColor.value }

    private val darkenOthers by ToggleSetting("Darken Others", true)
    private val darkenFactor by SliderSetting("Darken Factor", 0.5, 0.1, 0.9, 0.05).showIf { darkenOthers.value }

    private val changeSize by ToggleSetting("Change Size", true)
    private val targetScale by SliderSetting("Target Scale", 1.25, 1.0, 2.0, 0.05).showIf { changeSize.value }
    private val otherScale by SliderSetting("Other Scale", 0.65, 0.1, 1.0, 0.05).showIf { changeSize.value }

    private val blockWrongLeap by ToggleSetting("Block Wrong Leap", false).section("Protection")
    private val overrideClicks by SliderSetting("Override Clicks", 3, 1, 10, 1).showIf { blockWrongLeap.value }

    // 2. Boss Selection Mode Menu
    private val selectedBoss by DropdownSetting(
        "Boss Section", 0, listOf("Maxor", "Storm", "Goldor", "Necron", "P5 (M7)", "Misc Leap")
    ).section("Boss Targets")
    private val classOptions = listOf("None", "Archer", "Mage", "Berserk", "Healer", "Tank")

    // --- Maxor (Boss 0) ---
    private val maxorEnabled by ToggleSetting("Enable Maxor", true).showIf { selectedBoss.value == 0 }
    private val maxorLeap by DropdownSetting("Maxor Leap", 0, classOptions).showIf { selectedBoss.value == 0 && maxorEnabled.value }

    // --- Storm (Boss 1) ---
    private val stormEnabled by ToggleSetting("Enable Storm", true).showIf { selectedBoss.value == 1 }
    private val checkpointLeap by DropdownSetting("Checkpoint Leap (Purple)", 0, classOptions).showIf { selectedBoss.value == 1 && stormEnabled.value }
    private val yellowCrusherLeap by DropdownSetting("Yellow Crusher Leap", 0, classOptions).showIf { selectedBoss.value == 1 && stormEnabled.value }
    private val sscLeap by DropdownSetting("SSC Leap", 0, classOptions).showIf { selectedBoss.value == 1 && stormEnabled.value }

    // --- Goldor (Boss 2) ---
    private val goldorEnabled by ToggleSetting("Enable Goldor", true).showIf { selectedBoss.value == 2 }

    private val s1LeapsCount by SliderSetting("S1 Leaps Count", 1, 1, 4, 1).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s1Leap1 by DropdownSetting("S1 Leap 1", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s1Leap2 by DropdownSetting("S1 Leap 2", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s1LeapsCount.value >= 2 }
    private val s1Leap3 by DropdownSetting("S1 Leap 3", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s1LeapsCount.value >= 3 }
    private val s1Leap4 by DropdownSetting("S1 Leap 4", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s1LeapsCount.value >= 4 }

    private val s2LeapsCount by SliderSetting("S2 Leaps Count", 1, 1, 4, 1).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s2Leap1 by DropdownSetting("S2 Leap 1", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s2Leap2 by DropdownSetting("S2 Leap 2", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s2LeapsCount.value >= 2 }
    private val s2Leap3 by DropdownSetting("S2 Leap 3", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s2LeapsCount.value >= 3 }
    private val s2Leap4 by DropdownSetting("S2 Leap 4", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s2LeapsCount.value >= 4 }

    private val s3LeapsCount by SliderSetting("S3 Leaps Count", 1, 1, 4, 1).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s3Leap1 by DropdownSetting("S3 Leap 1", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s3Leap2 by DropdownSetting("S3 Leap 2", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s3LeapsCount.value >= 2 }
    private val s3Leap3 by DropdownSetting("S3 Leap 3", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s3LeapsCount.value >= 3 }
    private val s3Leap4 by DropdownSetting("S3 Leap 4", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s3LeapsCount.value >= 4 }

    private val s4LeapsCount by SliderSetting("S4 Leaps Count", 1, 1, 3, 1).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s4Leap1 by DropdownSetting("S4 Leap 1", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value }
    private val s4Leap2 by DropdownSetting("S4 Leap 2", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s4LeapsCount.value >= 2 }
    private val s4Leap3 by DropdownSetting("S4 Leap 3", 0, classOptions).showIf { selectedBoss.value == 2 && goldorEnabled.value && s4LeapsCount.value >= 3 }

    // --- Necron (Boss 3) ---
    private val necronEnabled by ToggleSetting("Enable Necron", true).showIf { selectedBoss.value == 3 }
    private val middleLeap by DropdownSetting("Middle Leap", 0, classOptions).showIf { selectedBoss.value == 3 && necronEnabled.value }
    private val pre4Leap by DropdownSetting("Pre4 / I4 Leap", 0, classOptions).showIf { selectedBoss.value == 3 && necronEnabled.value }

    // --- P5 (M7) (Boss 4) ---
    private val p5Enabled by ToggleSetting("Enable P5", true).showIf { selectedBoss.value == 4 }
    private val p5StartLeap by DropdownSetting("P5 Start Leap", 0, classOptions).showIf { selectedBoss.value == 4 && p5Enabled.value }
    private val relicLeap by DropdownSetting("Relic Leap", 0, classOptions).showIf { selectedBoss.value == 4 && p5Enabled.value }

    // --- Misc Leap (Boss 5) ---
    private val miscEnabled by ToggleSetting("Enable Misc Leap", true).showIf { selectedBoss.value == 5 }
    private val i4Leap by DropdownSetting("I4 Leap", 0, classOptions).showIf { selectedBoss.value == 5 && miscEnabled.value }
    private val pdLeap by DropdownSetting("PD Leap", 0, classOptions).showIf { selectedBoss.value == 5 && miscEnabled.value }

    // Runtime state
    private var consecutiveWrongClicks = 0

    val menuScale: Float
        get() = (LeapMenu.configSettings.find { it.name == "Menu Scale" } as? SliderSetting)?.value?.toFloat() ?: 50f

    override fun init() {
        BossPhaseDetector.init()

        register<WorldChangeEvent> {
            consecutiveWrongClicks = 0
            BossPhaseDetector.reset()
        }

        register<ScreenEvent.PreRender>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register

            val targetIndex = getTargetPlayerIndex()
            if (targetIndex == null && ! changeSize.value && ! highlightCorrect.value && ! darkenOthers.value) return@register

            event.isCanceled = true
            LeapMenu.updateLeapMenu()

            if (LeapMenu.players.filterNotNull().isEmpty()) {
                event.context.drawCenteredString("§4§lNo players found", Resolution.width / 2, Resolution.height / 2)
                return@register
            }

            val targetClass = getTargetClass()
            val borderCol = if (useClassColor.value) (targetClass?.color ?: Color.GREEN) else customHighlightColor.value

            LeapMenuRenderer.render(
                event = event,
                targetIndex = targetIndex,
                targetClass = targetClass,
                changeSize = changeSize.value,
                targetScale = targetScale.value.toFloat(),
                otherScale = otherScale.value.toFloat(),
                highlightCorrect = highlightCorrect.value,
                borderCol = borderCol,
                darkenOthers = darkenOthers.value,
                darkenFactor = darkenFactor.value.toFloat()
            )
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register
            if (! blockWrongLeap.value || event.button != 0) return@register

            val targetIndex = getTargetPlayerIndex() ?: return@register
            val hoveredIndex = LeapMenuRenderer.getHoveredIndex() ?: return@register

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
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return null

        // 1. Check Misc Leaps (I4 / PD) if enabled
        if (miscEnabled.value) {
            // I4 Leap: Goldor S1 active and player is in S4 / Pre4
            if (i4Leap.value > 0 && BossPhaseDetector.currentPhase == BossPhaseDetector.Phase.GOLDOR && BossPhaseDetector.goldorSection == 1) {
                if (BossPhaseDetector.isInPre4()) {
                    return DungeonClass.fromName(classOptions[i4Leap.value])
                }
            }

            // PD Leap: Storm active and player is in P3 sections (S1-S4)
            if (pdLeap.value > 0 && BossPhaseDetector.currentPhase == BossPhaseDetector.Phase.STORM) {
                if (BossPhaseDetector.isInP3Section()) {
                    return DungeonClass.fromName(classOptions[pdLeap.value])
                }
            }
        }

        // 2. Boss-specific progression leaps
        val choice = when (BossPhaseDetector.currentPhase) {
            BossPhaseDetector.Phase.MAXOR -> {
                if (maxorEnabled.value && ! BossPhaseDetector.isMaxorDead) maxorLeap.value else 0
            }
            BossPhaseDetector.Phase.STORM -> {
                if (stormEnabled.value) {
                    when (BossPhaseDetector.stormStep) {
                        0 -> checkpointLeap.value
                        1 -> yellowCrusherLeap.value
                        else -> sscLeap.value
                    }
                } else 0
            }
            BossPhaseDetector.Phase.GOLDOR -> {
                if (goldorEnabled.value) {
                    when (BossPhaseDetector.goldorSection) {
                        1 -> resolveMultiLeap(BossPhaseDetector.s1LeapIndex, s1Leap1.value, s1Leap2.value, s1Leap3.value, s1Leap4.value, limit = s1LeapsCount.value)
                        2 -> resolveMultiLeap(BossPhaseDetector.s2LeapIndex, s2Leap1.value, s2Leap2.value, s2Leap3.value, s2Leap4.value, limit = s2LeapsCount.value)
                        3 -> resolveMultiLeap(BossPhaseDetector.s3LeapIndex, s3Leap1.value, s3Leap2.value, s3Leap3.value, s3Leap4.value, limit = s3LeapsCount.value)
                        4 -> resolveMultiLeap(BossPhaseDetector.s4LeapIndex, s4Leap1.value, s4Leap2.value, s4Leap3.value, 0, limit = s4LeapsCount.value)
                        else -> 0
                    }
                } else 0
            }
            BossPhaseDetector.Phase.NECRON -> {
                if (necronEnabled.value) {
                    if (BossPhaseDetector.isPre4Done && pre4Leap.value > 0) pre4Leap.value
                    else if (BossPhaseDetector.isMiddleActive && middleLeap.value > 0) middleLeap.value
                    else 0
                } else 0
            }
            BossPhaseDetector.Phase.P5 -> {
                if (p5Enabled.value) {
                    if (BossPhaseDetector.isRelicActive && relicLeap.value > 0) relicLeap.value
                    else if (BossPhaseDetector.isP5StartActive && p5StartLeap.value > 0) p5StartLeap.value
                    else 0
                } else 0
            }
            else -> 0
        }

        if (choice <= 0 || choice !in classOptions.indices) return null
        return DungeonClass.fromName(classOptions[choice])
    }

    private fun resolveMultiLeap(index: Int, l1: Int, l2: Int, l3: Int, l4: Int, limit: Int): Int {
        val list = listOf(l1, l2, l3, l4).take(limit).filter { it > 0 }
        if (list.isEmpty()) return 0
        return list.getOrElse(index) { list.last() }
    }

    fun getTargetPlayerIndex(): Int? {
        val targetClass = getTargetClass() ?: return null
        val index = LeapMenu.players.indexOfFirst { entry ->
            entry != null && ! entry.player.isDead && entry.player.clazz == targetClass
        }
        return if (index != - 1) index else null
    }
}
