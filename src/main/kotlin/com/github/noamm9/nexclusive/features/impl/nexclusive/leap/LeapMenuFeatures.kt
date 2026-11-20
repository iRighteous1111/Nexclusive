package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.config.types.*
import com.github.noamm9.event.impl.*
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.IFeatureWindow
import com.github.noamm9.features.impl.dungeon.leapmenu.GridLeapRenderer
import com.github.noamm9.features.impl.dungeon.leapmenu.LeapMenu
import com.github.noamm9.nexclusive.ui.PlusMinusSetting
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object LeapMenuFeatures: Feature(
    name = "Leap Menu Features",
    description = "Enhances Noamm's Leap Menu with boss mode selections, targets, scaling, highlights, and protection."
), IFeatureWindow {
    override fun Feature.createWindow() = IFeatureWindow.CBW(this)
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

    private class P3Section(val count: PlusMinusSetting, val leaps: List<MultiCheckboxSetting>)

    private fun p3Section(index: Int, label: String, max: Int): P3Section {
        val visible = { selectedBoss.value == 2 && p3Enabled.value }
        val count = PlusMinusSetting(label, maxCount = max)
            .jsonName("s${index}_count")
            .showIf(visible)
            .apply(configSettings::add)
        val leaps = (1 .. max).map { step ->
            MultiCheckboxSetting("S$index Leap $step", defaultClasses())
                .jsonName("s${index}_target_$step")
                .showIf { visible() && count.value >= step }
                .apply(configSettings::add)
        }
        return P3Section(count, leaps)
    }

    private val p3Sections = listOf(
        p3Section(1, "§aSection 1 (S1)", 4),
        p3Section(2, "§eSection 2 (S2)", 4),
        p3Section(3, "§cSection 3 (S3)", 4),
        p3Section(4, "§bSection 4 (S4)", 3)
    )

    // --- P5 (Boss 3) ---
    private val p5Enabled by ToggleSetting("Enable P5", true).showIf { selectedBoss.value == 3 }
    private val relicLeap by DropdownSetting("Relic Leap", 0, classOptions).showIf { selectedBoss.value == 3 && p5Enabled.value }

    // --- Misc Leap (Boss 4) ---
    private val miscEnabled by ToggleSetting("Enable Misc Leap", true).showIf { selectedBoss.value == 4 }
    private val i4Leap by DropdownSetting("I4 Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }
    private val pdLeap by DropdownSetting("PD Leap", 0, classOptions).showIf { selectedBoss.value == 4 && miscEnabled.value }

    private var consecutiveWrongClicks = 0

    fun isCoreHighlightActive(): Boolean {
        if (! LeapMenu.highlightInCore.value) return false
        return DungeonListener.dungeonTeammatesNoSelf.any { LeapMenuRenderer.isPlayerInCore(it) }
    }

    override fun init() {
        register<WorldChangeEvent> { consecutiveWrongClicks = 0 }

        register<ScreenEvent.PreRender>(EventPriority.HIGHEST) {
            if (! isApplicable()) return@register

            BossPhaseDetector.recordLeapSection()
            LeapMenu.updateLeapMenu()
            val players = LeapMenu.players

            val targetIndices = getTargetPlayerIndices(players).toSet()
            if (targetIndices.isEmpty()) return@register

            event.isCanceled = true

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
            if (! isApplicable()) return@register
            if (! blockWrongClicks.value || event.button != 0) return@register

            val targetIndices = getTargetPlayerIndices(LeapMenu.players)
            if (targetIndices.isEmpty()) return@register
            val hoveredIndex = GridLeapRenderer.getHoveredIndex() ?: return@register

            if (hoveredIndex !in targetIndices) {
                consecutiveWrongClicks ++
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
    }

    private fun isApplicable() = LocationUtils.inBoss && LocationUtils.dungeonFloorNumber == 7 && LeapMenu.isActive()

    private fun classFromChoice(choice: Int): Set<DungeonClass> {
        if (choice <= 0) return emptySet()
        val c = DungeonClass.fromName(classOptions[choice])
        return if (c != DungeonClass.Empty) setOf(c) else emptySet()
    }

    fun getTargetClasses(): Set<DungeonClass> {
        if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return emptySet()

        // 1. Misc Leaps (I4 / PD)
        if (miscEnabled.value) {
            if (i4Leap.value > 0 && BossPhaseDetector.isGoldorPhase && BossPhaseDetector.isI4Done && ! BossPhaseDetector.leapedAfterI4) {
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
                val idx = BossPhaseDetector.currentSection - 1
                val section = p3Sections.getOrNull(idx) ?: return emptySet()
                resolveMultiTarget(BossPhaseDetector.getLeapIndex(idx), section.leaps, section.count.value)
            }
            LocationUtils.F7Phase == 5 -> {
                if (p5Enabled.value) classFromChoice(relicLeap.value) else emptySet()
            }
            else -> emptySet()
        }
    }

    private fun MultiCheckboxSetting.selectedClasses() =
        value.filterValues { it }.keys.map(DungeonClass::fromName).filterNot { it == DungeonClass.Empty }.toSet()

    private fun resolveMultiTarget(
        index: Int,
        steps: List<MultiCheckboxSetting>,
        limit: Int
    ): Set<DungeonClass> {
        val activeSteps = steps.take(limit)
        if (activeSteps.isEmpty()) return emptySet()
        return activeSteps.getOrElse(index) { activeSteps.last() }.selectedClasses()
    }

    fun getTargetPlayerIndices(players: Array<LeapMenu.LeapMenuPlayer?> = LeapMenu.players): List<Int> {
        if (isCoreHighlightActive()) return emptyList()
        val targetClasses = getTargetClasses()
        if (targetClasses.isEmpty()) return emptyList()
        return players.mapIndexedNotNull { i, entry ->
            val p = entry?.player
            if (p != null && ! p.isDead && p.clazz in targetClasses) i else null
        }
    }
}
