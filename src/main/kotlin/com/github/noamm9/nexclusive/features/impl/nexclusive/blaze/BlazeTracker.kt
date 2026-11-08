package com.github.noamm9.nexclusive.features.impl.nexclusive.blaze

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.Puzzle
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.*
import kotlin.math.ceil

object BlazeTracker {
    private val blazeHpRegex = Regex("""Blaze [\d,]+/([\d,]+)❤""")

    private val lowerRelSpot = BlockPos(8, 47, 13)
    private val higherRelSpot = BlockPos(9, 95, 18)
    private val lowerFallbackSpot = BlockPos(- 32, 47, - 27)
    private val higherFallbackSpot = BlockPos(- 150, 95, - 83)

    data class ShotInfo(val shotTimeMs: Long, val expectedDeathTick: Long)

    val blazes = CopyOnWriteArrayList<Blaze>()
    private val hpMap = ConcurrentHashMap<Int, Int>()
    val shotBlazes = ConcurrentHashMap<Int, ShotInfo>()

    var inBlazeRoom = false
    var reversed = false
    var targetBlaze: Blaze? = null
    var targetHasLineOfSight = false
    var currentTick = 0L
    var lastShotTime = 0L

    fun isHoldingBow(): Boolean {
        val held = mc.player?.mainHandItem?.takeUnless { it.isEmpty } ?: return false
        return held.`is`(Items.BOW) || held.skyblockId.let { it.contains("BOW") || it == "TERMINATOR" }
    }

    private fun parseHealth(name: String): Int? {
        return blazeHpRegex.find(name)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }

    private fun hasNearbyBlazeStands(): Boolean {
        val p = mc.player ?: return false
        return mc.level?.entitiesForRendering()?.any {
            it is ArmorStand && it.distanceToSqr(p) <= 2025.0 && it.customName?.unformattedText?.let(::parseHealth) != null
        } == true
    }

    fun onRoomEnter(roomName: String) {
        if (! roomName.contains("Blaze", ignoreCase = true)) return
        inBlazeRoom = true
        reversed = roomName.equals("Lower Blaze", ignoreCase = true)
    }

    fun onRoomExit(roomName: String): Boolean {
        if (! roomName.contains("Blaze", ignoreCase = true)) return false
        reset()
        return true
    }

    fun updateRoomState(): Boolean {
        if (! LocationUtils.inDungeon || LocationUtils.inBoss) {
            if (inBlazeRoom) reset()
            return false
        }

        val roomName = ScanUtils.currentRoom?.name
        if (roomName != null) {
            if (roomName.contains("Blaze", ignoreCase = true)) {
                inBlazeRoom = true
                reversed = roomName.contains("Lower", ignoreCase = true)
            } else if (inBlazeRoom) {
                reset()
            }
            return inBlazeRoom
        }

        val hasBlazePuzzle = DungeonListener.puzzles.any { it == Puzzle.LOWER_BLAZE || it == Puzzle.HIGHER_BLAZE }
        if (! hasBlazePuzzle && ! inBlazeRoom) return false

        if (blazes.any { it.isAlive && ! it.isRemoved } || hasNearbyBlazeStands()) {
            inBlazeRoom = true
            reversed = when {
                Puzzle.LOWER_BLAZE in DungeonListener.puzzles -> true
                Puzzle.HIGHER_BLAZE in DungeonListener.puzzles -> false
                else -> (blazes.firstOrNull()?.y ?: mc.player?.y ?: 100.0) < 95.0
            }
        } else if (inBlazeRoom) {
            reset()
        }
        return inBlazeRoom
    }

    fun scanBlazes(hitboxOffset: Double) {
        val level = mc.level ?: return
        val p = mc.player ?: return
        val found = ArrayList<Blaze>()
        hpMap.clear()

        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.distanceToSqr(p) > 2025.0) continue
            val health = entity.customName?.unformattedText?.let(::parseHealth) ?: continue

            val blaze = level.getEntitiesOfClass(
                Blaze::class.java,
                entity.boundingBox.inflate(1.5, 3.0, 1.5)
            ).minByOrNull { it.distanceToSqr(entity) } ?: continue

