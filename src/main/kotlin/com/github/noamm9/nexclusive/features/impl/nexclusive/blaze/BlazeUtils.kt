package com.github.noamm9.nexclusive.features.impl.nexclusive.blaze

import com.github.noamm9.config.types.*
import com.github.noamm9.event.impl.*
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import com.github.noamm9.nexclusive.utils.render.CircleRenderer.drawAimCircle
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderVec
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderTracer
import com.github.noamm9.utils.render.world.RenderContext
import net.minecraft.world.phys.Vec3
import java.awt.Color

object BlazeUtils: Feature(
    name = "Blaze Utils",
    description = "Blaze puzzle aim helper, optimal spot highlight, triggerbot, and wrong click blocker."
) {
    private val aimHelper by ToggleSetting("Enable Aim Helper", true).section("Aim Helper").withDescription("Automatically aims at the correct blaze inside the detection circle.")
    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px").withDescription("Screen radius of the circle to detect and aim at the correct blaze.").showIf { aimHelper.value }
    private val drawCircle by ToggleSetting("Draw Circle", true).withDescription("Draws the detection circle while inside the Blaze room.").showIf { aimHelper.value }
    private val circleColor by ColorSetting("Circle Color", Color(255, 170, 0, 140), true).withDescription("Color and transparency of the detection circle.").showIf { aimHelper.value && drawCircle.value }
    private val hitboxOffset by SliderSetting("Hitbox Offset", 0.0, - 1.0, 1.0, 0.05).withDescription("Vertical target offset: 0 is center, + is upper half up to top, - is lower half down to bottom.").showIf { aimHelper.value }
    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1).withDescription("Speed at which your crosshair rotates towards the blaze.").showIf { aimHelper.value }
    private val aimDuration by SliderSetting("Aim Duration", 0.5, 0.0, 5.0, 0.1, "s").withDescription("Duration to hold aim on the blaze before releasing.").showIf { aimHelper.value }
    private val aimCooldown by SliderSetting("Aim Cooldown", 1.0, 0.0, 5.0, 0.1, "s").withDescription("Time before re-aiming at the same blaze. Set to 0 to continuously aim.").showIf { aimHelper.value }

    private val highlightOptimalSpot by ToggleSetting("Highlight Optimal Spot", false).section("Extras").withDescription("Highlights the optimal standing block for Lower and Higher Blaze.")
    private val highlightedBlockColor by ColorSetting("Highlighted Block Color", Color(0, 255, 255, 110), true).withDescription("Color of the highlighted optimal spot block.").showIf { highlightOptimalSpot.value && ! chroma.value }
    private val chroma by ToggleSetting("Chroma", false).withDescription("Cycles the highlighted block and tracer through RGB colors.").showIf { highlightOptimalSpot.value }
    private val drawTracer by ToggleSetting("Draw Tracer", false).withDescription("Draws a tracer from your crosshair to the optimal spot until you stand on it.").showIf { highlightOptimalSpot.value }

    private val blazeTriggerbot by ToggleSetting("Blaze Triggerbot", false).withDescription("Automatically shoots once with Terminator when aiming at the correct blaze.")
    private val interactDelay by SliderSetting("Interact Delay", 2, 0, 10, 1, "t").withDescription("Game ticks to wait after aiming at the blaze before shooting.").showIf { blazeTriggerbot.value }
    private val prefireTicks by SliderSetting("Prefire Ticks", 3, 0, 10, 1, "t").withDescription("Ticks before the previous shot blaze dies to prefire the next blaze.").showIf { blazeTriggerbot.value }

    private val blockWrongClicks by ToggleSetting("Block Wrong Clicks", false).withDescription("Prevents shooting if aiming at a wrong blaze or if the arrow would hit another blaze behind it.")
    private val overrideClicks by SliderSetting("Override Clicks", 2, 1, 10, 1).withDescription("Consecutive clicks required to override and shoot anyway.").showIf { blockWrongClicks.value }

    private val blazeAimedTimes = HashMap<Int, Long>()
    private var currentAimingBlazeId: Int? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L

    private var triggerbotHoverTicks = 0
    private var lastHoveredBlazeId: Int? = null
    private var isTriggerbotClicking = false
    private var consecutiveWrongClicks = 0

    override fun init() {
        register<WorldChangeEvent> { resetAll() }
        register<DungeonEvent.RoomEvent.onExit> { if (event.room.name.contains("Blaze", ignoreCase = true)) resetAll() }

        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST) { onInteract(event, false) }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST) { onInteract(event, false) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST) { onInteract(event, false) }
        register<PlayerInteractEvent.LEFT_CLICK.AIR>(EventPriority.HIGHEST) { onInteract(event, true) }
        register<PlayerInteractEvent.LEFT_CLICK.ENTITY>(EventPriority.HIGHEST) { onInteract(event, true) }
        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST) { onInteract(event, true) }

        register<TickEvent.Start> {
            BlazeTracker.currentTick ++
            if (! BlazeTracker.updateRoomState()) return@register
            BlazeTracker.scanBlazes(hitboxOffset.value)
            tickTriggerbot()
        }

        register<RenderOverlayEvent> {
            if (! aimHelper.value || ! drawCircle.value || mc.gui.screen() != null) return@register
            if (! BlazeTracker.inBlazeRoom || LocationUtils.inBoss || ! BlazeTracker.isHoldingBow()) return@register
            event.drawAimCircle(helperRadius.value, circleColor.value)
        }

        register<RenderWorldEvent> {
            if (! BlazeTracker.inBlazeRoom || LocationUtils.inBoss) return@register
            if (highlightOptimalSpot.value) renderOptimalSpot(event.ctx)
            renderAimHelper()
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAll()
    }

    private fun onInteract(event: PlayerInteractEvent, isLeftClick: Boolean) {
        if (! BlazeTracker.inBlazeRoom || BlazeTracker.blazes.isEmpty() || ! BlazeTracker.isHoldingBow()) return
        if (isLeftClick && player.mainHandItem.skyblockId != "TERMINATOR") return
        if (isTriggerbotClicking) return

        val eyePos = player.eyePosition

        if (blockWrongClicks.value && ! BlazeTracker.isShotSafe(eyePos, hitboxOffset.value)) {
            consecutiveWrongClicks ++
            if (consecutiveWrongClicks < overrideClicks.value) {
                event.cancel()
                val needed = overrideClicks.value - consecutiveWrongClicks
                ChatUtils.modMessage("§cBlocked wrong blaze shot! Click §e$needed §cmore time(s) to override.")
                return
            }
            consecutiveWrongClicks = 0
            return
        }

        consecutiveWrongClicks = 0
        if (System.currentTimeMillis() - BlazeTracker.lastShotTime < 100L) return

        val target = BlazeTracker.targetBlaze ?: return
        val isLooking = BlazeTracker.isLookingAtBlaze(target, eyePos)
        val screenPos = ProjectionUtils.worldToScreen(target.position().add(0.0, target.bbHeight / 2.0, 0.0))
        val isNearScreen = aimHelper.value && CircleRenderer.isInsideCircle(screenPos, helperRadius.value)

        if (isLooking || isNearScreen) markShot(target)
    }

    private fun tickTriggerbot() {
        if (! blazeTriggerbot.value || mc.gui.screen() != null || ! BlazeTracker.isHoldingBow()) {
            return resetTriggerbot()
        }

        val target = BlazeTracker.targetBlaze
        if (target == null || ! BlazeTracker.isShotSafe(player.eyePosition, hitboxOffset.value)) {
            return resetTriggerbot()
        }

        if (lastHoveredBlazeId != target.id) {
            lastHoveredBlazeId = target.id
            triggerbotHoverTicks = 0
        } else {
            triggerbotHoverTicks ++
        }

        if (triggerbotHoverTicks < interactDelay.value) return
        if (! BlazeTracker.canPrefire(prefireTicks.value)) return

        isTriggerbotClicking = true
        PlayerUtils.rightClick()
        isTriggerbotClicking = false
        markShot(target)
    }

    private fun renderOptimalSpot(ctx: RenderContext) {
        val spot = BlazeTracker.getOptimalSpot() ?: return
        val baseColor = if (chroma.value) Color.getHSBColor((System.currentTimeMillis() % 4000L) / 4000f, 1f, 1f) else highlightedBlockColor.value
        val fillAlpha = if (chroma.value) 110 else baseColor.alpha

        ctx.renderBlock(
            spot,
            outlineColor = baseColor.withAlpha(255),
            fillColor = baseColor.withAlpha(fillAlpha),
            outline = true,
            fill = true,
            phase = true
        )

        if (drawTracer.value && ! BlazeTracker.isStandingOnSpot(spot)) {
            ctx.renderTracer(Vec3.atCenterOf(spot), baseColor.withAlpha(255))
        }
    }

    private fun renderAimHelper() {
        if (! aimHelper.value || mc.gui.screen() != null || ! BlazeTracker.isHoldingBow()) return resetAimState()
        val target = BlazeTracker.targetBlaze?.takeIf { it.isAlive && ! it.isRemoved && BlazeTracker.targetHasLineOfSight } ?: return resetAimState()

        val eyePos = player.renderVec.add(0.0, player.eyeHeight.toDouble(), 0.0)
        if (BlazeTracker.isLookingAtBlaze(target, eyePos)) {
            aimLockStartTime = 0L
            lastFrameTime = 0L
            return
        }

        val targetPos = BlazeTracker.getTargetPos(target, hitboxOffset.value)
        val screenPos = ProjectionUtils.worldToScreen(targetPos)
        if (! CircleRenderer.isInsideCircle(screenPos, helperRadius.value)) return resetAimState()

        val now = System.currentTimeMillis()
        val cooldownMs = (aimCooldown.value * 1000.0).toLong()
        val aimDurationMs = (aimDuration.value * 1000.0).toLong()

        if (cooldownMs > 0L && now - (blazeAimedTimes[target.id] ?: 0L) < cooldownMs) return

        if (target.id != currentAimingBlazeId) {
            currentAimingBlazeId = target.id
            aimLockStartTime = 0L
            lastFrameTime = 0L
        }

        if (aimLockStartTime > 0L) {
            if (now - aimLockStartTime < aimDurationMs) return AimUtils.rotateTo(targetPos)
            blazeAimedTimes[target.id] = now
            return resetAimState()
        }

        val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
        lastFrameTime = now

        if (AimUtils.rotateTowards(targetPos, rotationSpeed.value, dt)) {
            aimLockStartTime = now
            if (aimDurationMs <= 0L) {
                blazeAimedTimes[target.id] = now
                resetAimState()
            }
        }
    }

    private fun markShot(target: net.minecraft.world.entity.monster.Blaze) {
        BlazeTracker.recordShot(target, hitboxOffset.value)
        resetAimState()
        resetTriggerbot()
    }

    private fun resetTriggerbot() {
        triggerbotHoverTicks = 0
        lastHoveredBlazeId = null
    }

    private fun resetAimState() {
        currentAimingBlazeId = null
        aimLockStartTime = 0L
        lastFrameTime = 0L
    }

    private fun resetAll() {
        resetAimState()
        resetTriggerbot()
        blazeAimedTimes.clear()
        BlazeTracker.reset()
        isTriggerbotClicking = false
        consecutiveWrongClicks = 0
    }
}
