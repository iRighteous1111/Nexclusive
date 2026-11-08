package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import com.github.noamm9.nexclusive.utils.render.CircleRenderer.drawAimCircle
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.ColorUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.MathUtils.center
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.Puzzle
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.RenderHelper.renderVec
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderTracer
import net.minecraft.core.BlockPos
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
import kotlin.math.ceil

object BlazeUtils: Feature(
    name = "Blaze Utils",
    description = "Blaze puzzle aim helper, optimal spot highlight, triggerbot, and wrong click blocker."
) {
    // 1. Aim Helper
    private val aimHelper by ToggleSetting("Enable Aim Helper", true)
        .section("Aim Helper")
        .withDescription("Automatically aims at the correct blaze inside the detection circle.")

    private val helperRadius by SliderSetting("Helper Radius", 80, 10, 300, 5, "px")
        .withDescription("Screen radius of the circle to detect and aim at the correct blaze.")
        .showIf { aimHelper.value }

    private val drawCircle by ToggleSetting("Draw Circle", true)
        .withDescription("Draws the detection circle while inside the Blaze room.")
        .showIf { aimHelper.value }

    private val circleColor by ColorSetting("Circle Color", Color(255, 170, 0, 140), true)
        .withDescription("Color and transparency of the detection circle.")
        .showIf { aimHelper.value && drawCircle.value }

    private val hitboxOffset by SliderSetting("Hitbox Offset", 0.0, -1.0, 1.0, 0.05)
        .withDescription("Vertical target offset: 0 is center, + is upper half up to top, - is lower half down to bottom.")
        .showIf { aimHelper.value }

    private val rotationSpeed by SliderSetting("Rotation Speed", 10.0, 1.0, 50.0, 0.1)
        .withDescription("Speed at which your crosshair rotates towards the blaze.")
        .showIf { aimHelper.value }

    private val aimDuration by SliderSetting("Aim Duration", 0.5, 0.0, 5.0, 0.1, "s")
        .withDescription("Duration to hold aim on the blaze before releasing.")
        .showIf { aimHelper.value }

    private val aimCooldown by SliderSetting("Aim Cooldown", 1.0, 0.0, 5.0, 0.1, "s")
        .withDescription("Time before re-aiming at the same blaze. Set to 0 to continuously aim.")
        .showIf { aimHelper.value }

    // 2. Extras
    private val highlightOptimalSpot by ToggleSetting("Highlight Optimal Spot", false)
        .section("Extras")
        .withDescription("Highlights the optimal standing block for Lower and Higher Blaze.")

    private val highlightedBlockColor by ColorSetting("Highlighted Block Color", Color(0, 255, 255, 110), true)
        .withDescription("Color of the highlighted optimal spot block.")
        .showIf { highlightOptimalSpot.value && ! chroma.value }

    private val chroma by ToggleSetting("Chroma", false)
        .withDescription("Cycles the highlighted block and tracer through RGB colors.")
        .showIf { highlightOptimalSpot.value }

    private val drawTracer by ToggleSetting("Draw Tracer", false)
        .withDescription("Draws a tracer from your crosshair to the optimal spot until you stand on it.")
        .showIf { highlightOptimalSpot.value }

    private val blazeTriggerbot by ToggleSetting("Blaze Triggerbot", false)
        .withDescription("Automatically shoots once with Terminator when aiming at the correct blaze.")

    private val interactDelay by SliderSetting("Interact Delay", 2, 0, 10, 1, "t")
        .withDescription("Game ticks to wait after aiming at the blaze before shooting.")
        .showIf { blazeTriggerbot.value }

    private val prefireTicks by SliderSetting("Prefire Ticks", 3, 0, 10, 1, "t")
        .withDescription("Ticks before the previous shot blaze dies to prefire the next blaze.")
        .showIf { blazeTriggerbot.value }

    private val blockWrongClicks by ToggleSetting("Block Wrong Clicks", false)
        .withDescription("Prevents shooting if aiming at a wrong blaze or if the arrow would hit another blaze behind it.")

    private val overrideClicks by SliderSetting("Override Clicks", 2, 1, 10, 1)
        .withDescription("Consecutive clicks required to override and shoot anyway.")
        .showIf { blockWrongClicks.value }

    // Relative coordinates from room clayPos (matches -31, 48, -26 in Lower Blaze and -149, 96, -82 in Higher Blaze)
    private val lowerBlazeRelSpot = BlockPos(9, 48, 14)
    private val higherBlazeRelSpot = BlockPos(8, 96, 19)
    private val lowerBlazeFallbackSpot = BlockPos(-31, 48, -26)
    private val higherBlazeFallbackSpot = BlockPos(-149, 96, -82)

    private data class ShotInfo(val shotTimeMs: Long, val expectedDeathTick: Long)

    private val blazes = CopyOnWriteArrayList<Blaze>()
    private val hpMap = ConcurrentHashMap<Int, Int>()
    private val shotBlazes = ConcurrentHashMap<Int, ShotInfo>()

    private var inBlazeRoom = false
    private var reversed = false
    private var targetBlaze: Blaze? = null
    private var targetHasLineOfSight = false

    private val blazeAimedTimes = HashMap<Int, Long>()
    private var currentAimingBlazeId: Int? = null
    private var aimLockStartTime = 0L
    private var lastFrameTime = 0L
    private var lastShotTime = 0L

    private var currentTick = 0L
    private var triggerbotHoverTicks = 0
    private var lastHoveredBlazeId: Int? = null
    private var isTriggerbotClicking = false
    private var consecutiveWrongClicks = 0

    private fun isHoldingBow(): Boolean {
        val p = mc.player ?: return false
        val held = p.mainHandItem
        if (held.isEmpty) return false
        if (held.`is`(Items.BOW)) return true
        val id = held.skyblockId
        return id.contains("BOW") || id == "TERMINATOR"
    }

    private fun parseBlazeHealth(name: String): Int? {
        if (! name.contains("Blaze", ignoreCase = true)) return null
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
        if (! LocationUtils.inDungeon || LocationUtils.inBoss) {
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
        if (! hasBlazePuzzle && ! inBlazeRoom) return

        val hasEntities = blazes.any { it.isAlive && ! it.isRemoved } || hasNearbyBlazeArmorStands()
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

    private fun getOptimalSpotPos(): BlockPos {
        val room = ScanUtils.currentRoom
        val clayPos = room?.clayPos
        val rot = room?.rotation
        if (clayPos != null && rot != null) {
            val rel = if (reversed) lowerBlazeRelSpot else higherBlazeRelSpot
            return ScanUtils.getRealCoord(rel, clayPos, 360 - rot)
        }
        return if (reversed) lowerBlazeFallbackSpot else higherBlazeFallbackSpot
    }

    private fun isStandingOnSpot(spot: BlockPos): Boolean {
        val p = mc.player ?: return false
        return p.x in (spot.x - 0.35)..(spot.x + 1.35) &&
            p.z in (spot.z - 0.35)..(spot.z + 1.35) &&
            p.y in (spot.y - 0.5)..(spot.y + 2.5)
    }

    private fun getBlazeTargetPos(target: Blaze): Vec3 {
        val bb = target.boundingBox
        val height = bb.maxY - bb.minY
        val factor = 0.5 + 0.5 * hitboxOffset.value.coerceIn(-1.0, 1.0)
        return Vec3((bb.minX + bb.maxX) / 2.0, bb.minY + height * factor, (bb.minZ + bb.maxZ) / 2.0)
    }

    private fun updateTargetBlaze() {
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
        val targetPos = getBlazeTargetPos(target)
        targetHasLineOfSight = hasLineOfSight(eyePos, targetPos) && ! isLineBlockedByOtherBlaze(eyePos, targetPos, target.id)
    }

    private fun isLookingAtBlaze(blaze: Blaze, eyePos: Vec3): Boolean {
        if (mc.crosshairPickEntity?.id == blaze.id) return true
        val lookVec = MathUtils.getLookVec(player.yRot, player.xRot)
        val rayEnd = eyePos.add(lookVec.scale(60.0))
        return blaze.boundingBox.inflate(0.05).clip(eyePos, rayEnd).isPresent
    }

    private fun willRayHitAnotherBlaze(eyePos: Vec3, lookVec: Vec3, allowedBlazeId: Int?): Boolean {
        val rayEnd = eyePos.add(lookVec.scale(60.0))
        for (other in blazes) {
            if (! other.isAlive || other.isRemoved) continue
            if (other.id == allowedBlazeId || other.id in shotBlazes.keys) continue
            val hitOpt = other.boundingBox.inflate(0.12).clip(eyePos, rayEnd)
            if (! hitOpt.isPresent) continue
            if (hasLineOfSight(eyePos, hitOpt.get())) return true
        }
        return false
    }

    private fun isShotSafe(eyePos: Vec3): Boolean {
        val target = targetBlaze ?: return false
        if (! target.isAlive || target.isRemoved) return false
        val lookVec = MathUtils.getLookVec(player.yRot, player.xRot)

        // Must be looking at the target blaze, have clear line of sight, and not pierce/hit any other blaze
        if (! isLookingAtBlaze(target, eyePos)) return false
        val targetPos = getBlazeTargetPos(target)
        if (! hasLineOfSight(eyePos, targetPos)) return false
        if (isLineBlockedByOtherBlaze(eyePos, targetPos, target.id)) return false
        if (willRayHitAnotherBlaze(eyePos, lookVec, target.id)) return false
        return true
    }

    private fun recordShot(target: Blaze) {
        val now = System.currentTimeMillis()
        val p = mc.player
        val dist = if (p != null) p.distanceTo(target).toDouble() else 15.0
        val flightTicks = ceil(dist / 2.8).toLong().coerceIn(2L, 12L)
        lastShotTime = now
        shotBlazes[target.id] = ShotInfo(now, currentTick + flightTicks)
        resetAimState()
        triggerbotHoverTicks = 0
        lastHoveredBlazeId = null
        updateTargetBlaze()
    }

    private fun handlePlayerInteract(event: PlayerInteractEvent, isLeftClick: Boolean) {
        if (! inBlazeRoom || blazes.isEmpty() || ! isHoldingBow()) return
        if (isLeftClick && player.mainHandItem.skyblockId != "TERMINATOR") return
        if (isTriggerbotClicking) return

        val p = mc.player ?: return
        val eyePos = p.eyePosition

        if (blockWrongClicks.value && ! isShotSafe(eyePos)) {
            consecutiveWrongClicks++
            if (consecutiveWrongClicks < overrideClicks.value) {
                event.cancel()
                val needed = overrideClicks.value - consecutiveWrongClicks
                ChatUtils.modMessage("§cBlocked wrong blaze shot! Click §e$needed §cmore time(s) to override.")
                return
            }
            consecutiveWrongClicks = 0
            return
        }

        consecutiveWrongClicks = 0
        val now = System.currentTimeMillis()
        if (now - lastShotTime < 100L) return

        val target = targetBlaze ?: return
        val isLooking = isLookingAtBlaze(target, eyePos)
        val screenPos = ProjectionUtils.worldToScreen(target.position().add(0.0, target.bbHeight / 2.0, 0.0))
        val isNearScreen = aimHelper.value && CircleRenderer.isInsideCircle(screenPos, helperRadius.value)

        if (isLooking || isNearScreen) {
            recordShot(target)
        }
    }

    private fun runTriggerbotTick() {
        if (! blazeTriggerbot.value || mc.gui.screen() != null || ! inBlazeRoom || ! isHoldingBow()) {
            triggerbotHoverTicks = 0
            lastHoveredBlazeId = null
            return
        }

        val p = mc.player ?: return
        val target = targetBlaze
        val eyePos = p.eyePosition

        if (target == null || ! isShotSafe(eyePos)) {
            triggerbotHoverTicks = 0
            lastHoveredBlazeId = null
            return
        }

        if (lastHoveredBlazeId != target.id) {
            lastHoveredBlazeId = target.id
            triggerbotHoverTicks = 0
        } else {
            triggerbotHoverTicks++
        }

        if (triggerbotHoverTicks < interactDelay.value) return

        // If a previously shot blaze is still alive in the world, only prefire when within prefireTicks of its death
        val activeShotDeathTick = shotBlazes.entries
            .filter { (id, _) -> blazes.any { it.id == id && it.isAlive && ! it.isRemoved } }
            .maxOfOrNull { it.value.expectedDeathTick }

        if (activeShotDeathTick != null && currentTick < activeShotDeathTick - prefireTicks.value) {
            return
        }

        isTriggerbotClicking = true
        PlayerUtils.rightClick()
        isTriggerbotClicking = false
        recordShot(target)
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
            if (other.id == targetBlazeId || other.id in shotBlazes.keys || ! other.isAlive || other.isRemoved) continue
            val hitOpt = other.boundingBox.inflate(0.12).clip(eyePos, rayEnd)
            if (! hitOpt.isPresent) continue

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

            if (! blaze.isAlive || blaze.isRemoved || hpMap.containsKey(blaze.id)) continue
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

        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST) { handlePlayerInteract(event, false) }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST) { handlePlayerInteract(event, false) }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST) { handlePlayerInteract(event, false) }
        register<PlayerInteractEvent.LEFT_CLICK.AIR>(EventPriority.HIGHEST) { handlePlayerInteract(event, true) }
        register<PlayerInteractEvent.LEFT_CLICK.ENTITY>(EventPriority.HIGHEST) { handlePlayerInteract(event, true) }
        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST) { handlePlayerInteract(event, true) }

        register<TickEvent.Start> {
            currentTick++
            updateRoomState()
            if (! inBlazeRoom) return@register
            scanBlazes()
            runTriggerbotTick()
        }

        register<RenderOverlayEvent> {
            if (! aimHelper.value || ! drawCircle.value || mc.gui.screen() != null) return@register
            if (! inBlazeRoom || ! isHoldingBow()) return@register

            event.drawAimCircle(helperRadius.value, circleColor.value)
        }

        register<RenderWorldEvent> {
            if (! inBlazeRoom) return@register

            if (highlightOptimalSpot.value) {
                val spot = getOptimalSpotPos()
                val baseColor = if (chroma.value) {
                    Color.getHSBColor((System.currentTimeMillis() % 4000L) / 4000f, 1f, 1f)
                } else {
                    highlightedBlockColor.value
                }
                val fillAlpha = if (chroma.value) 110 else baseColor.alpha
                event.ctx.renderBlock(
                    spot,
                    outlineColor = baseColor.withAlpha(255),
                    fillColor = baseColor.withAlpha(fillAlpha),
                    outline = true,
                    fill = true,
                    phase = true
                )
                if (drawTracer.value && ! isStandingOnSpot(spot)) {
                    event.ctx.renderTracer(Vec3.atCenterOf(spot), baseColor.withAlpha(255))
                }
            }

            if (! aimHelper.value || mc.gui.screen() != null || ! isHoldingBow()) {
                return@register resetAimState()
            }

            val target = targetBlaze
            if (target == null || ! target.isAlive || target.isRemoved || ! targetHasLineOfSight) {
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
            if (! CircleRenderer.isInsideCircle(screenPos, helperRadius.value)) {
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
        triggerbotHoverTicks = 0
        lastHoveredBlazeId = null
        isTriggerbotClicking = false
        consecutiveWrongClicks = 0
    }
}
