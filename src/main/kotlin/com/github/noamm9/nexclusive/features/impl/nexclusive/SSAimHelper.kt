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
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.lang.reflect.Field
import kotlin.math.*

object SSAimHelper : Feature(
    name = "SS Aim Helper",
    description = "Assists with Simon Says device solving using customizable aim modes."
) {
    val INSTANCE = this

    private val mode by DropdownSetting(
        "Mode", 0, listOf("Classic Helper", "Threshold Lock", "Stop Movement", "Redirect Mode")
    ).withDescription("Select the Simon Says aim assistance mode.")

    // Circle Settings (visible for modes using circle: Classic Helper, Threshold Lock, Redirect Mode)
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

    // Mode 0: Classic Helper Settings
    private val classicRotationSpeed by SliderSetting("Classic Rotation Speed", 12.0, 1.0, 50.0, 0.5)
        .section("Classic Helper Settings")
        .showIf { mode.value == 0 }
        .withDescription("Speed at which your crosshair rotates towards the button.")

    // Mode 1: Threshold Lock Settings
    private val lockThreshold by SliderSetting("Lock Threshold", 3.5, 0.5, 10.0, 0.5, "px")
        .section("Threshold Lock Settings")
        .showIf { mode.value == 1 }
        .withDescription("Maximum mouse movement speed to trigger threshold lock.")

    private val thresholdRotationSpeed by SliderSetting("Threshold Rotation Speed", 15.0, 1.0, 50.0, 0.5)
        .showIf { mode.value == 1 }
        .withDescription("Speed at which your crosshair rotates towards the button during threshold lock.")

    // Mode 2: Stop Movement Settings
    private val stopDuration by SliderSetting("Stop Duration", 200, 20, 1000, 10, "ms")
        .section("Stop Movement Settings")
        .showIf { mode.value == 2 }
        .withDescription("Duration to cut mouse input while hovering over the button hitbox.")

    private val stopDelay by SliderSetting("Stop Delay", 10, 0, 200, 5, "ms")
        .showIf { mode.value == 2 }
        .withDescription("Delay after looking at the button before stopping mouse movement.")

    // Mode 3: Redirect Mode Settings
    private val smoothFinish by ToggleSetting("Smooth Finish", true)
        .section("Redirect Mode Settings")
        .showIf { mode.value == 3 }
        .withDescription("Gradually decelerates the crosshair as it nears the button to prevent abrupt stopping.")

    private val finishDeceleration by SliderSetting("Finish Deceleration", 30.0, 5.0, 80.0, 1.0)
        .showIf { mode.value == 3 && smoothFinish.value }
        .withDescription("Deceleration rate when smoothly finishing aim on the button.")

    private val speedMultiplier by SliderSetting("Speed Multiplier", 1.0, 0.2, 3.0, 0.1, "x")
        .showIf { mode.value == 3 }
        .withDescription("Multiplier applied to your mouse flick speed for redirection.")

    private val minimumSpeed by SliderSetting("Minimum Speed", 15.0, 5.0, 50.0, 1.0, "°/s")
        .showIf { mode.value == 3 }
        .withDescription("Minimum rotation speed towards the button if flick was slow.")

    private val deviceCenter = Vec3(110.5, 121.5, 93.5)

    private val solutionField by lazy {
        runCatching {
            SimonSays::class.java.getDeclaredField("solution").apply { isAccessible = true }
        }.getOrNull()
    }

    private var buttonFieldCache: Field? = null
    private var lastTargetPos: BlockPos? = null
    private var hasAimed = false
    private var lastFrameTime = 0L

    @Volatile
    var isThresholdLocking = false
        private set

    // Stop Movement state
    private var hitboxHoverStartTime = 0L

    // Redirect Mode state
    private var redirectActive = false
    private var redirectStartTime = 0L
    private var redirectCurrentSpeed = 0f
    private var recentSpeed = 0f

    private var prevYaw = 0f
    private var prevPitch = 0f

    // Click tracking to eliminate transition latency
    private var clickedSSButton: Any? = null
    private var clickedButtonPos: BlockPos? = null
    private var clickedSolutionSize = -1
    private var clickTime = 0L

    /**
     * Checks if the player is currently on the Simon Says device platform in F7 Phase 3.
     */
    fun isAtSSDevice(): Boolean {
        val p = mc.player ?: return false
        return LocationUtils.F7Phase == 3 && p.position().distanceToSqr(deviceCenter) <= 49.0
    }

    fun getSolutionList(): List<*>? {
        if (!SimonSays.enabled) return null
        return runCatching {
            solutionField?.get(SimonSays) as? List<*>
        }.getOrNull()
    }

    fun getValidSSButton(): Any? {
        return getSolutionList()?.firstOrNull()
    }

    /**
     * Retrieves the current valid button to press from Noamm's SimonSays solver.
     * Returns null if SimonSays is disabled, empty, or inaccessible.
     */
    fun getValidButton(): BlockPos? {
        val first = getValidSSButton() ?: return null
        return runCatching {
            if (buttonFieldCache == null) {
                buttonFieldCache = first.javaClass.getDeclaredField("button").apply {
                    isAccessible = true
                }
            }
            buttonFieldCache?.get(first) as? BlockPos
        }.getOrNull()
    }

    fun markTargetClicked() {
        val currentObj = getValidSSButton() ?: return
        val currentPos = getValidButton() ?: return
        clickedSSButton = currentObj
        clickedButtonPos = currentPos
        clickedSolutionSize = getSolutionList()?.size ?: -1
        clickTime = System.currentTimeMillis()

        isThresholdLocking = false
        hitboxHoverStartTime = 0L
        redirectActive = false
        hasAimed = false
    }

    fun isCurrentTargetClicked(): Boolean {
        if (clickedSSButton == null && clickedButtonPos == null) return false
        val now = System.currentTimeMillis()
        if (now - clickTime > 600L) {
            clickedSSButton = null
            clickedButtonPos = null
            clickedSolutionSize = -1
            return false
        }

        val currentList = getSolutionList() ?: return false
        val currentObj = currentList.firstOrNull() ?: return false
        val currentPos = getValidButton() ?: return false

        // If solution list shrank or head changed, Simon Says advanced to next button!
        if (currentList.size != clickedSolutionSize || currentObj !== clickedSSButton) {
            clickedSSButton = null
            clickedButtonPos = null
            clickedSolutionSize = -1
            return false
        }

        return currentPos == clickedButtonPos
    }

    /**
     * Checks if the player's crosshair is currently looking at the target button's hitbox.
     */
    fun isLookingAtButton(targetButton: BlockPos): Boolean {
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == targetButton) {
            return true
        }

        val player = mc.player ?: return false
        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        val reach = 6.0
        val endVec = eyePos.add(lookVec.scale(reach))

        val aabb = AABB(
            targetButton.x + 0.85,
            targetButton.y + 0.35,
            targetButton.z + 0.30,
            targetButton.x + 1.02,
            targetButton.y + 0.65,
            targetButton.z + 0.70
        )
        return aabb.clip(eyePos, endVec).isPresent
    }

    /**
     * Calculates the target 3D world point on the button (near center of hitbox).
     */
    fun getTargetPoint(targetButton: BlockPos): Vec3 {
        return Vec3(targetButton.x + 0.9, targetButton.y + 0.5, targetButton.z + 0.5)
    }

    /**
     * Determines whether user mouse movement should be cut off in [MouseHandler.turnPlayer].
     */
    fun shouldSuppressMouseInput(dx: Double, dy: Double): Boolean {
        if (!enabled) return false
        if (mc.screen != null || !SimonSays.enabled || !isAtSSDevice()) {
            isThresholdLocking = false
            redirectActive = false
            return false
        }

        val targetButton = getValidButton() ?: return false
        if (isCurrentTargetClicked()) {
            isThresholdLocking = false
            redirectActive = false
            return false
        }

        val now = System.currentTimeMillis()
        val onHitbox = isLookingAtButton(targetButton)

        when (mode.value) {
            // Mode 0: Classic Helper
            0 -> {
                if (onHitbox || hasAimed) return false
                val targetVec = getTargetPoint(targetButton)
                val screenPos = worldToScreen(targetVec) ?: return false
                val screenDist = hypot(
                    screenPos.x.toDouble() - (Resolution.width / 2.0),
                    screenPos.y.toDouble() - (Resolution.height / 2.0)
                )
                if (screenDist <= helperRadius.value.toDouble()) {
                    return true
                }
            }

            // Mode 1: Threshold Lock
            1 -> {
                if (onHitbox || hasAimed) {
                    isThresholdLocking = false
                    return false
                }
                val targetVec = getTargetPoint(targetButton)
                val screenPos = worldToScreen(targetVec) ?: run {
                    isThresholdLocking = false
                    return false
                }
                val screenDist = hypot(
                    screenPos.x.toDouble() - (Resolution.width / 2.0),
                    screenPos.y.toDouble() - (Resolution.height / 2.0)
                )
                if (screenDist <= helperRadius.value.toDouble()) {
                    val mouseDelta = hypot(dx, dy)
                    val isSlowOrStill = mouseDelta <= lockThreshold.value
                    isThresholdLocking = isSlowOrStill
                    if (isSlowOrStill) return true
                } else {
                    isThresholdLocking = false
                }
            }

            // Mode 2: Stop Movement
            2 -> {
                if (onHitbox) {
                    if (hitboxHoverStartTime == 0L) {
                        hitboxHoverStartTime = now
                    }
                    val elapsed = now - hitboxHoverStartTime
                    val delay = stopDelay.value.toLong()
                    val duration = stopDuration.value.toLong()
                    if (elapsed >= delay && elapsed <= delay + duration) {
                        return true
                    }
                } else {
                    hitboxHoverStartTime = 0L
                }
            }

            // Mode 3: Redirect Mode
            3 -> {
                if (onHitbox || hasAimed) {
                    redirectActive = false
                    return false
                }
                if (redirectActive) {
                    if (now - redirectStartTime <= 600L) {
                        return true
                    } else {
                        redirectActive = false
                    }
                }
                val targetVec = getTargetPoint(targetButton)
                val screenPos = worldToScreen(targetVec) ?: return false
                val screenDist = hypot(
                    screenPos.x.toDouble() - (Resolution.width / 2.0),
                    screenPos.y.toDouble() - (Resolution.height / 2.0)
                )
                if (screenDist <= helperRadius.value.toDouble()) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Projects a 3D world position into 2D screen coordinates based on current camera angles and FOV.
     * Returns null if the target is behind the player's view.
     */
    private fun worldToScreen(target: Vec3): Vec2? {
        val player = mc.player ?: return null
        val eyePos = player.renderVec.add(0.0, player.eyeHeight.toDouble(), 0.0)

        val dx = target.x - eyePos.x
        val dy = target.y - eyePos.y
        val dz = target.z - eyePos.z

        val yawRad = Math.toRadians(player.yRot.toDouble())
        val pitchRad = Math.toRadians(player.xRot.toDouble())

        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)

        // Minecraft camera vectors
        val fx = - sinYaw * cosPitch
        val fy = - sinPitch
        val fz = cosYaw * cosPitch

        val rx = cosYaw
        val ry = 0.0
        val rz = sinYaw

        val ux = - sinPitch * sinYaw
        val uy = cosPitch
        val uz = sinPitch * cosYaw

        // Relative coordinates in camera space
        val xCam = dx * rx + dy * ry + dz * rz
        val yCam = dx * ux + dy * uy + dz * uz
        val zCam = dx * fx + dy * fy + dz * fz

        // Behind the near plane / player view
        if (zCam <= 0.05) return null

        val fov = mc.options.fov().get().toDouble()
        val fovRad = Math.toRadians(fov)
        val halfHeight = Resolution.height / 2.0
        val halfWidth = Resolution.width / 2.0
        val focalLength = halfHeight / tan(fovRad / 2.0)

        val screenX = halfWidth + (xCam / zCam) * focalLength
        val screenY = halfHeight - (yCam / zCam) * focalLength

        return Vec2(screenX.toFloat(), screenY.toFloat())
    }

    override fun init() {
        register<WorldChangeEvent> {
            resetAimState()
        }

        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            if (!enabled || !SimonSays.enabled || !isAtSSDevice()) return@register
            val current = getValidButton() ?: return@register
            if (event.pos == current) {
                markTargetClicked()
            }
        }

        register<MouseClickEvent> {
            if (!enabled || !SimonSays.enabled || !isAtSSDevice()) return@register
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                val current = getValidButton() ?: return@register
                if (isLookingAtButton(current)) {
                    markTargetClicked()
                }
            }
        }

        // Draw the client-side circle overlay (not drawn for Mode 2: Stop Movement)
        register<RenderOverlayEvent> {
            if (!SimonSays.enabled) return@register
            if (mode.value == 2) return@register
            if (!drawCircle.value) return@register

            val inGui = mc.screen != null
            if (!isAtSSDevice() && !inGui) return@register

            Resolution.push(event.context)
            val cx = Resolution.width / 2f
            val cy = Resolution.height / 2f
            val radius = helperRadius.value.toFloat()

            val renderColor = circleColor.value

            event.context.drawAnnularSegment(
                centerX = cx,
                centerY = cy,
                innerRadius = radius - 1.2f,
                outerRadius = radius,
                startAngle = 0.0,
                endAngle = Math.PI * 2.0,
                color = renderColor
            )
            Resolution.pop(event.context)
        }

        // Handle aim assist logic per selected mode
        register<RenderWorldEvent> {
            if (mc.screen != null) return@register
            if (!SimonSays.enabled) return@register
            if (!isAtSSDevice()) return@register

            val targetButton = getValidButton()
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

            // Target changed to a new button in sequence
            if (targetButton != lastTargetPos) {
                lastTargetPos = targetButton
                hasAimed = false
                isThresholdLocking = false
                redirectActive = false
                redirectStartTime = 0L
                hitboxHoverStartTime = 0L
                clickedSSButton = null
                clickedButtonPos = null
                clickedSolutionSize = -1
            }

            val onHitbox = isLookingAtButton(targetButton)

            // Auto-detect click if holding right click or left click on hitbox
            if (onHitbox && (mc.options.keyUse.isDown || mc.options.keyAttack.isDown)) {
                markTargetClicked()
            }

            // If current button was already clicked, do not aim or pull back to it
            if (isCurrentTargetClicked()) {
                isThresholdLocking = false
                redirectActive = false
                return@register
            }

            // ----------------------------------------------------
            // MODE 2: STOP MOVEMENT (No aim assist, only hover tracking)
            // ----------------------------------------------------
            if (mode.value == 2) {
                if (onHitbox) {
                    if (hitboxHoverStartTime == 0L) {
                        hitboxHoverStartTime = now
                    }
                } else {
                    hitboxHoverStartTime = 0L
                }
                return@register
            }

            // If already on hitbox or already aimed at this button, stop aiming
            if (onHitbox) {
                hasAimed = true
                isThresholdLocking = false
                redirectActive = false
                return@register
            }

            if (hasAimed && mode.value != 1) {
                return@register
            }

            val targetVec = getTargetPoint(targetButton)
            val screenPos = worldToScreen(targetVec) ?: run {
                redirectActive = false
                isThresholdLocking = false
                return@register
            }

            val screenDist = hypot(
                screenPos.x.toDouble() - (Resolution.width / 2.0),
                screenPos.y.toDouble() - (Resolution.height / 2.0)
            )

            // Outside circle -> no aim assist
            if (screenDist > helperRadius.value.toDouble()) {
                redirectActive = false
                isThresholdLocking = false
                return@register
            }

            val targetRot = MathUtils.calcYawPitch(targetVec)
            val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
            val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
            val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

            if (angularDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                hasAimed = true
                redirectActive = false
                isThresholdLocking = false
                return@register
            }

            // ----------------------------------------------------
            // MODE 0: CLASSIC HELPER
            // ----------------------------------------------------
            if (mode.value == 0) {
                val speed = classicRotationSpeed.value * 4.5
                val baseStep = (speed * dt).toFloat()
                val easeFactor = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
                val step = (baseStep * easeFactor).coerceIn(0.05f, angularDist)

                val ratio = (step / angularDist).coerceIn(0f, 1f)
                val newYaw = currentYaw + deltaYaw * ratio
                val newPitch = currentPitch + deltaPitch * ratio
                PlayerUtils.rotate(newYaw, newPitch)

                if (isLookingAtButton(targetButton)) {
                    hasAimed = true
                }
                return@register
            }

            // ----------------------------------------------------
            // MODE 1: THRESHOLD LOCK
            // ----------------------------------------------------
            if (mode.value == 1) {
                if (!isThresholdLocking) return@register

                val speed = thresholdRotationSpeed.value * 4.5
                val baseStep = (speed * dt).toFloat()
                val easeFactor = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
                val step = (baseStep * easeFactor).coerceIn(0.05f, angularDist)

                val ratio = (step / angularDist).coerceIn(0f, 1f)
                val newYaw = currentYaw + deltaYaw * ratio
                val newPitch = currentPitch + deltaPitch * ratio
                PlayerUtils.rotate(newYaw, newPitch)

                if (isLookingAtButton(targetButton)) {
                    hasAimed = true
                    isThresholdLocking = false
                }
                return@register
            }

            // ----------------------------------------------------
            // MODE 3: REDIRECT MODE
            // ----------------------------------------------------
            if (mode.value == 3) {
                if (!redirectActive) {
                    redirectActive = true
                    redirectStartTime = now
                    val baseSpeed = max(recentSpeed * speedMultiplier.value.toFloat(), minimumSpeed.value.toFloat())
                    redirectCurrentSpeed = baseSpeed
                }

                // Timeout check
                if (now - redirectStartTime > 600L) {
                    redirectActive = false
                    return@register
                }

                // Smooth finish deceleration when nearing the button
                if (smoothFinish.value) {
                    val decel = (finishDeceleration.value * 10.0).toFloat()
                    val stopDist = (redirectCurrentSpeed * redirectCurrentSpeed) / (2f * decel)

                    if (angularDist <= stopDist) {
                        redirectCurrentSpeed = (redirectCurrentSpeed - decel * dt.toFloat())
                            .coerceAtLeast(minimumSpeed.value.toFloat() * 0.4f)
                    }
                }

                val step = (redirectCurrentSpeed * dt.toFloat()).coerceIn(0.05f, angularDist)
                val ratio = (step / angularDist).coerceIn(0f, 1f)
                val newYaw = currentYaw + deltaYaw * ratio
                val newPitch = currentPitch + deltaPitch * ratio
                PlayerUtils.rotate(newYaw, newPitch)

                if (isLookingAtButton(targetButton)) {
                    redirectActive = false
                    hasAimed = true
                }
                return@register
            }

            // Track recent rotational speed when outside or before redirecting
            if (!redirectActive) {
                recentSpeed = max(recentSpeed * 0.75f, currentRotSpeed)
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAimState()
    }

    private fun resetAimState() {
        lastTargetPos = null
        hasAimed = false
        lastFrameTime = 0L
        isThresholdLocking = false
        hitboxHoverStartTime = 0L
        redirectActive = false
        redirectStartTime = 0L
        redirectCurrentSpeed = 0f
        recentSpeed = 0f
        prevYaw = 0f
        prevPitch = 0f
        clickedSSButton = null
        clickedButtonPos = null
        clickedSolutionSize = -1
    }
}
