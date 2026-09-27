package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MouseClickEvent
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.devices.SimonSays
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import net.minecraft.core.BlockPos
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.hypot
import kotlin.math.max

object SSAimHelper: Feature(
    name = "SS Aim Helper",
    description = "Assists with Simon Says device solving using customizable aim modes."
) {
    val INSTANCE = this

    private val mode by DropdownSetting(
        "Mode", 0, listOf("Classic Helper", "Threshold Lock", "Stop Movement", "Redirect Mode")
    ).withDescription("Select the Simon Says aim assistance mode.")

    private val classicRotationSpeed by SliderSetting("Classic Rotation Speed", 12.0, 1.0, 50.0, 0.5)
        .section("Classic Helper Settings")
        .showIf { mode.value == 0 }
        .withDescription("Speed at which your crosshair rotates towards the button.")

    private val lockThreshold by SliderSetting("Lock Threshold", 3.5, 0.5, 10.0, 0.5, "px")
        .section("Threshold Lock Settings")
        .showIf { mode.value == 1 }
        .withDescription("Maximum mouse movement speed to trigger threshold lock.")

    private val lockDelay by SliderSetting("Lock Delay", 30, 0, 200, 5, "ms")
        .showIf { mode.value == 1 }
        .withDescription("Time the button must remain inside the circle to automatically lock aim directly onto it.")

    private val thresholdRotationSpeed by SliderSetting("Threshold Rotation Speed", 15.0, 1.0, 50.0, 0.5)
        .showIf { mode.value == 1 }
        .withDescription("Speed at which your crosshair rotates towards the button during threshold lock.")

    private val stopDuration by SliderSetting("Stop Duration", 200, 20, 1000, 10, "ms")
        .section("Stop Movement Settings")
        .showIf { mode.value == 2 }
        .withDescription("Duration to cut mouse input while hovering over the button hitbox.")

    private val stopDelay by SliderSetting("Stop Delay", 10, 0, 200, 5, "ms")
        .showIf { mode.value == 2 }
        .withDescription("Delay after looking at the button center area before stopping mouse movement.")

    private val centerAreaSize by SliderSetting("Center Area Size", 65, 30, 100, 5, "%")
        .showIf { mode.value == 2 }
        .withDescription("Size of the centered hitbox region required to trigger mouse stop. 100% = entire hitbox, 65% = middle area.")

    private val speedMultiplier by SliderSetting("Speed Multiplier", 3.0, 0.5, 10.0, 0.5, "x")
        .section("Redirect Mode Settings")
        .showIf { mode.value == 3 }
        .withDescription("Multiplier applied to your mouse flick speed for redirection.")

    private val minimumSpeed by SliderSetting("Minimum Speed", 80.0, 20.0, 300.0, 5.0, "°/s")
        .showIf { mode.value == 3 }
        .withDescription("Minimum rotation speed towards the button if flick was slow.")

    private val maxSpeed by SliderSetting("Max Speed", 700.0, 100.0, 1500.0, 25.0, "°/s")
        .showIf { mode.value == 3 }
        .withDescription("Maximum rotation speed cap during redirection.")

    private val smoothFinish by ToggleSetting("Smooth Finish", true)
        .showIf { mode.value == 3 }
        .withDescription("Gradually decelerates the crosshair as it nears the button to prevent abrupt stopping.")

    private val finishDeceleration by SliderSetting("Finish Deceleration", 35.0, 5.0, 100.0, 1.0)
        .showIf { mode.value == 3 && smoothFinish.value }
        .withDescription("Deceleration rate when smoothly finishing aim on the button.")

    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px")
        .section("Aim Radius")
        .showIf { mode.value != 2 }
        .withDescription("Radius of the circle on screen to detect the valid Simon Says button.")

    private val drawCircle by ToggleSetting("Draw Circle", true)
        .showIf { mode.value != 2 }
        .withDescription("Draws the detection circle centered on your crosshair.")

    private val circleColor by ColorSetting("Circle Color", Color(0, 255, 255), true)
        .showIf { mode.value != 2 && drawCircle.value }
        .withDescription("Color of the on-screen circle.")

    private var lastTargetPos: BlockPos? = null
    private var hasAimed = false
    private var lastFrameTime = 0L

    @Volatile var isThresholdLocking = false
        private set

    private var buttonInCircleTime = 0L
    private var hitboxHoverStartTime = 0L

    private var redirectActive = false
    private var redirectStartTime = 0L
    private var redirectCurrentSpeed = 0f
    private var recentMaxSpeed = 0f
    private var recentSpeedTime = 0L
    private var lastPhysicalMouseDelta = 0.0
    private var lastPhysicalMouseMoveTime = 0L

    private var prevYaw = 0f
    private var prevPitch = 0f

    fun shouldSuppressMouseInput(dx: Double, dy: Double): Boolean {
        if (! enabled) return false
        if (mc.screen != null || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice() || ! SimonSaysBridge.isDeviceInClickingPhase()) {
            isThresholdLocking = false
            redirectActive = false
            buttonInCircleTime = 0L
            return false
        }

        val targetButton = SimonSaysBridge.getValidButton() ?: return false
        if (SimonSaysBridge.isCurrentTargetClicked()) {
            isThresholdLocking = false
            redirectActive = false
            buttonInCircleTime = 0L
            return false
        }

        val now = System.currentTimeMillis()
        val mouseDelta = hypot(dx, dy)
        if (mouseDelta > 0.1) {
            lastPhysicalMouseDelta = mouseDelta
            lastPhysicalMouseMoveTime = now
        }

        val onHitbox = SimonSaysBridge.isLookingAtButton(targetButton)

        when (mode.value) {
            0 -> {
                if (onHitbox || hasAimed) return false
                val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: return false
                val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
                if (dist <= helperRadius.value.toDouble()) return true
            }

            1 -> {
                if (onHitbox || hasAimed) {
                    isThresholdLocking = false
                    buttonInCircleTime = 0L
                    return false
                }
                val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: run {
                    isThresholdLocking = false
                    buttonInCircleTime = 0L
                    return false
                }
                val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
                if (dist <= helperRadius.value.toDouble()) {
                    if (buttonInCircleTime == 0L) buttonInCircleTime = now
                    val isTimedLock = now - buttonInCircleTime >= lockDelay.value.toLong()
                    val lock = isTimedLock || mouseDelta <= lockThreshold.value
                    isThresholdLocking = lock
                    if (lock) return true
                } else {
                    buttonInCircleTime = 0L
                    isThresholdLocking = false
                }
            }

            2 -> {
                if (SimonSaysBridge.isInCenterArea(targetButton, centerAreaSize.value)) {
                    if (hitboxHoverStartTime == 0L) hitboxHoverStartTime = now
                    val elapsed = now - hitboxHoverStartTime
                    val delay = stopDelay.value.toLong()
                    if (elapsed in delay .. (delay + stopDuration.value.toLong())) return true
                } else {
                    hitboxHoverStartTime = 0L
                }
            }

            3 -> {
                if (onHitbox || hasAimed) {
                    redirectActive = false
                    return false
                }
                if (redirectActive) {
                    if (now - redirectStartTime <= 800L) return true
                    redirectActive = false
                }
                val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: return false
                val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
                if (dist <= helperRadius.value.toDouble()) return true
            }
        }

        return false
    }

    override fun init() {
        register<WorldChangeEvent> { resetAimState() }

        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            if (! enabled || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice()) return@register
            val current = SimonSaysBridge.getValidButton() ?: return@register
            if (event.pos == current) {
                SimonSaysBridge.markTargetClicked()
                resetTurnState()
            }
        }

        register<MouseClickEvent> {
            if (! enabled || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice() || event.action != GLFW.GLFW_PRESS) return@register
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                val current = SimonSaysBridge.getValidButton() ?: return@register
                if (SimonSaysBridge.isLookingAtButton(current)) {
                    SimonSaysBridge.markTargetClicked()
                    resetTurnState()
                }
            }
        }

        register<RenderOverlayEvent> {
            if (! SimonSays.enabled || mode.value == 2 || ! drawCircle.value) return@register
            if (! SimonSaysBridge.isAtSSDevice() && mc.screen == null) return@register

            Resolution.push(event.context)
            event.context.drawAnnularSegment(
                centerX = Resolution.width / 2f,
                centerY = Resolution.height / 2f,
                innerRadius = helperRadius.value.toFloat() - 1.2f,
                outerRadius = helperRadius.value.toFloat(),
                startAngle = 0.0,
                endAngle = Math.PI * 2.0,
                color = circleColor.value
            )
            Resolution.pop(event.context)
        }

        register<RenderWorldEvent> {
            if (mc.screen != null || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice()) return@register
            if (! SimonSaysBridge.isDeviceInClickingPhase()) {
                resetAimState()
                return@register
            }

            val targetButton = SimonSaysBridge.getValidButton()
            if (targetButton == null) {
                resetAimState()
                return@register
            }

            val now = System.currentTimeMillis()
            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            val currentYaw = player.yRot
            val currentPitch = player.xRot

            val velYaw = if (dt > 0.0) MathUtils.normalizeYaw(currentYaw - prevYaw) / dt.toFloat() else 0f
            val velPitch = if (dt > 0.0) MathUtils.normalizePitch(currentPitch - prevPitch) / dt.toFloat() else 0f
            val currentRotSpeed = hypot(velYaw.toDouble(), velPitch.toDouble()).toFloat()
            prevYaw = currentYaw
            prevPitch = currentPitch

            if (targetButton != lastTargetPos) {
                lastTargetPos = targetButton
                resetTurnState()
                SimonSaysBridge.resetClickedState()
            }

            val onHitbox = SimonSaysBridge.isLookingAtButton(targetButton)

            if (onHitbox && (mc.options.keyUse.isDown || mc.options.keyAttack.isDown)) {
                SimonSaysBridge.markTargetClicked()
                resetTurnState()
            }

            if (SimonSaysBridge.isCurrentTargetClicked()) {
                resetTurnState()
                return@register
            }

            if (mode.value == 2) {
                if (SimonSaysBridge.isInCenterArea(targetButton, centerAreaSize.value)) {
                    if (hitboxHoverStartTime == 0L) hitboxHoverStartTime = now
                } else {
                    hitboxHoverStartTime = 0L
                }
                return@register
            }

            if (onHitbox) {
                hasAimed = true
                resetTurnState()
                return@register
            }

            if (hasAimed && mode.value != 1) return@register

            val targetVec = SimonSaysBridge.getTargetPoint(targetButton)
            val screenPos = ProjectionUtils.worldToScreen(targetVec) ?: run {
                resetTurnState()
                return@register
            }

            val screenDist = hypot(
                screenPos.x.toDouble() - (Resolution.width / 2.0),
                screenPos.y.toDouble() - (Resolution.height / 2.0)
            )

            if (screenDist > helperRadius.value.toDouble()) {
                resetTurnState()
                return@register
            }

            val targetRot = MathUtils.calcYawPitch(targetVec)
            val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
            val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
            val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

            if (angularDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                hasAimed = true
                resetTurnState()
                return@register
            }

            when (mode.value) {
                0 -> {
                    val ratio = AimUtils.easedRatio(angularDist, classicRotationSpeed.value * 4.5, dt)

                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)
                    if (SimonSaysBridge.isLookingAtButton(targetButton)) hasAimed = true
                }

                1 -> {
                    if (buttonInCircleTime == 0L) buttonInCircleTime = now
                    if (now - buttonInCircleTime >= lockDelay.value.toLong()) isThresholdLocking = true
                    if (! isThresholdLocking) return@register

                    val ratio = AimUtils.easedRatio(angularDist, thresholdRotationSpeed.value * 4.5, dt)

                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)
                    if (SimonSaysBridge.isLookingAtButton(targetButton)) {
                        hasAimed = true
                        isThresholdLocking = false
                        buttonInCircleTime = 0L
                    }
                }

                3 -> {
                    if (! redirectActive) {
                        redirectActive = true
                        redirectStartTime = now
                        val flick = max(recentMaxSpeed, minimumSpeed.value.toFloat())
                        redirectCurrentSpeed = (flick * speedMultiplier.value.toFloat()).coerceIn(
                            minimumSpeed.value.toFloat(),
                            maxSpeed.value.toFloat()
                        )
                    }

                    if (now - lastPhysicalMouseMoveTime < 60L && lastPhysicalMouseDelta > 2.0) {
                        val boost = (lastPhysicalMouseDelta * 25.0 * speedMultiplier.value).toFloat()
                        redirectCurrentSpeed = max(redirectCurrentSpeed, boost).coerceAtMost(maxSpeed.value.toFloat())
                    }

                    if (now - redirectStartTime > 800L) {
                        redirectActive = false
                        return@register
                    }

                    if (smoothFinish.value) {
                        val decel = (finishDeceleration.value * 25.0).toFloat()
                        val stopDist = (redirectCurrentSpeed * redirectCurrentSpeed) / (2f * decel)
                        if (angularDist <= stopDist) {
                            redirectCurrentSpeed = (redirectCurrentSpeed - decel * dt.toFloat())
                                .coerceAtLeast(minimumSpeed.value.toFloat() * 0.8f)
                        }
                    }

                    val step = (redirectCurrentSpeed * dt.toFloat()).coerceIn(0.05f, angularDist)
                    val ratio = (step / angularDist).coerceIn(0f, 1f)
                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)

                    if (SimonSaysBridge.isLookingAtButton(targetButton)) {
                        redirectActive = false
                        hasAimed = true
                    }
                }
            }

            if (! redirectActive) {
                if (currentRotSpeed > recentMaxSpeed || now - recentSpeedTime > 250L) {
                    recentMaxSpeed = max(currentRotSpeed, 60.0f)
                    recentSpeedTime = now
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAimState()
    }

    private fun resetTurnState() {
        hasAimed = false
        isThresholdLocking = false
        buttonInCircleTime = 0L
        redirectActive = false
        redirectStartTime = 0L
        hitboxHoverStartTime = 0L
    }

    private fun resetAimState() {
        lastTargetPos = null
        lastFrameTime = 0L
        recentMaxSpeed = 0f
        recentSpeedTime = 0L
        lastPhysicalMouseDelta = 0.0
        lastPhysicalMouseMoveTime = 0L
        prevYaw = 0f
        prevPitch = 0f
        resetTurnState()
        SimonSaysBridge.resetClickedState()
    }
}
