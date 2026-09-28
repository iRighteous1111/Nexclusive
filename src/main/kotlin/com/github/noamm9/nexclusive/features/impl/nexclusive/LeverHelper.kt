package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
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
import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.hypot

object LeverHelper: Feature(
    name = "Lever Helper",
    description = "Automatically aims at visible nearby levers within the circle radius."
) {
    private val onlyInBoss by ToggleSetting("Only in Boss", false)
        .withDescription("Only activates lever helper while inside a boss fight.")

    private val ignoreLightsDevice by ToggleSetting("Ignore Lights Device", true)
        .withDescription("Prevents aiming at levers in the F7/M7 Lights device.")

    private val ignoreAdjacentLevers by ToggleSetting("Ignore Adjacent Levers", false)
        .withDescription("Prevents aiming if two levers are placed right next to each other.")

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

        val inset = 0.05
        val x0 = minX + (maxX - minX) * inset
        val x1 = maxX - (maxX - minX) * inset
        val y0 = minY + (maxY - minY) * inset
        val y1 = maxY - (maxY - minY) * inset
        val z0 = minZ + (maxZ - minZ) * inset
        val z1 = maxZ - (maxZ - minZ) * inset

        val points = listOf(
            center,
            Vec3(x0, midY, midZ), Vec3(x1, midY, midZ),
            Vec3(midX, y0, midZ), Vec3(midX, y1, midZ),
            Vec3(midX, midY, z0), Vec3(midX, midY, z1),
            Vec3(x0, y0, z0), Vec3(x0, y0, z1),
            Vec3(x0, y1, z0), Vec3(x0, y1, z1),
            Vec3(x1, y0, z0), Vec3(x1, y0, z1),
            Vec3(x1, y1, z0), Vec3(x1, y1, z1)
        )

        val visible = points.filter { pt ->
            val clip = ClipContext(eyePos, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            val hit = level.clip(clip)
            hit.type == HitResult.Type.MISS || hit.blockPos == pos
        }

        if (visible.isEmpty()) return null
        if (visible.contains(center)) return center

        val halfW = Resolution.width / 2.0
        val halfH = Resolution.height / 2.0

        return visible.minByOrNull { pt ->
            val screenPos = ProjectionUtils.worldToScreen(pt) ?: return@minByOrNull Double.MAX_VALUE
            hypot(screenPos.x.toDouble() - halfW, screenPos.y.toDouble() - halfH)
        }
    }

    private val lightsDeviceLevers = hashSetOf(
        BlockPos(61, 136, 142), BlockPos(60, 136, 142),
        BlockPos(59, 136, 142), BlockPos(62, 135, 142),
        BlockPos(61, 135, 142), BlockPos(59, 135, 142),
        BlockPos(58, 135, 142), BlockPos(62, 134, 142),
        BlockPos(61, 134, 142), BlockPos(59, 134, 142),
        BlockPos(58, 134, 142), BlockPos(61, 133, 142),
        BlockPos(60, 133, 142), BlockPos(59, 133, 142)
    )

    private fun isLightsDevice(pos: BlockPos): Boolean {
        if (LocationUtils.dungeonFloorNumber == 7 || LocationUtils.inBoss) {
            if (pos in lightsDeviceLevers) return true
            if (pos.z == 142 && pos.x in 57 .. 63 && pos.y in 132 .. 137) return true
        }
        return false
    }

    private fun hasAdjacentLever(pos: BlockPos): Boolean {
        for (dx in - 1 .. 1) {
            for (dy in - 1 .. 1) {
                for (dz in - 1 .. 1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    if (level.getBlockState(pos.offset(dx, dy, dz)).block == Blocks.LEVER) return true
                }
            }
        }
        return false
    }

    private fun getNearbyLevers(eyePos: Vec3): List<Pair<BlockPos, Vec3>> {
        val base = player.blockPosition()
        val levers = mutableListOf<Pair<BlockPos, Vec3>>()

        for (dx in - 5 .. 5) {
            for (dy in - 3 .. 3) {
                for (dz in - 5 .. 5) {
                    val pos = base.offset(dx, dy, dz)
                    if (level.getBlockState(pos).block != Blocks.LEVER) continue
                    if (ignoreLightsDevice.value && isLightsDevice(pos)) continue
                    if (ignoreAdjacentLevers.value && hasAdjacentLever(pos)) continue
                    if (eyePos.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > 25.0) continue

                    val visiblePt = getVisibleTargetPoint(pos, eyePos) ?: continue
                    levers.add(pos to visiblePt)
                }
            }
        }
        return levers
    }

    override fun init() {
        register<WorldChangeEvent> { resetAimState() }

        register<RenderOverlayEvent> {
            if (! drawCircle.value) return@register
            if (onlyInBoss.value && ! LocationUtils.inBoss) return@register

            val p = mc.player ?: return@register
            if (mc.screen == null) {
                val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)
                if (getNearbyLevers(eyePos).isEmpty()) return@register
            }

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

            data class Candidate(val pos: BlockPos, val target: Vec3, val dist: Double)

            val bestCandidate = levers.mapNotNull { (pos, target) ->
                if (cooldownMs > 0L && now - (leverAimedTimes[pos] ?: 0L) < cooldownMs) return@mapNotNull null
                val screenPos = ProjectionUtils.worldToScreen(target) ?: return@mapNotNull null
                val dist = hypot(screenPos.x.toDouble() - halfW, screenPos.y.toDouble() - halfH)
                if (dist <= maxRadius) Candidate(pos, target, dist) else null
            }.minByOrNull { it.dist }

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

            val targetRot = MathUtils.calcYawPitch(bestCandidate.target)

            if (aimLockStartTime > 0L) {
                if (now - aimLockStartTime < aimDurationMs) {
                    PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                    return@register
                }
                leverAimedTimes[bestCandidate.pos] = now
                currentAimingLever = null
                aimLockStartTime = 0L
                return@register
            }

            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            val currentYaw = player.yRot
            val currentPitch = player.xRot
            val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
            val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
            val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

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

            val ratio = AimUtils.easedRatio(angularDist, rotationSpeed.value * 4.5, dt)

            val newYaw = currentYaw + deltaYaw * ratio
            val newPitch = currentPitch + deltaPitch * ratio
            PlayerUtils.rotate(newYaw, newPitch)

            val remaining = hypot(
                MathUtils.normalizeYaw(targetRot.yaw - newYaw).toDouble(),
                MathUtils.normalizePitch(targetRot.pitch - newPitch).toDouble()
            ).toFloat()

            if (remaining <= 0.25f) {
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
