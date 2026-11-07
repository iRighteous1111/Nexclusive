package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.classicRotationSpeed
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.helperRadius
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object ClassicMode: SSMode {
    override fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean {
        if (inTarget || hasAimed) return false
        val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton))
        return CircleRenderer.isInsideCircle(screenPos, helperRadius.value)
    }

    override fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit) {
        if (AimUtils.rotateTowards(targetVec, classicRotationSpeed.value, dt) ||
            SimonSaysBridge.isInTargetArea(targetButton, SSAimHelper.aimingOffset.value)
        ) {
            onAimed()
        }
    }
}
