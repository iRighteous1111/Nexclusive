package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import com.github.noamm9.nexclusive.utils.render.CircleRenderer.drawAimCircle
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

object LeverHelper: Feature(
    name = "Lever Helper",
    description = "Automatically aims at visible nearby levers within the circle radius."
) {
    private val onlyInBoss by ToggleSetting("Only in Boss", false).withDescription("Only activates lever helper while inside a boss fight.")
    private val ignoreLightsDevice by ToggleSetting("Ignore Lights Device", true).withDescription("Prevents aiming at levers in the F7/M7 Lights device.")
    private val ignoreAdjacentLevers by ToggleSetting("Ignore Adjacent Levers", false).withDescription("Prevents aiming if two levers are placed right next to each other.")

    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px").section("Aim Radius").withDescription("Screen radius of the circle to detect levers.")
    private val drawCircle by ToggleSetting("Draw Circle", true).withDescription("Draws the detection circle when near a visible lever.")
    private val circleColor by ColorSetting("Circle Color", Color(255, 170, 0, 140), true).withDescription("Color and transparency of the detection circle.").showIf { drawCircle.value }

    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1).section("Aim Speed").withDescription("Speed at which your crosshair rotates towards the lever.")

    private val aimDuration by SliderSetting("Aim Duration", 0.5, 0.0, 5.0, 0.1, "s").section("Timing").withDescription("Duration to hold aim on the lever while moving past it before releasing.")
    private val aimCooldown by SliderSetting("Aim Cooldown", 1.0, 0.0, 5.0, 0.1, "s").withDescription("Time before re-aiming at the same lever. Set to 0 to continuously aim as long as it's in the circle.")

    private val cachedLevers = CopyOnWriteArrayList<Pair<BlockPos, Vec3>>()
    private val leverAimedTimes = HashMap<BlockPos, Long>()
    private var currentAimingLever: BlockPos? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L

    private fun isClearRay(eyePos: Vec3, pt: Vec3, pos: BlockPos): Boolean {
        val hit = level.clip(ClipContext(eyePos, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type == HitResult.Type.MISS || hit.blockPos == pos
    }

    private fun getVisibleTargetPoint(pos: BlockPos, eyePos: Vec3): Vec3? {
        val shape = WorldUtils.getStateAt(pos).getShape(level, pos)
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
        if (isClearRay(eyePos, center, pos)) return center

        val inset = 0.05
        val x0 = minX + (maxX - minX) * inset
        val x1 = maxX - (maxX - minX) * inset
        val y0 = minY + (maxY - minY) * inset
        val y1 = maxY - (maxY - minY) * inset
        val z0 = minZ + (maxZ - minZ) * inset
        val z1 = maxZ - (maxZ - minZ) * inset

        val points = arrayOf(
            Vec3(x0, midY, midZ), Vec3(x1, midY, midZ),
            Vec3(midX, y0, midZ), Vec3(midX, y1, midZ),
            Vec3(midX, midY, z0), Vec3(midX, midY, z1),
            Vec3(x0, y0, z0), Vec3(x0, y0, z1),
            Vec3(x0, y1, z0), Vec3(x0, y1, z1),
            Vec3(x1, y0, z0), Vec3(x1, y0, z1),
            Vec3(x1, y1, z0), Vec3(x1, y1, z1)
        )

        var bestPt: Vec3? = null
        var bestDist = Double.MAX_VALUE
        for (pt in points) {
            if (! isClearRay(eyePos, pt, pos)) continue
            val screenPos = ProjectionUtils.worldToScreen(pt) ?: continue
            val dist = CircleRenderer.distanceToCenter(screenPos)
            if (dist < bestDist) {
                bestDist = dist
                bestPt = pt
            }
        }
        return bestPt
    }

    private fun isLightsDevice(pos: BlockPos): Boolean {
        return (LocationUtils.dungeonFloorNumber == 7 || LocationUtils.inBoss) &&
            pos.z == 142 && pos.x in 57 .. 63 && pos.y in 132 .. 137
    }

    private fun hasAdjacentLever(pos: BlockPos): Boolean {
        val mPos = BlockPos.MutableBlockPos()
        for (dx in - 1 .. 1) {
            for (dy in - 1 .. 1) {
                for (dz in - 1 .. 1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    mPos.set(pos.x + dx, pos.y + dy, pos.z + dz)
                    if (WorldUtils.getBlockAt(mPos) == Blocks.LEVER) return true
                }
            }
        }
        return false
    }

    private fun scanNearbyLevers() {
        val p = mc.player ?: return cachedLevers.clear()
        if (mc.level == null || mc.gui.screen() != null || (onlyInBoss.value && ! LocationUtils.inBoss)) {
            cachedLevers.clear()
            return
        }

        val eyePos = p.eyePosition
        val base = p.blockPosition()
        val mPos = BlockPos.MutableBlockPos()
        val found = ArrayList<Pair<BlockPos, Vec3>>(4)

        for (dx in - 5 .. 5) {
            for (dy in - 3 .. 3) {
                for (dz in - 5 .. 5) {
                    mPos.set(base.x + dx, base.y + dy, base.z + dz)
                    if (eyePos.distanceToSqr(mPos.x + 0.5, mPos.y + 0.5, mPos.z + 0.5) > 25.0) continue
                    if (WorldUtils.getBlockAt(mPos) != Blocks.LEVER) continue

                    val immutablePos = mPos.immutable()
                    if (ignoreLightsDevice.value && isLightsDevice(immutablePos)) continue
                    if (ignoreAdjacentLevers.value && hasAdjacentLever(immutablePos)) continue

                    val visiblePt = getVisibleTargetPoint(immutablePos, eyePos) ?: continue
                    found.add(immutablePos to visiblePt)
                }
            }
        }

        cachedLevers.clear()
        cachedLevers.addAll(found)
    }

    override fun init() {
        register<WorldChangeEvent> { resetAll() }
        register<TickEvent.Start> { scanNearbyLevers() }

        register<RenderOverlayEvent> {
            if (! drawCircle.value || mc.gui.screen() != null) return@register
            if (onlyInBoss.value && ! LocationUtils.inBoss) return@register
            if (cachedLevers.isEmpty()) return@register

            event.drawAimCircle(helperRadius.value, circleColor.value)
        }

        register<RenderWorldEvent> {
            if (mc.gui.screen() != null) return@register
            if (onlyInBoss.value && ! LocationUtils.inBoss) return@register
            if (cachedLevers.isEmpty()) return@register resetLockState()

            val maxRadius = helperRadius.value.toDouble()
            val now = System.currentTimeMillis()
            val cooldownMs = (aimCooldown.value * 1000.0).toLong()
            val aimDurationMs = (aimDuration.value * 1000.0).toLong()

            var bestPos: BlockPos? = null
            var bestTarget: Vec3? = null
            var bestDist = Double.MAX_VALUE

            for ((pos, target) in cachedLevers) {
                if (cooldownMs > 0L && now - (leverAimedTimes[pos] ?: 0L) < cooldownMs) continue
                val screenPos = ProjectionUtils.worldToScreen(target) ?: continue
                val dist = CircleRenderer.distanceToCenter(screenPos)
                if (dist <= maxRadius && dist < bestDist) {
                    bestDist = dist
                    bestPos = pos
                    bestTarget = target
                }
            }

            if (bestPos == null || bestTarget == null) return@register resetLockState()

            if (bestPos != currentAimingLever) {
                currentAimingLever = bestPos
                aimLockStartTime = 0L
                lastFrameTime = 0L
            }

            if (aimLockStartTime > 0L) {
                if (now - aimLockStartTime < aimDurationMs) {
                    AimUtils.rotateTo(bestTarget)
                    return@register
                }
                leverAimedTimes[bestPos] = now
                resetLockState()
                return@register
            }

            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            if (AimUtils.rotateTowards(bestTarget, rotationSpeed.value, dt)) {
                aimLockStartTime = now
                if (aimDurationMs <= 0L) {
                    leverAimedTimes[bestPos] = now
                    resetLockState()
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAll()
    }

    private fun resetLockState() {
        currentAimingLever = null
        aimLockStartTime = 0L
        lastFrameTime = 0L
    }

    private fun resetAll() {
        cachedLevers.clear()
        leverAimedTimes.clear()
        resetLockState()
    }
}