            if (! blaze.isAlive || blaze.isRemoved || hpMap.containsKey(blaze.id)) continue
            hpMap[blaze.id] = health
            found.add(blaze)
        }

        found.sortBy { hpMap[it.id] ?: Int.MAX_VALUE }
        if (reversed) found.reverse()

        blazes.clear()
        blazes.addAll(found)
        updateTarget(hitboxOffset)
    }

    fun updateTarget(hitboxOffset: Double) {
        val now = System.currentTimeMillis()
        shotBlazes.entries.removeIf { (id, info) ->
            now - info.shotTimeMs > 3000L || blazes.none { it.id == id && it.isAlive && ! it.isRemoved }
        }

        val target = blazes.firstOrNull { it.isAlive && ! it.isRemoved && it.id !in shotBlazes.keys }
        targetBlaze = target

        val p = mc.player
        if (target == null || p == null) {
            targetHasLineOfSight = false
            return
        }

        val eyePos = p.eyePosition
        val targetPos = getTargetPos(target, hitboxOffset)
        val dir = targetPos.subtract(eyePos).normalize()
        targetHasLineOfSight = hasLineOfSight(eyePos, targetPos) && ! hitsOtherBlaze(eyePos, dir, target.id)
    }

    fun getOptimalSpot(): BlockPos {
        val room = ScanUtils.currentRoom
        val clayPos = room?.clayPos
        val rot = room?.rotation
        if (clayPos != null && rot != null) {
            return ScanUtils.getRealCoord(if (reversed) lowerRelSpot else higherRelSpot, clayPos, 360 - rot)
        }
        return if (reversed) lowerFallbackSpot else higherFallbackSpot
    }

    fun isStandingOnSpot(spot: BlockPos): Boolean {
        val p = mc.player ?: return false
        return p.distanceToSqr(spot.x + 0.5, spot.y + 1.0, spot.z + 0.5) <= 12.25
    }

    fun getTargetPos(target: Blaze, hitboxOffset: Double): Vec3 {
        val bb = target.boundingBox
        val factor = 0.5 + 0.5 * hitboxOffset.coerceIn(- 1.0, 1.0)
        return Vec3((bb.minX + bb.maxX) / 2.0, bb.minY + (bb.maxY - bb.minY) * factor, (bb.minZ + bb.maxZ) / 2.0)
    }

    fun isLookingAtBlaze(blaze: Blaze, eyePos: Vec3): Boolean {
        val p = mc.player ?: return false
        if (mc.crosshairPickEntity?.id == blaze.id) return true
        val rayEnd = eyePos.add(MathUtils.getLookVec(p.yRot, p.xRot).scale(60.0))
        return blaze.boundingBox.inflate(0.05).clip(eyePos, rayEnd).isPresent
    }

    fun hitsOtherBlaze(eyePos: Vec3, dir: Vec3, ignoreId: Int): Boolean {
        val rayEnd = eyePos.add(dir.scale(60.0))
        return blazes.any { other ->
            other.id != ignoreId && other.id !in shotBlazes.keys && other.isAlive && ! other.isRemoved &&
                other.boundingBox.inflate(0.12).clip(eyePos, rayEnd).orElse(null)?.let { hasLineOfSight(eyePos, it) } == true
        }
    }

    fun isShotSafe(eyePos: Vec3, hitboxOffset: Double): Boolean {
        val p = mc.player ?: return false
        val target = targetBlaze?.takeIf { it.isAlive && ! it.isRemoved } ?: return false
        if (! isLookingAtBlaze(target, eyePos)) return false

        val targetPos = getTargetPos(target, hitboxOffset)
        if (! hasLineOfSight(eyePos, targetPos)) return false
        if (hitsOtherBlaze(eyePos, targetPos.subtract(eyePos).normalize(), target.id)) return false
        if (hitsOtherBlaze(eyePos, MathUtils.getLookVec(p.yRot, p.xRot), target.id)) return false
        return true
    }

    fun canPrefire(prefireTicks: Int): Boolean {
        val activeDeathTick = shotBlazes.entries
            .filter { (id, _) -> blazes.any { it.id == id && it.isAlive && ! it.isRemoved } }
            .maxOfOrNull { it.value.expectedDeathTick } ?: return true
        return currentTick >= activeDeathTick - prefireTicks
    }

    fun recordShot(target: Blaze, hitboxOffset: Double) {
        val now = System.currentTimeMillis()
        val dist = mc.player?.distanceTo(target)?.toDouble() ?: 15.0
        val flightTicks = ceil(dist / 2.8).toLong().coerceIn(2L, 12L)
        lastShotTime = now
        shotBlazes[target.id] = ShotInfo(now, currentTick + flightTicks)
        updateTarget(hitboxOffset)
    }

    fun hasLineOfSight(eyePos: Vec3, targetPos: Vec3): Boolean {
        val level = mc.level ?: return false
        val p = mc.player ?: return false
        val totalDistSq = eyePos.distanceToSqr(targetPos)
        if (totalDistSq < 0.01) return true

        val dir = targetPos.subtract(eyePos).normalize()
        var start = eyePos

        repeat(16) {
            val hit = level.clip(ClipContext(start, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
            if (hit.type == HitResult.Type.MISS) return true
            if (WorldUtils.getBlockAt(hit.blockPos) != Blocks.IRON_BARS) return false

            val next = hit.location.add(dir.scale(0.1))
            if (next.distanceToSqr(eyePos) >= totalDistSq) return true
            start = if (next.distanceToSqr(start) < 0.0001) start.add(dir.scale(0.2)) else next
        }
        return false
    }

    fun reset() {
        blazes.clear()
        hpMap.clear()
        shotBlazes.clear()
        targetBlaze = null
        targetHasLineOfSight = false
        inBlazeRoom = false
        reversed = false
    }
}
