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
import com.github.noamm9.nexclusive.features.impl.nexclusive.ss.*
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import net.minecraft.core.BlockPos
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.hypot

object SSAimHelper: Feature(
    name = "SS Aim Helper",
    description = "Assists with Simon Says device solving using customizable aim modes."
) {
    val INSTANCE = this

    private val mode by DropdownSetting("Mode", 0, listOf("Classic Helper", "Threshold Lock", "Stop Movement", "Redirect Mode"))

    val classicRotationSpeed by SliderSetting("Rotation Speed", 12.0, 1.0, 50.0, 0.5).section("Classic Helper").showIf { mode.value == 0 }

    val lockThreshold by SliderSetting("Lock Threshold", 3.5, 0.5, 10.0, 0.5, "px").section("Threshold Lock").showIf { mode.value == 1 }
    val lockDelay by SliderSetting("Lock Delay", 30, 0, 200, 5, "ms").showIf { mode.value == 1 }
    val thresholdRotationSpeed by SliderSetting("Rotation Speed", 15.0, 1.0, 50.0, 0.5).showIf { mode.value == 1 }

    val stopDuration by SliderSetting("Stop Duration", 200, 20, 1000, 10, "ms").section("Stop Movement").showIf { mode.value == 2 }
    val stopDelay by SliderSetting("Stop Delay", 10, 0, 200, 5, "ms").showIf { mode.value == 2 }

    val speedMultiplier by SliderSetting("Speed Multiplier", 3.0, 0.5, 10.0, 0.5, "x").section("Redirect Mode").showIf { mode.value == 3 }
    val detailedSettings by ToggleSetting("Detailed Settings", false).showIf { mode.value == 3 }
    val holdDelay by SliderSetting("Hold Delay", 0, 0, 30, 1, "ms").showIf { mode.value == 3 && detailedSettings.value }
    val minimumSpeed by SliderSetting("Minimum Speed", 0.0, 0.0, 300.0, 5.0, "°/s").showIf { mode.value == 3 && detailedSettings.value }
    val maxSpeed by SliderSetting("Max Speed", 1200.0, 100.0, 3000.0, 50.0, "°/s").showIf { mode.value == 3 && detailedSettings.value }
    val smoothFinish by ToggleSetting("Smooth Finish", false).showIf { mode.value == 3 && detailedSettings.value }
    val finishDeceleration by SliderSetting("Finish Deceleration", 35.0, 5.0, 100.0, 1.0).showIf { mode.value == 3 && detailedSettings.value && smoothFinish.value }

    val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px").section("Aim Radius").showIf { mode.value != 2 }
    private val drawCircle by ToggleSetting("Draw Circle", true).showIf { mode.value != 2 }
    private val circleColor by ColorSetting("Circle Color", Color(0, 255, 255), true).showIf { mode.value != 2 && drawCircle.value }
    val dontAssistFirstClick by ToggleSetting("Dont assist first click", false)
    val aimingOffset by SliderSetting("Aiming Offset", 100, 20, 100, 5, "%")

    private val modes: Array<SSMode> = arrayOf(ClassicMode, ThresholdMode, StopMovementMode, RedirectMode)
    private val currentMode get() = modes.getOrNull(mode.value)

    private var lastTargetPos: BlockPos? = null
    private var hasAimed = false
    private var lastFrameTime = 0L

    private var isFirstClickInPhase = true
    private var wasInClickingPhase = false
    private var initialPhaseButton: BlockPos? = null

    val isThresholdLocking get() = ThresholdMode.isLocking

    val isFirstButton: Boolean
        get() {
            if (! isFirstClickInPhase) return false
            val num = SimonSaysBridge.getValidButtonNumber()
            if (num != null && num > 1) {
                isFirstClickInPhase = false
                return false
            }
            return true
        }

    fun shouldSuppressMouseInput(dx: Double, dy: Double): Boolean {
        if (! enabled || mc.screen != null || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice() || ! SimonSaysBridge.isDeviceInClickingPhase()) {
            resetTurnState()
            return false
        }

        if (dontAssistFirstClick.value && isFirstButton) {
            return false
        }

        val targetButton = SimonSaysBridge.getValidButton() ?: return false
        if (SimonSaysBridge.isCurrentTargetClicked()) {
            resetTurnState()
            return false
        }

        val inTarget = SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)
        return currentMode?.shouldSuppressMouseInput(dx, dy, targetButton, inTarget, hasAimed) ?: false
    }

    override fun init() {
        register<WorldChangeEvent> { resetAimState() }

        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            if (! enabled || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice()) return@register
            val current = SimonSaysBridge.getValidButton() ?: return@register
            if (event.pos == current) onTargetClicked()
        }

        register<MouseClickEvent> {
            if (! enabled || ! SimonSays.enabled || ! SimonSaysBridge.isAtSSDevice() || event.action != GLFW.GLFW_PRESS) return@register
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                val current = SimonSaysBridge.getValidButton() ?: return@register
                if (SimonSaysBridge.isLookingAtButton(current)) onTargetClicked()
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
                wasInClickingPhase = false
                isFirstClickInPhase = true
                initialPhaseButton = null
                return@register resetAimState()
            }

            val targetButton = SimonSaysBridge.getValidButton() ?: return@register resetAimState()

            if (! wasInClickingPhase) {
                wasInClickingPhase = true
                isFirstClickInPhase = true
                initialPhaseButton = targetButton
            }

            val now = System.currentTimeMillis()
            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            if (targetButton != lastTargetPos) {
                if (lastTargetPos != null && initialPhaseButton != null && targetButton != initialPhaseButton) {
                    isFirstClickInPhase = false
                }
                lastTargetPos = targetButton
                hasAimed = false
                resetTurnState()
                SimonSaysBridge.resetClickedState()
            }

            val inTarget = SimonSaysBridge.isInTargetArea(targetButton, aimingOffset.value)

            if (inTarget && (mc.options.keyUse.isDown || mc.options.keyAttack.isDown)) {
                onTargetClicked()
                return@register
            }

            if (SimonSaysBridge.isCurrentTargetClicked()) {
                hasAimed = false
                resetTurnState()
                return@register
            }

            if (dontAssistFirstClick.value && isFirstButton) return@register

            if (hasAimed && mode.value != 1) return@register

            val targetVec = SimonSaysBridge.getTargetPoint(targetButton)

            if (mode.value != 2) {
                val screenPos = ProjectionUtils.worldToScreen(targetVec) ?: return@register resetTurnState()
                val screenDist = hypot(
                    screenPos.x.toDouble() - (Resolution.width / 2.0),
                    screenPos.y.toDouble() - (Resolution.height / 2.0)
                )
                if (screenDist > helperRadius.value.toDouble()) return@register resetTurnState()
            }

            currentMode?.onRender(dt, targetButton, targetVec, inTarget, hasAimed) {
                hasAimed = true
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAimState()
    }

    private fun onTargetClicked() {
        isFirstClickInPhase = false
        SimonSaysBridge.markTargetClicked()
        hasAimed = false
        resetTurnState()
    }

    private fun resetTurnState() {
        modes.forEach { it.reset() }
    }

    private fun resetAimState() {
        lastTargetPos = null
        hasAimed = false
        lastFrameTime = 0L
        isFirstClickInPhase = true
        wasInClickingPhase = false
        initialPhaseButton = null
        resetTurnState()
        SimonSaysBridge.resetClickedState()
    }
}
