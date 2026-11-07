package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.MouseClickEvent
import com.github.noamm9.event.impl.PlayerInteractEvent
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
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.Puzzle
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    private val blazes = CopyOnWriteArrayList<Blaze>()
    private val hpMap = ConcurrentHashMap<Int, Int>()
    private val shotBlazes = ConcurrentHashMap<Int, Long>()

    private var inBlazeRoom = false
    private var reversed = false
    private var targetBlaze: Blaze? = null
    private var targetHasLineOfSight = false

    private val blazeAimedTimes = HashMap<Int, Long>()
    private var currentAimingBlazeId: Int? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L
    private var lastShotTime = 0L

    private fun isHoldingBow(): Boolean {
        val p = mc.player ?: return false
        val held = p.mainHandItem
        if (held.isEmpty) return false
        if (held.`is`(Items.BOW)) return true
        val id = held.skyblockId
        return id.contains("BOW") || id == "TERMINATOR"
    }

    private fun parseBlazeHealth(name: String): Int? {
        if (!name.contains("Blaze", ignoreCase = true)) return null
        val slashIndex = name.indexOf('/')
        if (slashIndex == -1) return null

        var end = slashIndex + 1
        while (end < name.length && (name[end].isDigit() || name[end] == ',')) {
            end++
        }
        if (end <= slashIndex + 1) return null

        return name.substring(slashIndex + 1, end).replace(",", "").toIntOrNull()
    }

    private fun hasNearbyBlazeArmorStands(): Boolean {
        val lvl = mc.level ?: return false
        val p = mc.player ?: return false
        for (entity in lvl.entitiesForRendering()) {
            if (entity is ArmorStand && entity.distanceToSqr(p) <= 45.0 * 45.0) {
                val name = entity.customName?.unformattedText ?: continue
                if (parseBlazeHealth(name) != null) return true
            }
        }
        return false
    }

    private fun updateRoomState() {
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) {
            if (inBlazeRoom) resetAll()
            return
        }

        val roomName = ScanUtils.currentRoom?.name
        if (roomName != null) {
            if (roomName.contains("Blaze", ignoreCase = true)) {
                inBlazeRoom = true
                reversed = roomName.contains("Lower", ignoreCase = true)
            } else if (inBlazeRoom) {
                resetAll()
            }
            return
        }

        val hasBlazePuzzle = DungeonListener.puzzles.any { it == Puzzle.LOWER_BLAZE || it == Puzzle.HIGHER_BLAZE }
        if (!hasBlazePuzzle && !inBlazeRoom) return

        val hasEntities = blazes.any { it.isAlive && !it.isRemoved } || hasNearbyBlazeArmorStands()
        if (hasEntities) {
            inBlazeRoom = true
            reversed = when {
                DungeonListener.puzzles.any { it == Puzzle.LOWER_BLAZE } -> true
                DungeonListener.puzzles.any { it == Puzzle.HIGHER_BLAZE } -> false
                else -> (blazes.firstOrNull()?.y ?: player.y) < 95.0
            }
        } else if (inBlazeRoom) {
            resetAll()
        }
    }

    private fun getBlazeTargetPos(target: Blaze): Vec3 {
        val bb = target.boundingBox
        val height = bb.maxY - bb.minY
        val factor = 0.5 + 0.5 * hitboxOffset.value.coerceIn(-1.0, 1.0)
        return Vec3((bb.minX + bb.maxX) / 2.0, bb.minY + height * factor, (bb.minZ + bb.maxZ) / 2.0)
    }

    private fun updateTargetBlaze() {
        val now = System.currentTimeMillis()
        shotBlazes.entries.removeIf { (id, shotTime) ->
            now - shotTime > 3000L || blazes.none { it.id == id && it.isAlive && !it.isRemoved }
        }
        val target = blazes.firstOrNull { it.isAlive && !it.isRemoved && it.id !in shotBlazes.keys }
        targetBlaze = target
        val p = mc.player
        if (target == null || p == null) {
            targetHasLineOfSight = false
            return
        }
        val eyePos = p.eyePosition
        val targetPos = getBlazeTargetPos(target)
        targetHasLineOfSight = hasLineOfSight(eyePos, targetPos) && !isLineBlockedByOtherBlaze(eyePos, targetPos, target.id)
    }

    private fun isLookingAtBlaze(blaze: Blaze, eyePos: Vec3): Boolean {
        if (mc.crosshairPickEntity?.id == blaze.id) return true
        val lookVec = MathUtils.getLookVec(player.yRot, player.xRot)
        val rayEnd = eyePos.add(lookVec.scale(60.0))
        return blaze.boundingBox.inflate(0.05).clip(eyePos, rayEnd).isPresent
    }

    private fun handleShoot() {
        if (!inBlazeRoom || !isHoldingBow()) return
        val now = System.currentTimeMillis()
        if (now - lastShotTime < 100L) return

        val p = mc.player ?: return
        val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)
        val target = targetBlaze ?: return

        val isLooking = isLookingAtBlaze(target, eyePos)
        val screenPos = ProjectionUtils.worldToScreen(target.position().add(0.0, target.bbHeight / 2.0, 0.0))
        val isNearScreen = CircleRenderer.isInsideCircle(screenPos, helperRadius.value)

        if (isLooking || isNearScreen) {
            lastShotTime = now
            shotBlazes[target.id] = now
            resetAimState()
            updateTargetBlaze()
        }
    }

    private fun hasLineOfSight(eyePos: Vec3, targetPos: Vec3): Boolean {
        var start = eyePos
        val totalDistSq = eyePos.distanceToSqr(targetPos)
        if (totalDistSq < 0.01) return true

        val dir = targetPos.subtract(eyePos).normalize()
        var iterations = 0

        while (iterations++ < 16) {
            val hit = level.clip(ClipContext(start, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.MISS) return true

            if (WorldUtils.getBlockAt(hit.blockPos) == Blocks.IRON_BARS) {
                val nextStart = hit.location.add(dir.scale(0.1))
                if (nextStart.distanceToSqr(eyePos) >= totalDistSq) return true
                start = if (nextStart.distanceToSqr(start) < 0.0001) start.add(dir.scale(0.2)) else nextStart
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
            if (other.id == targetBlazeId || !other.isAlive || other.isRemoved) continue
            val hitOpt = other.boundingBox.inflate(0.1).clip(eyePos, rayEnd)
            if (!hitOpt.isPresent) continue

            val otherHit = hitOpt.get()
            val checkFrom = if (eyePos.distanceToSqr(otherHit) < distToTargetSq) eyePos else targetPos
            if (hasLineOfSight(checkFrom, otherHit)) return true
        }
        return false
    }

    private fun scanBlazes() {
        val currentLevel = mc.level ?: return
        val p = mc.player ?: return

        val foundBlazes = ArrayList<Blaze>()
        hpMap.clear()

        for (entity in currentLevel.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.distanceToSqr(p) > 45.0 * 45.0) continue
            val name = entity.customName?.unformattedText ?: continue
            val health = parseBlazeHealth(name) ?: continue

            val blaze = currentLevel.getEntitiesOfClass(
                Blaze::class.java,
                entity.boundingBox.inflate(1.5, 3.0, 1.5)
            ).minByOrNull { it.distanceToSqr(entity) } ?: continue

            if (!blaze.isAlive || blaze.isRemoved || hpMap.containsKey(blaze.id)) continue
            hpMap[blaze.id] = health
            foundBlazes.add(blaze)
        }

        foundBlazes.sortBy { hpMap[it.id] ?: Int.MAX_VALUE }
        if (reversed) foundBlazes.reverse()

        blazes.clear()
        blazes.addAll(foundBlazes)
        updateTargetBlaze()
    }

    override fun init() {
        register<WorldChangeEvent> { resetAll() }

        register<DungeonEvent.RoomEvent.onEnter> {
            if (event.room.name.contains("Blaze", ignoreCase = true)) {
                inBlazeRoom = true
                reversed = event.room.name.equals("Lower Blaze", ignoreCase = true)
            }
        }

        register<DungeonEvent.RoomEvent.onExit> {
            if (event.room.name.contains("Blaze", ignoreCase = true)) {
                resetAll()
            }
        }

        register<PlayerInteractEvent.RIGHT_CLICK.AIR> { handleShoot() }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY> { handleShoot() }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { handleShoot() }
        register<MouseClickEvent> {
            if (event.button == 1 && event.action == 1) handleShoot()
        }

        register<TickEvent.Start> {
            updateRoomState()
            if (inBlazeRoom) scanBlazes()
        }

        register<RenderOverlayEvent> {
            if (!drawCircle.value || mc.gui.screen() != null) return@register
            if (!inBlazeRoom || !isHoldingBow()) return@register

            event.drawAimCircle(helperRadius.value, circleColor.value)
        }

        register<RenderWorldEvent> {
            if (mc.gui.screen() != null) return@register
            if (!inBlazeRoom || !isHoldingBow()) return@register resetAimState()

            val target = targetBlaze
            if (target == null || !target.isAlive || target.isRemoved || !targetHasLineOfSight) {
                return@register resetAimState()
            }

            val p = mc.player ?: return@register
            val eyePos = p.renderVec.add(0.0, p.eyeHeight.toDouble(), 0.0)

            if (isLookingAtBlaze(target, eyePos)) {
                aimLockStartTime = 0L
                lastFrameTime = 0L
                return@register
            }

            val targetPos = getBlazeTargetPos(target)
            val screenPos = ProjectionUtils.worldToScreen(targetPos)
            if (!CircleRenderer.isInsideCircle(screenPos, helperRadius.value)) {
                return@register resetAimState()
            }

            val now = System.currentTimeMillis()
            val cooldownMs = (aimCooldown.value * 1000.0).toLong()
            val aimDurationMs = (aimDuration.value * 1000.0).toLong()

            if (cooldownMs > 0L && now - (blazeAimedTimes[target.id] ?: 0L) < cooldownMs) return@register

            if (target.id != currentAimingBlazeId) {
                currentAimingBlazeId = target.id
                aimLockStartTime = 0L
                lastFrameTime = 0L
            }

            if (aimLockStartTime > 0L) {
                if (now - aimLockStartTime < aimDurationMs) {
                    AimUtils.rotateTo(targetPos)
                    return@register
                }
                blazeAimedTimes[target.id] = now
                resetAimState()
                return@register
            }

            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            if (AimUtils.rotateTowards(targetPos, rotationSpeed.value, dt)) {
                aimLockStartTime = now
                if (aimDurationMs <= 0L) {
                    blazeAimedTimes[target.id] = now
                    resetAimState()
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
        shotBlazes.clear()
        targetBlaze = null
        targetHasLineOfSight = false
        inBlazeRoom = false
        reversed = false
    }
}
