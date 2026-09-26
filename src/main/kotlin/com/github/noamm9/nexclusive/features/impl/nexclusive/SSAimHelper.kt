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
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.lang.reflect.Field
import kotlin.math.*

object SSAimHelper : Feature(
    name = "SS Aim Helper",
    description = "Automatically aims at the valid Simon Says button within the circle radius."
) {
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
        if (! SimonSays.enabled) return null
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
            if (! SimonSays.enabled) return@register
            if (! drawCircle.value) return@register

            val inGui = mc.screen != null
            if (! isAtSSDevice() && ! inGui) return@register

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
            if (! SimonSays.enabled) return@register
            if (! isAtSSDevice()) return@register

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

            // If already aimed at this button once, do not pull again to prevent getting stuck
            if (hasAimed) return@register

            // Center of the stone button surface on the Simon Says board (facing west at x = 110)
            val targetVec = Vec3(targetButton.x + 0.9, targetButton.y + 0.5, targetButton.z + 0.5)

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

            // Once crosshair is at the exact center, finish aiming so user can freely move mouse
            if (angularDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                hasAimed = true
                return@register
            }

            val speed = rotationSpeed.value

            // Smooth frame-rate independent glide towards the button
            val degPerSec = speed * 4.5
            val baseStep = (degPerSec * dt).toFloat()
            // Gentle ease-out deceleration when very close to the center (< 1.5 degrees)
            val easeFactor = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
            val step = (baseStep * easeFactor).coerceIn(0.05f, angularDist)

            val ratio = (step / angularDist).coerceIn(0f, 1f)
            val newYaw = currentYaw + deltaYaw * ratio
            val newPitch = currentPitch + deltaPitch * ratio

            PlayerUtils.rotate(newYaw, newPitch)

            // Check if this rotation reached the center
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
    }
}
