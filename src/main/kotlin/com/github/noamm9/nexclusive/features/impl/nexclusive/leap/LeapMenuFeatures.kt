package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.MultiCheckboxSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.ui.PlusMinusSetting
import com.github.noamm9.nexclusive.utils.LeapMenuBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonPlayer
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object LeapMenuFeatures: Feature(
    name = "Leap Menu Features",
    description = "Enhances Noamm's Leap Menu with boss mode selections, targets, scaling, highlights, and protection."
) {
    // 1. Visuals
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

    // 2. Player Selection
    private val selectedBoss by DropdownSetting(
        "Boss Section", 0, listOf("P1", "P2", "P3", "P5", "Misc Leap")
    ).section("Player Selection")
    private val classOptions = listOf("None", "Archer", "Mage", "Berserk", "Healer", "Tank")

    private fun defaultClasses() = mutableMapOf(
        "Archer" to false, "Mage" to false, "Berserk" to false, "Healer" to false, "Tank" to false
    )

    // --- P1 (Boss 0) ---
    private val p1Enabled by ToggleSetting("Enable P1", true).showIf { selectedBoss.value == 0 }
    private val p1Leap by DropdownSetting("P1 Leap", 0, classOptions).showIf { selectedBoss.value == 0 && p1Enabled.value }

    // --- P2 (Boss 1) ---
    private val p2Enabled by ToggleSetting("Enable P2", true).showIf { selectedBoss.value == 1 }
    private val purplePillarLeap by DropdownSetting("Purple Pillar Leap", 0, classOptions).withDescription("When near purple pillar targets selected class.").showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val purplePadLeap by DropdownSetting("Purple Pad Leap", 0, classOptions).withDescription("For going down after padding purple.").showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val acaciaSpotLeap by DropdownSetting("Acacia Spot Leap", 0, classOptions).withDescription("Targets selected class when near purple pillar.").showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val yellowPadLeap by DropdownSetting("Yellow Pad Leap", 0, classOptions).withDescription("Its gonna target this person while near yellow pad.").showIf { selectedBoss.value == 1 && p2Enabled.value }
    private val yellowPillarLeap by DropdownSetting("Yellow Pillar Leap", 0, classOptions).withDescription("Targets selected class when near yellow pillar.").showIf { selectedBoss.value == 1 && p2Enabled.value }

    private val purplePillarBox = aabb(94, 169, 60, 106, 175, 70)
    private val purplePadBox = aabb(106, 170, 86, 122, 175, 102)
    private val acaciaSpotBox = aabb(78, 163, 92, 91, 168, 82)
    private val yellowPadBox = aabb(58, 160, 85, 24, 177, 102)
    private val yellowPillarBox = aabb(59, 169, 75, 32, 173, 56)

    // --- P3 (Goldor) (Boss 2) ---
    private val p3Enabled by ToggleSetting("Enable P3", true).showIf { selectedBoss.value == 2 }

    private fun sectionCounter(key: String, label: String, max: Int): SliderSetting<Int> {
        val count = SliderSetting(key, 1, 1, max, 1).hideIf { true }.apply(configSettings::add)
        PlusMinusSetting(
            name = label,
            countSupplier = { count.value },
            minCount = 1,
            maxCount = max,
            onAdd = { if (count.value < max) count.value += 1 },
            onRemove = { if (count.value > 1) count.value -= 1 }
        ).showIf { selectedBoss.value == 2 && p3Enabled.value }.apply(configSettings::add)
        return count
    }

    private val s1Count = sectionCounter("s1_count", "§aSection 1 (S1)", 4)
    private val s1Leap1 by MultiCheckboxSetting("S1 Leap 1", defaultClasses()).jsonName("s1_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }
    private val s1Leap2 by MultiCheckboxSetting("S1 Leap 2", defaultClasses()).jsonName("s1_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 2 }
    private val s1Leap3 by MultiCheckboxSetting("S1 Leap 3", defaultClasses()).jsonName("s1_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 3 }
    private val s1Leap4 by MultiCheckboxSetting("S1 Leap 4", defaultClasses()).jsonName("s1_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s1Count.value >= 4 }

    private val s2Count = sectionCounter("s2_count", "§eSection 2 (S2)", 4)
    private val s2Leap1 by MultiCheckboxSetting("S2 Leap 1", defaultClasses()).jsonName("s2_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }
    private val s2Leap2 by MultiCheckboxSetting("S2 Leap 2", defaultClasses()).jsonName("s2_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 2 }
    private val s2Leap3 by MultiCheckboxSetting("S2 Leap 3", defaultClasses()).jsonName("s2_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 3 }
    private val s2Leap4 by MultiCheckboxSetting("S2 Leap 4", defaultClasses()).jsonName("s2_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s2Count.value >= 4 }

    private val s3Count = sectionCounter("s3_count", "§cSection 3 (S3)", 4)
    private val s3Leap1 by MultiCheckboxSetting("S3 Leap 1", defaultClasses()).jsonName("s3_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }
    private val s3Leap2 by MultiCheckboxSetting("S3 Leap 2", defaultClasses()).jsonName("s3_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 2 }
    private val s3Leap3 by MultiCheckboxSetting("S3 Leap 3", defaultClasses()).jsonName("s3_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 3 }
    private val s3Leap4 by MultiCheckboxSetting("S3 Leap 4", defaultClasses()).jsonName("s3_target_4").showIf { selectedBoss.value == 2 && p3Enabled.value && s3Count.value >= 4 }

    private val s4Count = sectionCounter("s4_count", "§bSection 4 (S4)", 3)
    private val s4Leap1 by MultiCheckboxSetting("S4 Leap 1", defaultClasses()).jsonName("s4_target_1").showIf { selectedBoss.value == 2 && p3Enabled.value }
    private val s4Leap2 by MultiCheckboxSetting("S4 Leap 2", defaultClasses()).jsonName("s4_target_2").showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value >= 2 }
    private val s4Leap3 by MultiCheckboxSetting("S4 Leap 3", defaultClasses()).jsonName("s4_target_3").showIf { selectedBoss.value == 2 && p3Enabled.value && s4Count.value >= 3 }

    // --- P5 (Boss 3) ---
    private val p5Enabled by ToggleSetting("Enable P5", true).showIf { selectedBoss.value == 3 }
    private val relicLeap by DropdownSetting("Relic Leap", 0, classOptions).showIf { selectedBoss.value == 3 && p5Enabled.value }

    // --- Misc Leap (Boss 4) ---
    private val miscEnabled by ToggleSetting("Enable Misc Leap", true).showIf { selectedBoss.value == 4 }
    private val i4Leap by DropdownSetting("I4 Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }
    private val pdLeap by DropdownSetting("PD Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }

    private var consecutiveWrongClicks = 0

    val menuScale: Float get() = LeapMenuBridge.menuScale
    val showLastDoorOpener: Boolean get() = LeapMenuBridge.showLastDoorOpener
    val tintDeadPlayers: Boolean get() = LeapMenuBridge.tintDeadPlayers
    val highlightInCore: Boolean get() = LeapMenuBridge.highlightInCore

    fun isCoreHighlightActive(): Boolean {
        if (! highlightInCore) return false
        return DungeonListener.dungeonTeammatesNoSelf.any { LeapMenuRenderer.isPlayerInCore(it) }
    }

    override fun init() {
        register<WorldChangeEvent> {
            consecutiveWrongClicks = 0
            BossPhaseDetector.reset()
        }

        register<ChatMessageEvent> { BossPhaseDetector.onChat(event.unformattedText) }
        register<MainThreadPacketReceivedEvent.Post> { BossPhaseDetector.onPacketPost(event) }

        register<ScreenEvent.PreRender>(EventPriority.HIGHEST) {
            if (! isApplicable(event.screen)) return@register

            BossPhaseDetector.recordLeapSection()
            LeapMenuBridge.updateLeapMenu()
            val players = LeapMenuBridge.getPlayers()

            val coreActive = isCoreHighlightActive()
            val targetIndices = if (coreActive) emptySet() else getTargetPlayerIndices(players).toSet()
            if (targetIndices.isEmpty() && ! coreActive) return@register

            event.isCanceled = true

            if (players.all { it == null }) {
                event.context.drawCenteredString("§4§lNo players found", Resolution.width / 2, Resolution.height / 2)
                return@register
            }

            LeapMenuRenderer.render(
                event = event,
                players = players,
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

            val targetIndices = getTargetPlayerIndices(LeapMenuBridge.getPlayers())
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

    override fun onDisable() {
        super.onDisable()
        consecutiveWrongClicks = 0
        BossPhaseDetector.reset()
    }

    private fun isApplicable(screen: Screen): Boolean {
        if (screen !is AbstractContainerScreen<*>) return false
        if (! enabled || ! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return false
        if (! LeapMenuBridge.enabled) return false
        val title = screen.title.string.lowercase()
        return title.contains("spirit leap") || title.contains("teleport to player")
    }

    private fun classFromChoice(choice: Int): Set<DungeonClass> {
        if (choice <= 0) return emptySet()
        val c = DungeonClass.fromName(classOptions[choice])
        return if (c != DungeonClass.Empty) setOf(c) else emptySet()
    }

    fun getTargetClasses(): Set<DungeonClass> {
        if (! LocationUtils.inDungeon || ! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return emptySet()

        // 1. Misc Leaps (I4 / PD)
        if (miscEnabled.value) {
            if (i4Leap.value > 0 && BossPhaseDetector.isGoldorPhase && BossPhaseDetector.isI4Done) {
                return classFromChoice(i4Leap.value)
            }
            if (pdLeap.value > 0 && BossPhaseDetector.isStormPhase && BossPhaseDetector.isInP3Section()) {
                return classFromChoice(pdLeap.value)
            }
        }

        // 2. Boss-specific progression leaps
        return when {
            LocationUtils.F7Phase == 1 -> {
                if (p1Enabled.value && ! BossPhaseDetector.isMaxorDead) classFromChoice(p1Leap.value) else emptySet()
            }
            BossPhaseDetector.isStormPhase -> {
                if (! p2Enabled.value) return emptySet()
                val pos = mc.player?.position() ?: return emptySet()
                val choice = when {
                    purplePillarBox.contains(pos) -> purplePillarLeap.value
                    purplePadBox.contains(pos) -> purplePadLeap.value
                    acaciaSpotBox.contains(pos) -> acaciaSpotLeap.value
                    yellowPadBox.contains(pos) -> yellowPadLeap.value
                    yellowPillarBox.contains(pos) -> yellowPillarLeap.value
                    else -> 0
                }
                classFromChoice(choice)
            }
            BossPhaseDetector.isGoldorPhase -> {
                if (! p3Enabled.value) return emptySet()
                when (BossPhaseDetector.currentSection) {
                    1 -> resolveMultiTarget(BossPhaseDetector.s1LeapIndex, listOf(s1Leap1, s1Leap2, s1Leap3, s1Leap4), s1Count.value)
                    2 -> resolveMultiTarget(BossPhaseDetector.s2LeapIndex, listOf(s2Leap1, s2Leap2, s2Leap3, s2Leap4), s2Count.value)
                    3 -> resolveMultiTarget(BossPhaseDetector.s3LeapIndex, listOf(s3Leap1, s3Leap2, s3Leap3, s3Leap4), s3Count.value)
                    4 -> resolveMultiTarget(BossPhaseDetector.s4LeapIndex, listOf(s4Leap1, s4Leap2, s4Leap3), s4Count.value)
                    else -> emptySet()
                }
            }
            LocationUtils.F7Phase == 5 -> {
                if (p5Enabled.value) classFromChoice(relicLeap.value) else emptySet()
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

    fun getTargetPlayerIndices(players: Array<DungeonPlayer?> = LeapMenuBridge.getPlayers()): List<Int> {
        if (isCoreHighlightActive()) return emptyList()
        val targetClasses = getTargetClasses()
        if (targetClasses.isEmpty()) return emptyList()
        return players.mapIndexedNotNull { i, player ->
            if (player != null && ! player.isDead && player.clazz in targetClasses) i else null
        }
    }
}
