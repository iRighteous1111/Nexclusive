package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.features.Shortcuts
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

interface SSMode: Shortcuts {
    fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean
    fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit)
    fun reset() {}
}
