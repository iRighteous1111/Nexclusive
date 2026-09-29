package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.helperRadius
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.lockDelay
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.lockThreshold
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.thresholdRotationSpeed
import com.github.noamm9.nexclusive.utils.AimUtils
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

object T    hresholdMode: SSMode {
    @Volatile var isLocking = false
        private set

    private var buttonInCircleTime = 0L

    override fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean {
        if (inTarget || hasAimed) {
            reset()
            return false
        }

        val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: run {
            reset()
            return false
        }

        val dist = hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))
        if (dist > helperRadius.value.toDouble()) {
            reset()
            return false
        }

        val now = System.currentTimeMillis()
        if (buttonInCircleTime == 0L) buttonInCircleTime = now

        val mouseDelta = hypot(dx, dy)
        val isTimedLock = now - buttonInCircleTime >= lockDelay.value.toLong()
        isLocking = isTimedLock || mouseDelta <= lockThreshold.value

        return isLocking
    }

    override fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit) {
        val now = System.currentTimeMillis()
        if (buttonInCircleTime == 0L) buttonInCircleTime = now
        if (now - buttonInCircleTime >= lockDelay.value.toLong()) isLocking = true
        if (! isLocking) return

        val currentYaw = player.yRot
        val currentPitch = player.xRot

        val targetRot = MathUtils.calcYawPitch(targetVec)
        val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
        val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
        val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

        if (angularDist <= 0.25f) {
            PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
            reset()
            onAimed()
            return
        }

        val ratio = AimUtils.easedRatio(angularDist, thresholdRotationSpeed.value * 4.5, dt)
        PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)

        if (SimonSaysBridge.isInTargetArea(targetButton, SSAimHelper.aimingOffset.value)) {
            reset()
            onAimed()
        }
    }

    override fun reset() {
        isLocking = false
        buttonInCircleTime = 0L
    }
}
