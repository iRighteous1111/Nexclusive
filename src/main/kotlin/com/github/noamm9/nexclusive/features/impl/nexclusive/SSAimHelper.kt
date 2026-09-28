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
import kotlin.math.pow

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

    private val speedMultiplier by SliderSetting("Speed Multiplier", 3.0, 0.5, 10.0, 0.5, "x")
        .section("Redirect Mode Settings")
        .showIf { mode.value == 3 }
        .withDescription("Multiplier applied to your mouse flick speed for redirection.")

    private val detailedSettings by ToggleSetting("Detailed Settings", false)
        .showIf { mode.value == 3 }
        .withDescription("Shows advanced settings for Redirect Mode.")

    private val holdDelay by SliderSetting("Hold Delay", 0, 0, 30, 1, "ms")
        .showIf { mode.value == 3 && detailedSettings.value }
        .withDescription("Duration to hold aim on the button before releasing mouse control.")

    private val minimumSpeed by SliderSetting("Minimum Speed", 0.0, 0.0, 300.0, 5.0, "°/s")
        .showIf { mode.value == 3 && detailedSettings.value }
        .withDescription("Minimum rotation speed towards the button while moving mouse.")

    private val maxSpeed by SliderSetting("Max Speed", 1200.0, 100.0, 3000.0, 50.0, "°/s")
        .showIf { mode.value == 3 && detailedSettings.value }
        .withDescription("Maximum rotation speed cap during redirection.")

    private val smoothFinish by ToggleSetting("Smooth Finish", false)
        .showIf { mode.value == 3 && detailedSettings.value }
        .withDescription("Gradually decelerates rotation as crosshair nears the button.")

    private val finishDeceleration by SliderSetting("Finish Deceleration", 35.0, 5.0, 100.0, 1.0)
        .showIf { mode.value == 3 && detailedSettings.value && smoothFinish.value }
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

    private val aimingOffset by SliderSetting("Aiming Offset", 100, 20, 100, 5, "%")
        .withDescription("Hitbox area percentage required to finish aiming. 100% stops at hitbox edge, 65% aims towards center area.")

    private var lastTargetPos: BlockPos? = null
    private var hasAimed = false
    private var lastFrameTime = 0L

    @Volatile var isThresholdLocking = false
        private set

    private var buttonInCircleTime = 0L
    private var hitboxHoverStartTime = 0L
    private var targetHoldStartTime = 0L

    private var redirectActive = false
    private var pendingRedirectDelta = 0.0
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
            lastPhysicalMouseMoveTime = now
        }

        val inTarget = SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)

        when (mode.value) {
            0 -> {
                if (inTarget || hasAimed) return false
                val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: return false
                val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
                if (dist <= helperRadius.value.toDouble()) return true
            }

            1 -> {
                if (inTarget || hasAimed) {
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
                if (inTarget) {
                    if (hitboxHoverStartTime == 0L) hitboxHoverStartTime = now
                    val elapsed = now - hitboxHoverStartTime
                    val delay = stopDelay.value.toLong()
                    if (elapsed in delay .. (delay + stopDuration.value.toLong())) return true
                } else {
                    hitboxHoverStartTime = 0L
                }
            }

            3 -> {
                if (hasAimed) {
                    redirectActive = false
                    return false
                }
                if (inTarget) {
                    if (detailedSettings.value && holdDelay.value > 0) {
                        if (targetHoldStartTime == 0L) targetHoldStartTime = now
                        if (now - targetHoldStartTime < holdDelay.value.toLong()) {
                            return true
                        }
                    }
                    redirectActive = false
                    return false
                }
                val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: run {
                    redirectActive = false
                    return false
                }
                val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
                if (dist <= helperRadius.value.toDouble()) {
                    redirectActive = true
                    pendingRedirectDelta += mouseDelta
                    return true
                } else {
                    redirectActive = false
                }
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
                hasAimed = false
                resetTurnState()
            }
        }

        register<MouseClickEvent> {
            if (! enabled || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice() || event.action != GLFW.GLFW_PRESS) return@register
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                val current = SimonSaysBridge.getValidButton() ?: return@register
                if (SimonSaysBridge.isLookingAtButton(current)) {
                    SimonSaysBridge.markTargetClicked()
                    hasAimed = false
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

            prevYaw = currentYaw
            prevPitch = currentPitch

            if (targetButton != lastTargetPos) {
                lastTargetPos = targetButton
                hasAimed = false
                resetTurnState()
                SimonSaysBridge.resetClickedState()
            }

            val inTarget = SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)

            if (inTarget && (mc.options.keyUse.isDown || mc.options.keyAttack.isDown)) {
                SimonSaysBridge.markTargetClicked()
                hasAimed = false
                resetTurnState()
            }

            if (SimonSaysBridge.isCurrentTargetClicked()) {
                hasAimed = false
                resetTurnState()
                return@register
            }

            if (mode.value == 2) {
                if (inTarget) {
                    if (hitboxHoverStartTime == 0L) hitboxHoverStartTime = now
                } else {
                    hitboxHoverStartTime = 0L
                }
                return@register
            }

            val targetVec = SimonSaysBridge.getTargetPoint(targetButton)

            if (inTarget) {
                if (mode.value == 3 && detailedSettings.value && holdDelay.value > 0) {
                    if (targetHoldStartTime == 0L) targetHoldStartTime = now
                    if (now - targetHoldStartTime < holdDelay.value.toLong()) {
                        val targetRot = MathUtils.calcYawPitch(targetVec)
                        PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                        return@register
                    }
                }
                hasAimed = true
                redirectActive = false
                isThresholdLocking = false
                targetHoldStartTime = 0L
                return@register
            }

            if (hasAimed && mode.value != 1) return@register

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

            if (angularDist <= 0.25f && mode.value != 3) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                hasAimed = true
                redirectActive = false
                isThresholdLocking = false
                return@register
            }

            when (mode.value) {
                0 -> {
                    val ratio = AimUtils.easedRatio(angularDist, classicRotationSpeed.value * 4.5, dt)

                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)
                    if (SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)) hasAimed = true
                }

                1 -> {
                    if (buttonInCircleTime == 0L) buttonInCircleTime = now
                    if (now - buttonInCircleTime >= lockDelay.value.toLong()) isThresholdLocking = true
                    if (! isThresholdLocking) return@register

                    val ratio = AimUtils.easedRatio(angularDist, thresholdRotationSpeed.value * 4.5, dt)

                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)
                    if (SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)) {
                        hasAimed = true
                        isThresholdLocking = false
                        buttonInCircleTime = 0L
                    }
                }

                3 -> {
                    val delta = pendingRedirectDelta
                    pendingRedirectDelta = 0.0

                    if (delta <= 0.001) return@register

                    val f = mc.options.sensitivity().get().toFloat() * 0.6f + 0.2f
                    val gcd = f * f * f * 1.2f
                    val inputDegrees = delta.toFloat() * gcd
                    var step = inputDegrees * speedMultiplier.value.toFloat()

                    if (detailedSettings.value) {
                        if (minimumSpeed.value > 0.0) {
                            val minStep = (minimumSpeed.value.toFloat() * dt.toFloat())
                            if (step < minStep) step = minStep
                        }
                        val maxStep = (maxSpeed.value.toFloat() * dt.toFloat())
                        if (step > maxStep) step = maxStep
                        if (smoothFinish.value && angularDist < 2.5f) {
                            val ease = (angularDist / 2.5f).coerceIn(0.15f, 1.0f)
                            val rate = (finishDeceleration.value.toFloat() / 35.0f).coerceIn(0.5f, 2.5f)
                            step *= ease.pow(rate)
                        }
                    }

                    val ratio = (step / angularDist).coerceIn(0f, 1f)
                    PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)

                    if (SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value) || ratio >= 1f) {
                        if (detailedSettings.value && holdDelay.value > 0) {
                            if (targetHoldStartTime == 0L) targetHoldStartTime = now
                        } else {
                            hasAimed = true
                            redirectActive = false
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAimState()
    }

    private fun resetTurnState() {
        isThresholdLocking = false
        buttonInCircleTime = 0L
        redirectActive = false
        hitboxHoverStartTime = 0L
        targetHoldStartTime = 0L
        pendingRedirectDelta = 0.0
    }

    private fun resetAimState() {
        lastTargetPos = null
        hasAimed = false
        lastFrameTime = 0L
        lastPhysicalMouseMoveTime = 0L
        prevYaw = 0f
        prevPitch = 0f
        resetTurnState()
        SimonSaysBridge.resetClickedState()
    }
}
