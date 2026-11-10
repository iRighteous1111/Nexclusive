package com.github.noamm9.nexclusive.features.impl.nexclusive.blaze

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.WorldUtils
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
    private val blazeHpRegex = Regex("""^\[Lv\d+].+Blaze [\d,]+/([\d,]+)❤$""")
    private val lowerRelSpot = BlockPos(8, 47, 13)
    private val higherRelSpot = BlockPos(9, 95, 18)

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

    fun updateRoomState(): Boolean {
        val p = mc.player
        val roomName = ScanUtils.currentRoom?.name
        if (
            p == null ||
            ! LocationUtils.inDungeon ||
            LocationUtils.inBoss ||
            p.x > - 8.0 ||
            p.z > - 8.0 ||
            roomName == null ||
            ! roomName.contains("Blaze", ignoreCase = true)
        ) {
            if (inBlazeRoom) reset()
            return false
        }

        reversed = roomName.equals("Lower Blaze", ignoreCase = true)
        val spot = getOptimalSpot()
        if (spot != null && (kotlin.math.abs(p.x - spot.x) > 25.0 || kotlin.math.abs(p.z - spot.z) > 25.0)) {
            if (inBlazeRoom) reset()
            return false
        }

        inBlazeRoom = true
        return true
    }

    fun scanBlazes(hitboxOffset: Double) {
        val level = mc.level ?: return
        val found = ArrayList<Blaze>()
        hpMap.clear()

        level.entitiesForRendering().filterIsInstance<ArmorStand>().forEach { armorStand ->
            val name = armorStand.customName?.unformattedText ?: return@forEach
            val health = blazeHpRegex.find(name)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return@forEach

            val blaze = level.getEntitiesOfClass(
                Blaze::class.java,
                armorStand.boundingBox.expandTowards(0.0, - 2.0, 0.0)
            ).firstOrNull() ?: return@forEach

            if (! blaze.isAlive || blaze.isRemoved || hpMap.containsKey(blaze.id)) return@forEach
            hpMap[blaze.id] = health
            found.add(blaze)
        }

        found.sortBy { hpMap[it.id] }
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

    fun getOptimalSpot(): BlockPos? {
        val room = ScanUtils.currentRoom ?: return null
        val clayPos = room.clayPos ?: return null
        val rot = room.rotation ?: return null
        return ScanUtils.getRealCoord(if (reversed) lowerRelSpot else higherRelSpot, clayPos, 360 - rot)
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
