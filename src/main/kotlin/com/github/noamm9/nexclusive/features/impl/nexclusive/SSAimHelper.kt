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

    private val stopMouseMovement by ToggleSetting("Stop Mouse Movement", false)
        .section("Mouse Movement Control")
        .withDescription("Stops mouse movement when hovering on the button hitbox to prevent sliding off.")

    private val stopDuration by SliderSetting("Stop Duration", 200, 50, 1000, 10, "ms")
        .showIf { stopMouseMovement.value }
        .withDescription("Duration to cut mouse input while hovering over the button hitbox.")

    private val redirectFixMode by ToggleSetting("Redirect Fix Mode", false)
        .showIf { stopMouseMovement.value }
        .withDescription("Catches and corrects overshoot if the crosshair quickly passes the button.")

    private val overshootDistance by SliderSetting("Overshoot Distance", 120, 20, 300, 5, "px")
        .showIf { stopMouseMovement.value && redirectFixMode.value }
        .withDescription("Maximum screen pixel distance from the button where overshoot correction triggers.")

    private val smoothMovement by ToggleSetting("Smooth Movement", true)
        .showIf { stopMouseMovement.value && redirectFixMode.value }
        .withDescription("Smoothly decelerates to a stop, then accelerates and decelerates back onto the button.")

    private val stopDeceleration by SliderSetting("Deceleration (Braking)", 20.0, 5.0, 60.0, 1.0)
        .showIf { stopMouseMovement.value && redirectFixMode.value && smoothMovement.value }
        .withDescription("Braking deceleration rate before reversing towards the button.")

    private val returnAcceleration by SliderSetting("Return Acceleration", 25.0, 5.0, 80.0, 1.0)
        .showIf { stopMouseMovement.value && redirectFixMode.value && smoothMovement.value }
        .withDescription("Acceleration rate when starting to pull back towards the button.")

    private val returnMaxSpeed by SliderSetting("Return Max Speed", 35.0, 10.0, 100.0, 1.0)
        .showIf { stopMouseMovement.value && redirectFixMode.value && smoothMovement.value }
        .withDescription("Maximum speed during the return correction glide.")

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

    enum class RedirectState {
        IDLE,
        DECELERATING,
        RETURNING
    }

    private var redirectState = RedirectState.IDLE
    private var hitboxHoverStartTime = 0L
    private var lastOnHitboxTime = 0L
    private var minScreenDistRecent = 9999.0
    private var lastDistTime = 0L

    private var prevYaw = 0f
    private var prevPitch = 0f
    private var currentDecelVelYaw = 0f
    private var currentDecelVelPitch = 0f
    private var currentReturnSpeed = 0f

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
     * Suppresses mouse input when:
     * 1. Redirect Fix is actively decelerating or returning to the button.
     * 2. Stop Mouse Movement is active and crosshair is hovering over the button hitbox.
     * 3. Threshold Lock Mode is active, crosshair is off hitbox, within circle, and mouse is slow/still.
     */
    fun shouldSuppressMouseInput(dx: Double, dy: Double): Boolean {
        if (!enabled) return false
        if (mc.screen != null || !SimonSays.enabled || !isAtSSDevice()) {
            isThresholdLocking = false
            return false
        }

        val targetButton = getValidButton() ?: return false
        val now = System.currentTimeMillis()

        // 1. If currently in Redirect Fix (decelerating or returning), suppress mouse movement
        if (stopMouseMovement.value && redirectFixMode.value && redirectState != RedirectState.IDLE) {
            return true
        }

        // 2. Stop Mouse Movement when hovering on the button hitbox
        val onHitbox = isLookingAtButton(targetButton)
        if (onHitbox) {
            lastOnHitboxTime = now
            if (stopMouseMovement.value) {
                if (hitboxHoverStartTime == 0L) {
                    hitboxHoverStartTime = now
                }
                if (now - hitboxHoverStartTime <= stopDuration.value.toLong()) {
                    return true
                }
            }
        } else {
            hitboxHoverStartTime = 0L
        }

        // 3. Threshold Lock Mode
        if (thresholdLockMode.value) {
            if (!onHitbox) {
                val targetVec = getTargetPoint(targetButton)
                val screenPos = worldToScreen(targetVec)
                if (screenPos != null) {
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
                } else {
                    isThresholdLocking = false
                }
            } else {
                isThresholdLocking = false
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

        // Handle smooth mouse aim assist, stop movement, and redirect fix towards the valid button
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

            // Calculate rotational velocity (degrees per second)
            val velYaw = if (dt > 0.0) MathUtils.normalizeYaw(currentYaw - prevYaw) / dt.toFloat() else 0f
            val velPitch = if (dt > 0.0) MathUtils.normalizePitch(currentPitch - prevPitch) / dt.toFloat() else 0f
            prevYaw = currentYaw
            prevPitch = currentPitch

            // Target changed to a new button in sequence
            if (targetButton != lastTargetPos) {
                lastTargetPos = targetButton
                hasAimed = false
                redirectState = RedirectState.IDLE
                hitboxHoverStartTime = 0L
                lastOnHitboxTime = 0L
                minScreenDistRecent = 9999.0
            }

            val onHitbox = isLookingAtButton(targetButton)
            if (onHitbox) {
                lastOnHitboxTime = now
                redirectState = RedirectState.IDLE
                if (stopMouseMovement.value && hitboxHoverStartTime == 0L) {
                    hitboxHoverStartTime = now
                }
            }

            // Track recent minimum screen distance to the button for overshoot detection
            val currentTargetVec = getTargetPoint(targetButton)
            val currentScreenPos = worldToScreen(currentTargetVec)
            val currentScreenDist = if (currentScreenPos != null) {
                hypot(
                    currentScreenPos.x.toDouble() - (Resolution.width / 2.0),
                    currentScreenPos.y.toDouble() - (Resolution.height / 2.0)
                )
            } else 9999.0

            if (now - lastDistTime > 400L) {
                minScreenDistRecent = currentScreenDist
                lastDistTime = now
            } else {
                minScreenDistRecent = min(minScreenDistRecent, currentScreenDist)
            }

            // Check if Redirect Fix should trigger (overshot past the button)
            if (!onHitbox && stopMouseMovement.value && redirectFixMode.value && redirectState == RedirectState.IDLE) {
                val wasRecentlyNear = (now - lastOnHitboxTime) <= 400L || minScreenDistRecent <= 40.0
                if (wasRecentlyNear && currentScreenDist <= overshootDistance.value.toDouble()) {
                    if (smoothMovement.value) {
                        redirectState = RedirectState.DECELERATING
                        currentDecelVelYaw = velYaw
                        currentDecelVelPitch = velPitch
                        currentReturnSpeed = 0f
                    } else {
                        redirectState = RedirectState.RETURNING
                        currentReturnSpeed = 0f
                    }
                }
            }

            // ----------------------------------------------------
            // REDIRECT FIX: Smooth Deceleration Phase
            // ----------------------------------------------------
            if (redirectState == RedirectState.DECELERATING) {
                val brakeRate = (stopDeceleration.value * 30.0 * dt).toFloat()
                val speed = hypot(currentDecelVelYaw.toDouble(), currentDecelVelPitch.toDouble()).toFloat()

                if (speed <= brakeRate || speed < 1.0f) {
                    // Fully stopped! Transition to returning phase
                    redirectState = RedirectState.RETURNING
                    currentReturnSpeed = 0f
                } else {
                    val newSpeed = speed - brakeRate
                    val scale = newSpeed / speed
                    currentDecelVelYaw *= scale
                    currentDecelVelPitch *= scale

                    val nextYaw = currentYaw + currentDecelVelYaw * dt.toFloat()
                    val nextPitch = currentPitch + currentDecelVelPitch * dt.toFloat()
                    PlayerUtils.rotate(nextYaw, nextPitch)
                    return@register
                }
            }

            // ----------------------------------------------------
            // REDIRECT FIX: Return Glide Phase
            // ----------------------------------------------------
            if (redirectState == RedirectState.RETURNING) {
                val targetRot = MathUtils.calcYawPitch(currentTargetVec)
                val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
                val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
                val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

                if (isLookingAtButton(targetButton) || angularDist <= 0.25f) {
                    if (angularDist <= 0.25f) {
                        PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                    }
                    redirectState = RedirectState.IDLE
                    hitboxHoverStartTime = now
                    hasAimed = true
                    return@register
                }

                if (smoothMovement.value) {
                    // Kinematic acceleration & deceleration profile
                    val accel = (returnAcceleration.value * 20.0).toFloat()
                    val decel = (stopDeceleration.value * 20.0).toFloat()
                    val maxSpd = (returnMaxSpeed.value * 4.0).toFloat()

                    val stopDist = (currentReturnSpeed * currentReturnSpeed) / (2f * decel)

                    if (angularDist <= stopDist) {
                        currentReturnSpeed = (currentReturnSpeed - decel * dt.toFloat()).coerceAtLeast(4.0f)
                    } else {
                        currentReturnSpeed = (currentReturnSpeed + accel * dt.toFloat()).coerceAtMost(maxSpd)
                    }

                    val step = (currentReturnSpeed * dt.toFloat()).coerceIn(0.05f, angularDist)
                    val ratio = (step / angularDist).coerceIn(0f, 1f)
                    val newYaw = currentYaw + deltaYaw * ratio
                    val newPitch = currentPitch + deltaPitch * ratio

                    PlayerUtils.rotate(newYaw, newPitch)

                    if (isLookingAtButton(targetButton)) {
                        redirectState = RedirectState.IDLE
                        hitboxHoverStartTime = now
                        hasAimed = true
                    }
                    return@register
                } else {
                    val step = (rotationSpeed.value * 6.0 * dt).toFloat().coerceIn(0.1f, angularDist)
                    val ratio = (step / angularDist).coerceIn(0f, 1f)
                    val newYaw = currentYaw + deltaYaw * ratio
                    val newPitch = currentPitch + deltaPitch * ratio
                    PlayerUtils.rotate(newYaw, newPitch)

                    if (isLookingAtButton(targetButton)) {
                        redirectState = RedirectState.IDLE
                        hitboxHoverStartTime = now
                        hasAimed = true
                    }
                    return@register
                }
            }

            // ----------------------------------------------------
            // NORMAL & THRESHOLD LOCK AIM ASSIST
            // ----------------------------------------------------
            // If aimCenter is enabled and crosshair is already looking at the button hitbox, stop aiming
            if (aimCenter.value && onHitbox) {
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
                return@register
            }

            val targetVec = getTargetPoint(targetButton)

            // Check if the button is within the client-side circle
            val screenPos = worldToScreen(targetVec) ?: return@register
            val screenDist = hypot(
                screenPos.x.toDouble() - (Resolution.width / 2.0),
                screenPos.y.toDouble() - (Resolution.height / 2.0)
            )
            if (screenDist > helperRadius.value.toDouble()) {
                return@register
            }

            // Calculate target yaw and pitch
            val targetRot = MathUtils.calcYawPitch(targetVec)
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
        redirectState = RedirectState.IDLE
        hitboxHoverStartTime = 0L
        lastOnHitboxTime = 0L
        minScreenDistRecent = 9999.0
        lastDistTime = 0L
        currentDecelVelYaw = 0f
        currentDecelVelPitch = 0f
        currentReturnSpeed = 0f
        prevYaw = 0f
        prevPitch = 0f
    }
}
