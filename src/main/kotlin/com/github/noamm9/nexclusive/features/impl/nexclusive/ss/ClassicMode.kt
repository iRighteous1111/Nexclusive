package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.classicRotationSpeed
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.helperRadius
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

object ClassicMode: SSMode {
    override fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean {
        if (inTarget || hasAimed) return false
        val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: return false
        val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
        return dist <= helperRadius.value.toDouble()
    }

    override fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit) {
        val currentYaw = player.yRot
        val currentPitch = player.xRot

        val targetRot = MathUtils.calcYawPitch(targetVec)
        val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
        val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
        val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

        if (angularDist <= 0.25f) {
            PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
            onAimed()
            return
        }

        val ratio = AimUtils.easedRatio(angularDist, classicRotationSpeed.value * 4.5, dt)
        PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)

        if (SimonSaysBridge.isInTargetArea(targetButton, SSAimHelper.aimingOffset.value)) {
            onAimed()
        }
    }
}
