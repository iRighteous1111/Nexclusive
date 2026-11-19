package com.github.noamm9.nexclusive.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

object ArrowTrajectory {
    private const val ARROW_SPEED = 3.0
    private const val DRAG = 0.99
    private const val GRAVITY = 0.05
    private const val MAX_TICKS = 30
    private val TERM_YAW_OFFSETS = floatArrayOf(0f, - 5f, 5f)
    private val BOW_YAW_OFFSETS = floatArrayOf(0f)

    fun isTerminator(): Boolean = mc.player?.mainHandItem?.skyblockId == "TERMINATOR"

    fun yawOffsets(): FloatArray = if (isTerminator()) TERM_YAW_OFFSETS else BOW_YAW_OFFSETS

    /**
     * Simulates bow/Terminator arrow trajectories per tick until block collision or [MAX_TICKS].
     */
    fun forEachSegment(
        yaw: Float = mc.player?.yRot ?: 0f,
        pitch: Float = mc.player?.xRot ?: 0f,
        onSegment: (start: Vec3, end: Vec3) -> Boolean
    ) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val yawRad = Math.toRadians(yaw.toDouble())
        val startPos = player.position().add(- cos(yawRad) * 0.16, player.eyeHeight - 0.1, - sin(yawRad) * 0.16)

        for (offset in yawOffsets()) {
            var pos = startPos
            var motion = MathUtils.getLookVec(yaw + offset, pitch).scale(ARROW_SPEED)

            for (tick in 0 until MAX_TICKS) {
                val nextPos = pos.add(motion)
                val blockHit = level.clip(ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                val end = if (blockHit.type == HitResult.Type.BLOCK) blockHit.location else nextPos
                if (! onSegment(pos, end)) break
                if (blockHit.type == HitResult.Type.BLOCK) break

                pos = nextPos
                motion = Vec3(motion.x * DRAG, motion.y * DRAG - GRAVITY, motion.z * DRAG)
            }
        }
    }
}
