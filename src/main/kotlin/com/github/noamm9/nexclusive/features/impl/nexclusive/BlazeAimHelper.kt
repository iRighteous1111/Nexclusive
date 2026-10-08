package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import com.github.noamm9.nexclusive.utils.render.CircleRenderer.drawAimCircle
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

object BlazeAimHelper : Feature(
    name = "Blaze Aim Helper",
    description = "Automatically aims at the correct blaze in the Blaze puzzle."
) {
    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px")
        .section("Aim Radius")
        .withDescription("Screen radius of the circle to detect and aim at the correct blaze.")

    private val drawCircle by ToggleSetting("Draw Circle", true)
        .withDescription("Draws the detection circle while inside the Blaze room.")

    private val circleColor by ColorSetting("Circle Color", Color(255, 170, 0, 140), true)
        .withDescription("Color and transparency of the detection circle.")
        .showIf { drawCircle.value }

    private val hitboxOffset by SliderSetting("Hitbox Offset", 0.0, -1.0, 1.0, 0.05)
        .section("Targeting")
        .withDescription("Vertical target offset: 0 is center, + is upper half up to top, - is lower half down to bottom.")

    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1)
        .section("Aim Speed")
        .withDescription("Speed at which your crosshair rotates towards the blaze.")

    private val aimDuration by SliderSetting("Aim Duration", 0.5, 0.0, 5.0, 0.1, "s")
        .section("Timing")
        .withDescription("Duration to hold aim on the blaze before releasing.")

    private val aimCooldown by SliderSetting("Aim Cooldown", 1.0, 0.0, 5.0, 0.1, "s")
        .withDescription("Time before re-aiming at the same blaze. Set to 0 to continuously aim.")

    private val blazeHpRegex = Regex("""^\[Lv\d+].+Blaze [\d,]+/([\d,]+)❤$""")
    private val blazes = CopyOnWriteArrayList<Blaze>()
    private val hpMap = ConcurrentHashMap<Int, Int>()

    private var inBlazeRoom = false
    private var reversed = false

    private val blazeAimedTimes = HashMap<Int, Long>()
    private var currentAimingBlazeId: Int? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L

    private fun isInBlazeRoom(): Boolean {
        if (!LocationUtils.inDungeon) return false
        val currentRoomName = ScanUtils.currentRoom?.name
        if (currentRoomName != null) {
            val inRoom = currentRoomName.contains("Blaze", ignoreCase = true)
            inBlazeRoom = inRoom
            reversed = currentRoomName.equals("Lower Blaze", ignoreCase = true)
            return inRoom
        }
        return inBlazeRoom
    }

    private fun hasLineOfSight(eyePos: Vec3, targetPos: Vec3): Boolean {
        var start = eyePos
        val dir = targetPos.subtract(eyePos).normalize()
        var iterations = 0

        while (iterations++ < 16) {
            val clip = ClipContext(start, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            val hit = level.clip(clip)

            if (hit.type == HitResult.Type.MISS) return true

            val state = level.getBlockState(hit.blockPos)
            if (state.block == Blocks.IRON_BARS) {
                val hitLoc = hit.location
                val nextStart = hitLoc.add(dir.scale(0.1))
                if (nextStart.distanceToSqr(eyePos) >= eyePos.distanceToSqr(targetPos)) {
                    return true
                }
                if (nextStart.distanceToSqr(start) < 0.0001) {
                    start = start.add(dir.scale(0.2))
                } else {
                    start = nextStart
                }
            } else {
                return false
            }
        }
        return false
    }

    private fun isLineBlockedByOtherBlaze(eyePos: Vec3, targetPos: Vec3, targetBlazeId: Int): Boolean {
        val dir = targetPos.subtract(eyePos).normalize()
        val rayEnd = eyePos.add(dir.scale(60.0))
        val distToTargetSq = eyePos.distanceToSqr(targetPos)

        for (other in blazes) {
            if (other.id == targetBlazeId) continue
            if (!other.isAlive || other.isRemoved) continue

            val otherBb = other.boundingBox.inflate(0.1)
            val hitOpt = otherBb.clip(eyePos, rayEnd)
            if (!hitOpt.isPresent) continue

            val otherHit = hitOpt.get()
            val otherDistSq = eyePos.distanceToSqr(otherHit)

            if (otherDistSq < distToTargetSq) {
                // Another blaze is between player and target blaze
                if (hasLineOfSight(eyePos, otherHit)) return true
            } else {
                // Another blaze is behind target blaze in the same line of fire
                if (hasLineOfSight(targetPos, otherHit)) return true
            }
        }
        return false
    }

    override fun init() {
        register<WorldChangeEvent> {
            resetAll()
        }

        register<DungeonEvent.RoomEvent.onEnter> {
            if (event.room.name.contains("Blaze", ignoreCase = true)) {
                inBlazeRoom = true
                reversed = event.room.name.equals("Lower Blaze", ignoreCase = true)
            }
        }

        register<DungeonEvent.RoomEvent.onExit> {
            if (event.room.name.contains("Blaze", ignoreCase = true)) {
                inBlazeRoom = false
                resetAimState()
            }
        }

        register<TickEvent.Start> {
            if (!isInBlazeRoom()) {
                if (blazes.isNotEmpty()) blazes.clear()
                if (hpMap.isNotEmpty()) hpMap.clear()
                return@register
            }

            val currentLevel = mc.level ?: return@register
            val armorStands = currentLevel.entitiesForRendering().filterIsInstance<ArmorStand>()

            for (armorStand in armorStands) {
                val name = armorStand.customName?.unformattedText ?: continue
                val match = blazeHpRegex.find(name) ?: continue
                val health = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue

                val blaze = currentLevel.getEntitiesOfClass(
                    Blaze::class.java,
                    armorStand.boundingBox.expandTowards(0.0, -2.0, 0.0)
                ).firstOrNull() ?: continue

                if (blaze in blazes || hpMap.containsKey(blaze.id)) continue

                hpMap[blaze.id] = health
                blazes.add(blaze)
            }

            blazes.removeIf { !it.isAlive || it.isRemoved }
            blazes.sortBy { hpMap[it.id] ?: Int.MAX_VALUE }
            if (reversed) blazes.reverse()
        }

        register<RenderOverlayEvent> {
            if (!drawCircle.value) return@register
            if (!isInBlazeRoom()) return@register
            if (blazes.none { it.isAlive && !it.isRemoved }) return@register

            event.drawAimCircle(helperRadius.value, circleColor.value)
        }

        register<RenderWorldEvent> {
            if (mc.screen != null) return@register
            if (!isInBlazeRoom()) {
                resetAimState()
                return@register
            }

            val p = mc.player ?: return@register
            val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)

            val targetBlaze = blazes.firstOrNull { it.isAlive && !it.isRemoved }
            if (targetBlaze == null) {
                resetAimState()
                return@register
            }

            val bb = targetBlaze.boundingBox
            val height = bb.maxY - bb.minY
            val clampedOffset = hitboxOffset.value.coerceIn(-1.0, 1.0)
            val factor = 0.5 + 0.5 * clampedOffset
            val targetY = bb.minY + height * factor
            val targetPos = Vec3((bb.minX + bb.maxX) / 2.0, targetY, (bb.minZ + bb.maxZ) / 2.0)

            if (!hasLineOfSight(eyePos, targetPos)) {
                resetAimState()
                return@register
            }

            if (isLineBlockedByOtherBlaze(eyePos, targetPos, targetBlaze.id)) {
                resetAimState()
                return@register
            }

            val screenPos = ProjectionUtils.worldToScreen(targetPos)
            if (screenPos == null || CircleRenderer.distanceToCenter(screenPos) > helperRadius.value) {
                resetAimState()
                return@register
            }

            val now = System.currentTimeMillis()
            val cooldownMs = (aimCooldown.value * 1000.0).toLong()
            val aimDurationMs = (aimDuration.value * 1000.0).toLong()

            if (cooldownMs > 0L && now - (blazeAimedTimes[targetBlaze.id] ?: 0L) < cooldownMs) {
                return@register
            }

            if (targetBlaze.id != currentAimingBlazeId) {
                currentAimingBlazeId = targetBlaze.id
                aimLockStartTime = 0L
                lastFrameTime = 0L
            }

            val targetRot = MathUtils.calcYawPitch(targetPos)

            if (aimLockStartTime > 0L) {
                if (now - aimLockStartTime < aimDurationMs) {
                    PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                    return@register
                }
                blazeAimedTimes[targetBlaze.id] = now
                currentAimingBlazeId = null
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
                    blazeAimedTimes[targetBlaze.id] = now
                    currentAimingBlazeId = null
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
                    blazeAimedTimes[targetBlaze.id] = now
                    currentAimingBlazeId = null
                    aimLockStartTime = 0L
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        resetAll()
    }

    private fun resetAimState() {
        currentAimingBlazeId = null
        aimLockStartTime = 0L
        lastFrameTime = 0L
    }

    private fun resetAll() {
        resetAimState()
        blazeAimedTimes.clear()
        blazes.clear()
        hpMap.clear()
        inBlazeRoom = false
        reversed = false
    }
}
