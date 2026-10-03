package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.config.types.ButtonSetting
import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.MultiCheckboxSetting
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
    // 1. Visuals & Click Protection
    private val highlightCorrect by ToggleSetting("Highlight Correct Player", true).section("Visuals")
    private val useClassColor by ToggleSetting("Use Class Color for Highlight", true).showIf { highlightCorrect.value }
    private val customHighlightColor by ColorSetting("Custom Highlight Color", Color(0, 255, 120, 220), true).showIf { highlightCorrect.value && ! useClassColor.value }

    private val darkenOthers by ToggleSetting("Darken Others", true)
    private val darkenFactor by SliderSetting("Darken Factor", 0.5, 0.1, 0.9, 0.05).showIf { darkenOthers.value }

    private val changeSize by ToggleSetting("Change Size", true)
    private val targetScale by SliderSetting("Target Scale", 1.25, 1.0, 2.0, 0.05).showIf { changeSize.value }
    private val otherScale by SliderSetting("Other Scale", 0.65, 0.1, 1.0, 0.05).showIf { changeSize.value }

    private val blockWrongClicks by ToggleSetting("Block Wrong Clicks", false)
    private val overrideClicks by SliderSetting("Override Clicks", 3, 1, 10, 1).showIf { blockWrongClicks.value }

    // 2. Boss Selection Mode Menu
    private val selectedBoss by DropdownSetting(
        "Boss Section", 0, listOf("P1", "P2", "P3", "P5", "Misc Leap")
    ).section("Boss Targets")
    private val classOptions = listOf("None", "Archer", "Mage", "Berserk", "Healer", "Tank")

    private fun defaultClasses() = mutableMapOf(
        "Archer" to false, "Mage" to false, "Berserk" to false, "Healer" to false, "Tank" to false
    )

    // --- P1 (Boss 0) ---
    private val p1Enabled by ToggleSetting("Enable P1", true).showIf { selectedBoss.value == 0 }
    private val p1Leap by DropdownSetting("P1 Leap", 0, classOptions).showIf { selectedBoss.value == 0 && p1Enabled.value }

    // --- P2 (Boss 1) ---
    private val p2Enabled by ToggleSetting("Enable P2", true).showIf { selectedBoss.value == 1 }
    private val checkpointLeap by DropdownSetting("Checkpoint Leap (Purple)", 0, classOptions).showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val yellowCrusherLeap by DropdownSetting("Yellow Crusher Leap", 0, classOptions).showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val sscLeap by DropdownSetting("SSC Leap", 0, classOptions).showIf { selectedBoss.value == 1 && p2Enabled.value }

    // --- P3 (Goldor) (Boss 2) ---
    private val p3Enabled by ToggleSetting("Enable P3", true).showIf { selectedBoss.value == 2 }

    // Section 1
    private val s1Count = SliderSetting("s1_count", 1, 1, 4, 1).hideIf { true }.apply(configSettings::add)
    @Suppress("unused")
    private val addS1Leap = ButtonSetting("+ Add Leap (S1)") {
        if (s1Count.value < 4) s1Count.value = s1Count.value + 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value < 4 }.section("§aSection 1 (S1)").apply(configSettings::add)
    @Suppress("unused")
    private val removeS1Leap = ButtonSetting("- Remove Leap (S1)") {
        if (s1Count.value > 1) s1Count.value = s1Count.value - 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value > 1 }.section("§aSection 1 (S1)").apply(configSettings::add)

    private val s1Leap1 by MultiCheckboxSetting("S1 Leap 1", defaultClasses()).jsonName("s1_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }.section("§aSection 1 (S1)")
    private val s1Leap2 by MultiCheckboxSetting("S1 Leap 2", defaultClasses()).jsonName("s1_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 2 }.section("§aSection 1 (S1)")
    private val s1Leap3 by MultiCheckboxSetting("S1 Leap 3", defaultClasses()).jsonName("s1_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 3 }.section("§aSection 1 (S1)")
    private val s1Leap4 by MultiCheckboxSetting("S1 Leap 4", defaultClasses()).jsonName("s1_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 4 }.section("§aSection 1 (S1)")

    // Section 2
    private val s2Count = SliderSetting("s2_count", 1, 1, 4, 1).hideIf { true }.apply(configSettings::add)
    @Suppress("unused")
    private val addS2Leap = ButtonSetting("+ Add Leap (S2)") {
        if (s2Count.value < 4) s2Count.value = s2Count.value + 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value < 4 }.section("§eSection 2 (S2)").apply(configSettings::add)
    @Suppress("unused")
    private val removeS2Leap = ButtonSetting("- Remove Leap (S2)") {
        if (s2Count.value > 1) s2Count.value = s2Count.value - 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value > 1 }.section("§eSection 2 (S2)").apply(configSettings::add)

    private val s2Leap1 by MultiCheckboxSetting("S2 Leap 1", defaultClasses()).jsonName("s2_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }.section("§eSection 2 (S2)")
    private val s2Leap2 by MultiCheckboxSetting("S2 Leap 2", defaultClasses()).jsonName("s2_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 2 }.section("§eSection 2 (S2)")
    private val s2Leap3 by MultiCheckboxSetting("S2 Leap 3", defaultClasses()).jsonName("s2_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 3 }.section("§eSection 2 (S2)")
    private val s2Leap4 by MultiCheckboxSetting("S2 Leap 4", defaultClasses()).jsonName("s2_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 4 }.section("§eSection 2 (S2)")

    // Section 3
    private val s3Count = SliderSetting("s3_count", 1, 1, 4, 1).hideIf { true }.apply(configSettings::add)
    @Suppress("unused")
    private val addS3Leap = ButtonSetting("+ Add Leap (S3)") {
        if (s3Count.value < 4) s3Count.value = s3Count.value + 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value < 4 }.section("§cSection 3 (S3)").apply(configSettings::add)
    @Suppress("unused")
    private val removeS3Leap = ButtonSetting("- Remove Leap (S3)") {
        if (s3Count.value > 1) s3Count.value = s3Count.value - 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value > 1 }.section("§cSection 3 (S3)").apply(configSettings::add)

    private val s3Leap1 by MultiCheckboxSetting("S3 Leap 1", defaultClasses()).jsonName("s3_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }.section("§cSection 3 (S3)")
    private val s3Leap2 by MultiCheckboxSetting("S3 Leap 2", defaultClasses()).jsonName("s3_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 2 }.section("§cSection 3 (S3)")
    private val s3Leap3 by MultiCheckboxSetting("S3 Leap 3", defaultClasses()).jsonName("s3_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 3 }.section("§cSection 3 (S3)")
    private val s3Leap4 by MultiCheckboxSetting("S3 Leap 4", defaultClasses()).jsonName("s3_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 4 }.section("§cSection 3 (S3)")

    // Section 4
    private val s4Count = SliderSetting("s4_count", 1, 1, 3, 1).hideIf { true }.apply(configSettings::add)
    @Suppress("unused")
    private val addS4Leap = ButtonSetting("+ Add Leap (S4)") {
        if (s4Count.value < 3) s4Count.value = s4Count.value + 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value < 3 }.section("§bSection 4 (S4)").apply(configSettings::add)
    @Suppress("unused")
    private val removeS4Leap = ButtonSetting("- Remove Leap (S4)") {
        if (s4Count.value > 1) s4Count.value = s4Count.value - 1
    }.showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value > 1 }.section("§bSection 4 (S4)").apply(configSettings::add)

    private val s4Leap1 by MultiCheckboxSetting("S4 Leap 1", defaultClasses()).jsonName("s4_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }.section("§bSection 4 (S4)")
    private val s4Leap2 by MultiCheckboxSetting("S4 Leap 2", defaultClasses()).jsonName("s4_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value >= 2 }.section("§bSection 4 (S4)")
    private val s4Leap3 by MultiCheckboxSetting("S4 Leap 3", defaultClasses()).jsonName("s4_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value >= 3 }.section("§bSection 4 (S4)")

    // --- P5 (Boss 3) ---
    private val p5Enabled by ToggleSetting("Enable P5", true).showIf { selectedBoss.value == 3 }
    private val relicLeap by DropdownSetting("Relic Leap", 0, classOptions).showIf { selectedBoss.value == 3 && p5Enabled.value }

    // --- Misc Leap (Boss 4) ---
    private val miscEnabled by ToggleSetting("Enable Misc Leap", true).showIf { selectedBoss.value == 4 }
    private val i4Leap by DropdownSetting("I4 Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }
    private val pdLeap by DropdownSetting("PD Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }

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

            BossPhaseDetector.recordLeapSection()

            val targetIndices = getTargetPlayerIndices().toSet()
            if (targetIndices.isEmpty() && ! changeSize.value && ! highlightCorrect.value && ! darkenOthers.value) return@register

            event.isCanceled = true
            LeapMenu.updateLeapMenu()

            if (LeapMenu.players.filterNotNull().isEmpty()) {
                event.context.drawCenteredString("§4§lNo players found", Resolution.width / 2, Resolution.height / 2)
                return@register
            }

            LeapMenuRenderer.render(
                event = event,
                targetIndices = targetIndices,
                useClassColor = useClassColor.value,
                changeSize = changeSize.value,
                targetScale = targetScale.value.toFloat(),
                otherScale = otherScale.value.toFloat(),
                highlightCorrect = highlightCorrect.value,
                borderCol = customHighlightColor.value,
                darkenOthers = darkenOthers.value,
                darkenFactor = darkenFactor.value.toFloat()
            )
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register
            if (! blockWrongClicks.value || event.button != 0) return@register

            val targetIndices = getTargetPlayerIndices()
            if (targetIndices.isEmpty()) return@register
            val hoveredIndex = LeapMenuRenderer.getHoveredIndex() ?: return@register

            if (hoveredIndex !in targetIndices) {
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

    fun getTargetClasses(): Set<DungeonClass> {
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return emptySet()

        // 1. Check Misc Leaps (I4 / PD) if enabled
        if (miscEnabled.value) {
            // I4 Leap: Goldor S1 active and player is in S4 / Pre4
            if (i4Leap.value > 0 && BossPhaseDetector.currentPhase == BossPhaseDetector.Phase.GOLDOR && BossPhaseDetector.goldorSection == 1) {
                if (BossPhaseDetector.isInPre4()) {
                    val c = DungeonClass.fromName(classOptions[i4Leap.value])
                    return if (c != DungeonClass.Empty) setOf(c) else emptySet()
                }
            }

            // PD Leap: Storm active and player is in P3 sections (S1-S4)
            if (pdLeap.value > 0 && BossPhaseDetector.currentPhase == BossPhaseDetector.Phase.STORM) {
                if (BossPhaseDetector.isInP3Section()) {
                    val c = DungeonClass.fromName(classOptions[pdLeap.value])
                    return if (c != DungeonClass.Empty) setOf(c) else emptySet()
                }
            }
        }

        // 2. Boss-specific progression leaps
        return when (BossPhaseDetector.currentPhase) {
            BossPhaseDetector.Phase.MAXOR -> {
                if (p1Enabled.value && ! BossPhaseDetector.isMaxorDead && p1Leap.value > 0) {
                    val c = DungeonClass.fromName(classOptions[p1Leap.value])
                    if (c != DungeonClass.Empty) setOf(c) else emptySet()
                } else emptySet()
            }
            BossPhaseDetector.Phase.STORM -> {
                if (p2Enabled.value) {
                    val choice = when (BossPhaseDetector.stormStep) {
                        0 -> checkpointLeap.value
                        1 -> yellowCrusherLeap.value
                        else -> sscLeap.value
                    }
                    if (choice > 0) {
                        val c = DungeonClass.fromName(classOptions[choice])
                        if (c != DungeonClass.Empty) setOf(c) else emptySet()
                    } else emptySet()
                } else emptySet()
            }
            BossPhaseDetector.Phase.GOLDOR -> {
                if (p3Enabled.value) {
                    val sec = BossPhaseDetector.playerSection ?: BossPhaseDetector.goldorSection
                    when (sec) {
                        1 -> resolveMultiTarget(BossPhaseDetector.s1LeapIndex, listOf(s1Leap1, s1Leap2, s1Leap3, s1Leap4), s1Count.value)
                        2 -> resolveMultiTarget(BossPhaseDetector.s2LeapIndex, listOf(s2Leap1, s2Leap2, s2Leap3, s2Leap4), s2Count.value)
                        3 -> resolveMultiTarget(BossPhaseDetector.s3LeapIndex, listOf(s3Leap1, s3Leap2, s3Leap3, s3Leap4), s3Count.value)
                        4 -> resolveMultiTarget(BossPhaseDetector.s4LeapIndex, listOf(s4Leap1, s4Leap2, s4Leap3), s4Count.value)
                        else -> emptySet()
                    }
                } else emptySet()
            }
            BossPhaseDetector.Phase.P5 -> {
                if (p5Enabled.value && relicLeap.value > 0) {
                    val c = DungeonClass.fromName(classOptions[relicLeap.value])
                    if (c != DungeonClass.Empty) setOf(c) else emptySet()
                } else emptySet()
            }
            else -> emptySet()
        }
    }

    private fun resolveMultiTarget(
        index: Int,
        steps: List<MultiCheckboxSetting>,
        limit: Int
    ): Set<DungeonClass> {
        val activeSteps = steps.take(limit).map { setting ->
            setting.value.filter { it.value }.keys.map { DungeonClass.fromName(it) }.filter { it != DungeonClass.Empty }.toSet()
        }.filter { it.isNotEmpty() }

        if (activeSteps.isEmpty()) return emptySet()
        return activeSteps.getOrElse(index) { activeSteps.last() }
    }

    fun getTargetPlayerIndices(): List<Int> {
        val targetClasses = getTargetClasses()
        if (targetClasses.isEmpty()) return emptyList()
        return LeapMenu.players.mapIndexedNotNull { i, entry ->
            if (entry != null && ! entry.player.isDead && entry.player.clazz in targetClasses) i else null
        }
    }
}
