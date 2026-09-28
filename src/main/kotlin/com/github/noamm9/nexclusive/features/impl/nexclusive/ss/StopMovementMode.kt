package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.stopDelay
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.stopDuration
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object StopMovementMode: SSMode {
    private var hoverStartTime = 0L

    override fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean {
        if (! inTarget) {
            hoverStartTime = 0L
            return false
        }

        val now = System.currentTimeMillis()
        if (hoverStartTime == 0L) hoverStartTime = now

        val elapsed = now - hoverStartTime
        val delay = stopDelay.value.toLong()
        return elapsed in delay .. (delay + stopDuration.value.toLong())
    }

    override fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit) {
        if (inTarget) {
            if (hoverStartTime == 0L) hoverStartTime = System.currentTimeMillis()
        } else {
            hoverStartTime = 0L
        }
    }

    override fun reset() {
        hoverStartTime = 0L
    }
}
