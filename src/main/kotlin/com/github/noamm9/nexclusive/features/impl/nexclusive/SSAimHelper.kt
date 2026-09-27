package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
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
import java.awt.Color
import java.lang.reflect.Field
import kotlin.math.*

object SSAimHelper : Feature(
    name = "SS Aim Helper",
    description = "Automatically aims at the valid Simon Says button within the circle radius."
) {
    val INSTANCE = this

    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px")
        .section("Aim Radius")
        .withDescription("Radius of the circle on screen to detect the valid Simon Says button.")

    private val drawCircle by ToggleSetting("Draw Circle", true)
        .withDescription("Draws the detection circle centered on your crosshair.")

    private val circleColor by ColorSetting("Circle Color", Color(0, 255, 255), true)
        .withDescription("Color of the on-screen circle.")
        .showIf { drawCircle.value }

    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1)
        .section("Aim Speed")
        .withDescription("Speed at which your crosshair rotates towards the button.")

    private val thresholdLockMode by ToggleSetting("Threshold Lock Mode", false)
        .section("Threshold Lock")
        .withDescription("Cuts mouse input and locks aim onto the button when crosshair is off the hitbox, within the circle, and mouse is idle or moving slowly.")

    private val lockThreshold by SliderSetting("Lock Threshold", 3.5, 0.5, 10.0, 0.5, "px")
        .showIf { thresholdLockMode.value }
        .withDescription("Maximum mouse movement speed to trigger threshold lock.")

    private val aimCenter by ToggleSetting("Aim Center", false)
        .section("Aim Point")
        .withDescription("Aims at the closest point of the button hitbox rather than forcing center aim, stopping immediately upon entering the hitbox.")

    private val offCenter by SliderSetting("OFF Center", 50, 0, 100, 5, "%")
        .showIf { aimCenter.value }
        .withDescription("Distance from center towards the edge. 0 = exact center, 100 = closest outer edge of hitbox.")

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

    /**
     * Checks if the player is currently on the Simon Says device platform in F7 Phase 3.
     */
    fun isAtSSDevice(): Boolean {
        val p = mc.player ?: return false
        return LocationUtils.F7Phase == 3 && p.position().distanceToSqr(deviceCenter) <= 49.0
    }

    /**
     * Retrieves the current valid button to press from Noamm's SimonSays solver.
     * Returns null if SimonSays is disabled, empty, or inaccessible.
     */
    fun getValidButton(): BlockPos? {
        if (!SimonSays.enabled) return null
        return runCatching {
            val list = solutionField?.get(SimonSays) as? List<*> ?: return null
            val first = list.firstOrNull() ?: return null
            if (buttonFieldCache == null) {
                buttonFieldCache = first.javaClass.getDeclaredField("button").apply {
                    isAccessible = true
                }
            }
            buttonFieldCache?.get(first) as? BlockPos
        }.getOrNull()
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
     * Calculates the target 3D world point on the button.
     * When [aimCenter] is enabled, dynamically shifts the target towards the point on the hitbox
     * closest to the player's crosshair, scaled by [offCenter].
     */
    fun getTargetPoint(targetButton: BlockPos): Vec3 {
        val centerX = targetButton.x + 0.9
        val centerY = targetButton.y + 0.5
        val centerZ = targetButton.z + 0.5

        if (!aimCenter.value) {
            return Vec3(centerX, centerY, centerZ)
        }

        val player = mc.player ?: return Vec3(centerX, centerY, centerZ)
        val eyePos = player.eyePosition
        val lookVec = player.lookAngle

        if (abs(lookVec.x) < 1e-5) {
            return Vec3(centerX, centerY, centerZ)
        }

        val t = (centerX - eyePos.x) / lookVec.x
        if (t <= 0.0) {
            return Vec3(centerX, centerY, centerZ)
        }

        val hitY = eyePos.y + t * lookVec.y
        val hitZ = eyePos.z + t * lookVec.z

        val diffY = hitY - centerY
        val diffZ = hitZ - centerZ

        val ratio = (offCenter.value / 100.0).coerceIn(0.0, 1.0)
        val maxOffsetY = 0.125 * 0.80 * ratio
        val maxOffsetZ = 0.1875 * 0.80 * ratio

        val targetY = centerY + diffY.coerceIn(-maxOffsetY, maxOffsetY)
        val targetZ = centerZ + diffZ.coerceIn(-maxOffsetZ, maxOffsetZ)

        return Vec3(centerX, targetY, targetZ)
    }

    /**
     * Determines whether user mouse movement should be cut off in [MouseHandler.turnPlayer].
     * Suppresses mouse input when threshold lock mode is active, crosshair is off the hitbox,
     * within the circle, and mouse is idle or moving slowly.
     */
    fun shouldSuppressMouseInput(dx: Double, dy: Double): Boolean {
        if (!enabled || !thresholdLockMode.value) {
            isThresholdLocking = false
            return false
        }
        if (mc.screen != null || !SimonSays.enabled || !isAtSSDevice()) {
            isThresholdLocking = false
            return false
        }

        val targetButton = getValidButton()
        if (targetButton == null || isLookingAtButton(targetButton)) {
            isThresholdLocking = false
            return false
        }

        val targetVec = getTargetPoint(targetButton)
        val screenPos = worldToScreen(targetVec)
        if (screenPos == null) {
            isThresholdLocking = false
            return false
        }

        val screenDist = hypot(
            screenPos.x.toDouble() - (Resolution.width / 2.0),
            screenPos.y.toDouble() - (Resolution.height / 2.0)
        )
        if (screenDist > helperRadius.value.toDouble()) {
            isThresholdLocking = false
            return false
        }

        val mouseDelta = hypot(dx, dy)
        val isSlowOrStill = mouseDelta <= lockThreshold.value

        isThresholdLocking = isSlowOrStill
        return isSlowOrStill
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

        // Draw the client-side circle overlay on screen only when at the SS device (or in ClickGUI for preview)
        register<RenderOverlayEvent> {
            if (!SimonSays.enabled) return@register
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

        // Handle smooth mouse aim assist towards the valid button
        register<RenderWorldEvent> {
            if (mc.screen != null) return@register
            if (!SimonSays.enabled) return@register
            if (!isAtSSDevice()) return@register

            val targetButton = getValidButton()
            if (targetButton == null) {
                resetAimState()
                return@register
            }

            // Target changed to a new button in sequence
            if (targetButton != lastTargetPos) {
                lastTargetPos = targetButton
                hasAimed = false
                lastFrameTime = 0L
            }

            // If aimCenter is enabled and crosshair is already looking at the button hitbox, stop aiming
            if (aimCenter.value && isLookingAtButton(targetButton)) {
                hasAimed = true
                return@register
            }

            // If already aimed at this button once, do not pull again unless Threshold Lock Mode is active and off target
            if (hasAimed) {
                if (!thresholdLockMode.value || !isThresholdLocking) {
                    return@register
                }
            }

            // In threshold lock mode, only aim when threshold condition is active (idle/slow mouse)
            if (thresholdLockMode.value && !isThresholdLocking) {
                lastFrameTime = 0L
                return@register
            }

            val targetVec = getTargetPoint(targetButton)

            // Check if the button is within the client-side circle
            val screenPos = worldToScreen(targetVec) ?: run {
                lastFrameTime = 0L
                return@register
            }
            val screenDist = hypot(
                screenPos.x.toDouble() - (Resolution.width / 2.0),
                screenPos.y.toDouble() - (Resolution.height / 2.0)
            )
            if (screenDist > helperRadius.value.toDouble()) {
                lastFrameTime = 0L
                return@register
            }

            val now = System.currentTimeMillis()
            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            // Calculate target yaw and pitch
            val targetRot = MathUtils.calcYawPitch(targetVec)
            val currentYaw = player.yRot
            val currentPitch = player.xRot

            val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
            val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
            val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

            // Stop condition:
            if ((aimCenter.value && isLookingAtButton(targetButton)) || angularDist <= 0.25f) {
                if (angularDist <= 0.25f) {
                    PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                }
                hasAimed = true
                return@register
            }

            val speed = rotationSpeed.value

            // Smooth frame-rate independent glide towards the button
            val degPerSec = speed * 4.5
            val baseStep = (degPerSec * dt).toFloat()
            // Gentle ease-out deceleration when very close to target (< 1.5 degrees)
            val easeFactor = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
            val step = (baseStep * easeFactor).coerceIn(0.05f, angularDist)

            val ratio = (step / angularDist).coerceIn(0f, 1f)
            val newYaw = currentYaw + deltaYaw * ratio
            val newPitch = currentPitch + deltaPitch * ratio

            PlayerUtils.rotate(newYaw, newPitch)

            // Check if this rotation reached the button hitbox
            if (aimCenter.value && isLookingAtButton(targetButton)) {
                hasAimed = true
                return@register
            }

            val remainingDist = hypot(
                MathUtils.normalizeYaw(targetRot.yaw - newYaw).toDouble(),
                MathUtils.normalizePitch(targetRot.pitch - newPitch).toDouble()
            ).toFloat()

            if (remainingDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                hasAimed = true
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
    }
}
