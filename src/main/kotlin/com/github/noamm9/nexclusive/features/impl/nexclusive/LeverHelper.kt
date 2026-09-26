package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.*

object LeverHelper : Feature(
    name = "Lever Helper",
    description = "Automatically aims at visible nearby levers within the circle radius."
) {
    private val onlyInBoss by ToggleSetting("Only in Boss", false)
        .withDescription("Only activates lever helper while inside a boss fight.")

    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px")
        .section("Aim Radius")
        .withDescription("Screen radius of the circle to detect levers.")

    private val drawCircle by ToggleSetting("Draw Circle", true)
        .withDescription("Draws the detection circle when near a visible lever.")

    private val circleColor by ColorSetting("Circle Color", Color(255, 170, 0, 140), true)
        .withDescription("Color and transparency of the detection circle.")
        .showIf { drawCircle.value }

    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1)
        .section("Aim Speed")
        .withDescription("Speed at which your crosshair rotates towards the lever.")

    private val aimDuration by SliderSetting("Aim Duration", 0.5, 0.0, 5.0, 0.1, "s")
        .section("Timing")
        .withDescription("Duration to hold aim on the lever while moving past it before releasing.")

    private val aimCooldown by SliderSetting("Aim Cooldown", 1.0, 0.0, 5.0, 0.1, "s")
        .withDescription("Time before re-aiming at the same lever. Set to 0 to continuously aim as long as it's in the circle.")

    private val leverAimedTimes = HashMap<BlockPos, Long>()
    private var currentAimingLever: BlockPos? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L

    /**
     * Checks multiple points across the lever's actual hitbox (center, face centers, corners/tips).
     * Returns the best visible point to aim at, or null if completely obstructed.
     */
    private fun getVisibleTargetPoint(pos: BlockPos, eyePos: Vec3): Vec3? {
        val state = level.getBlockState(pos)
        val shape = state.getShape(level, pos)
        if (shape.isEmpty) return null

        val minX = pos.x + shape.min(Direction.Axis.X)
        val minY = pos.y + shape.min(Direction.Axis.Y)
        val minZ = pos.z + shape.min(Direction.Axis.Z)
        val maxX = pos.x + shape.max(Direction.Axis.X)
        val maxY = pos.y + shape.max(Direction.Axis.Y)
        val maxZ = pos.z + shape.max(Direction.Axis.Z)

        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0
        val midZ = (minZ + maxZ) / 2.0
        val center = Vec3(midX, midY, midZ)

        // Sample points slightly inset (5%) from the bounding box to avoid boundary clipping artifacts
        val inset = 0.05
        val x0 = minX + (maxX - minX) * inset
        val x1 = maxX - (maxX - minX) * inset
        val y0 = minY + (maxY - minY) * inset
        val y1 = maxY - (maxY - minY) * inset
        val z0 = minZ + (maxZ - minZ) * inset
        val z1 = maxZ - (maxZ - minZ) * inset

        val points = listOf(
            center,
            // Face centers
            Vec3(x0, midY, midZ), Vec3(x1, midY, midZ),
            Vec3(midX, y0, midZ), Vec3(midX, y1, midZ),
            Vec3(midX, midY, z0), Vec3(midX, midY, z1),
            // Corners and tips
            Vec3(x0, y0, z0), Vec3(x0, y0, z1),
            Vec3(x0, y1, z0), Vec3(x0, y1, z1),
            Vec3(x1, y0, z0), Vec3(x1, y0, z1),
            Vec3(x1, y1, z0), Vec3(x1, y1, z1)
        )

        // Find all points that have an unobstructed line of sight
        val visiblePoints = points.filter { pt ->
            val clip = ClipContext(eyePos, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            val hit = level.clip(clip)
            hit.type == HitResult.Type.MISS || hit.blockPos == pos
        }

        if (visiblePoints.isEmpty()) return null

        // If the center is visible, aim directly at the center
        if (visiblePoints.contains(center)) return center

        // Otherwise pick the visible point (e.g. visible tip/corner) closest to the screen crosshair
        val halfW = Resolution.width / 2.0
        val halfH = Resolution.height / 2.0

        return visiblePoints.minByOrNull { pt ->
            val screenPos = worldToScreen(pt) ?: return@minByOrNull Double.MAX_VALUE
            hypot(screenPos.x.toDouble() - halfW, screenPos.y.toDouble() - halfH)
        }
    }

    /**
     * Finds nearby levers within reach distance that have at least one visible point.
     */
    private fun getNearbyLevers(eyePos: Vec3): List<Pair<BlockPos, Vec3>> {
        val base = player.blockPosition()
        val levers = mutableListOf<Pair<BlockPos, Vec3>>()

        for (dx in -5 .. 5) {
            for (dy in -3 .. 3) {
                for (dz in -5 .. 5) {
                    val pos = base.offset(dx, dy, dz)
                    val state = level.getBlockState(pos)
                    if (state.block != Blocks.LEVER) continue

                    val approxCenter = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    if (eyePos.distanceToSqr(approxCenter) > 25.0) continue // max 5 blocks reach

                    val visiblePt = getVisibleTargetPoint(pos, eyePos) ?: continue
                    levers.add(pos to visiblePt)
                }
            }
        }
        return levers
    }

    /**
     * Projects a 3D world position into 2D screen coordinates based on current camera angles and FOV.
     * Returns null if the target is behind the player's view.
     */
    private fun worldToScreen(target: Vec3): Vec2? {
        val p = mc.player ?: return null
        val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)

        val dx = target.x - eyePos.x
        val dy = target.y - eyePos.y
        val dz = target.z - eyePos.z

        val yawRad = Math.toRadians(p.yRot.toDouble())
        val pitchRad = Math.toRadians(p.xRot.toDouble())

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

        // Draw the client-side circle overlay when near a visible lever (or previewing in GUI)
        register<RenderOverlayEvent> {
            if (! drawCircle.value) return@register
            if (onlyInBoss.value && ! LocationUtils.inBoss) return@register

            val p = mc.player ?: return@register
            val inGui = mc.screen != null

            if (! inGui) {
                val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)
                val hasNearbyLevers = getNearbyLevers(eyePos).isNotEmpty()
                if (! hasNearbyLevers) return@register
            }

            Resolution.push(event.context)
            val cx = Resolution.width / 2f
            val cy = Resolution.height / 2f
            val radius = helperRadius.value.toFloat()

            event.context.drawAnnularSegment(
                centerX = cx,
                centerY = cy,
                innerRadius = radius - 1.2f,
                outerRadius = radius,
                startAngle = 0.0,
                endAngle = Math.PI * 2.0,
                color = circleColor.value
            )
            Resolution.pop(event.context)
        }

        // Handle smooth mouse aim assist towards visible levers
        register<RenderWorldEvent> {
            if (mc.screen != null) return@register
            if (onlyInBoss.value && ! LocationUtils.inBoss) return@register

            val p = mc.player ?: return@register
            val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)
            val levers = getNearbyLevers(eyePos)

            if (levers.isEmpty()) {
                currentAimingLever = null
                aimLockStartTime = 0L
                lastFrameTime = 0L
                return@register
            }

            val halfW = Resolution.width / 2.0
            val halfH = Resolution.height / 2.0
            val maxRadius = helperRadius.value.toDouble()
            val now = System.currentTimeMillis()
            val cooldownMs = (aimCooldown.value * 1000.0).toLong()
            val aimDurationMs = (aimDuration.value * 1000.0).toLong()

            data class LeverCandidate(val pos: BlockPos, val targetVec: Vec3, val screenDist: Double)

            val candidates = levers.mapNotNull { (pos, targetVec) ->
                // Check cooldown: if cooldown > 0, ignore levers aimed at within cooldown window
                if (cooldownMs > 0L) {
                    val lastAimed = leverAimedTimes[pos] ?: 0L
                    if (now - lastAimed < cooldownMs) return@mapNotNull null
                }
                val screenPos = worldToScreen(targetVec) ?: return@mapNotNull null
                val dist = hypot(screenPos.x.toDouble() - halfW, screenPos.y.toDouble() - halfH)
                if (dist <= maxRadius) LeverCandidate(pos, targetVec, dist) else null
            }

            val bestCandidate = candidates.minByOrNull { it.screenDist }
            if (bestCandidate == null) {
                currentAimingLever = null
                aimLockStartTime = 0L
                lastFrameTime = 0L
                return@register
            }

            if (bestCandidate.pos != currentAimingLever) {
                currentAimingLever = bestCandidate.pos
                aimLockStartTime = 0L
                lastFrameTime = 0L
            }

            val targetRot = MathUtils.calcYawPitch(bestCandidate.targetVec)

            // If already locked on this lever during its aim duration window
            if (aimLockStartTime > 0L) {
                if (now - aimLockStartTime < aimDurationMs) {
                    // Actively keep crosshair locked to the lever as the player moves/runs
                    PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                    return@register
                } else {
                    // Aim duration finished: record cooldown timestamp and release control
                    leverAimedTimes[bestCandidate.pos] = now
                    currentAimingLever = null
                    aimLockStartTime = 0L
                    return@register
                }
            }

            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            val currentYaw = player.yRot
            val currentPitch = player.xRot

            val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
            val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
            val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

            // When arriving at the center, start the aim duration timer
            if (angularDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                aimLockStartTime = now
                if (aimDurationMs <= 0L) {
                    leverAimedTimes[bestCandidate.pos] = now
                    currentAimingLever = null
                    aimLockStartTime = 0L
                }
                return@register
            }

            val speed = rotationSpeed.value
            val degPerSec = speed * 4.5
            val baseStep = (degPerSec * dt).toFloat()
            val easeFactor = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
            val step = (baseStep * easeFactor).coerceIn(0.05f, angularDist)

            val ratio = (step / angularDist).coerceIn(0f, 1f)
            val newYaw = currentYaw + deltaYaw * ratio
            val newPitch = currentPitch + deltaPitch * ratio

            PlayerUtils.rotate(newYaw, newPitch)

            val remainingDist = hypot(
                MathUtils.normalizeYaw(targetRot.yaw - newYaw).toDouble(),
                MathUtils.normalizePitch(targetRot.pitch - newPitch).toDouble()
            ).toFloat()

            if (remainingDist <= 0.25f) {
                PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                aimLockStartTime = now
                if (aimDurationMs <= 0L) {
                    leverAimedTimes[bestCandidate.pos] = now
                    currentAimingLever = null
                    aimLockStartTime = 0L
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAimState()
    }

    private fun resetAimState() {
        leverAimedTimes.clear()
        currentAimingLever = null
        aimLockStartTime = 0L
        lastFrameTime = 0L
    }
}
