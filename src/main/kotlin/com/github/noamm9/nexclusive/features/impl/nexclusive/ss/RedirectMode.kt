package com.github.noamm9.nexclusive.features.impl.nexclusive.ss

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.detailedSettings
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.helperRadius
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.holdDelay
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.maxSpeed
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.minimumSpeed
import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper.speedMultiplier
import com.github.noamm9.nexclusive.utils.ProjectionUtils
import com.github.noamm9.nexclusive.utils.SimonSaysBridge
import com.github.noamm9.nexclusive.utils.render.CircleRenderer
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

object RedirectMode: SSMode {
    private var pendingDelta = 0.0
    private var holdStartTime = 0L

    override fun shouldSuppressMouseInput(dx: Double, dy: Double, targetButton: BlockPos, inTarget: Boolean, hasAimed: Boolean): Boolean {
        if (hasAimed) return false

        if (inTarget) {
            if (detailedSettings.value && holdDelay.value > 0) {
                val now = System.currentTimeMillis()
                if (holdStartTime == 0L) holdStartTime = now
                if (now - holdStartTime < holdDelay.value.toLong()) return true
            }
            return false
        }   

        val screenPos = ProjectionUtils.worldToScreen(SimonSaysBridge.getTargetPoint(targetButton)) ?: return false
        if (! CircleRenderer.isInsideCircle(screenPos, helperRadius.value)) return false

        pendingDelta += hypot(dx, dy)
        return true
    }

    override fun onRender(dt: Double, targetButton: BlockPos, targetVec: Vec3, inTarget: Boolean, hasAimed: Boolean, onAimed: () -> Unit) {
        val now = System.currentTimeMillis()

        if (inTarget) {
            if (detailedSettings.value && holdDelay.value > 0) {
                if (holdStartTime == 0L) holdStartTime = now
                if (now - holdStartTime < holdDelay.value.toLong()) {
                    val targetRot = MathUtils.calcYawPitch(targetVec)
                    PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
                    return
                }
            }
            holdStartTime = 0L
            onAimed()
            return
        }

        val delta = pendingDelta
        pendingDelta = 0.0
        if (delta <= 0.001) return

        val currentYaw = player.yRot
        val currentPitch = player.xRot

        val targetRot = MathUtils.calcYawPitch(targetVec)
        val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
        val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
        val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

        val f = mc.options.sensitivity().get().toFloat() * 0.6f + 0.2f
        val gcd = f * f * f * 1.2f
        val inputDegrees = delta.toFloat() * gcd
        var step = inputDegrees * speedMultiplier.value.toFloat()

        if (detailedSettings.value) {
            if (minimumSpeed.value > 0.0) {
                val minStep = (minimumSpeed.value.toFloat() * dt.toFloat())
                if (step < minStep) step = minStep
            }
            val maxStep = (maxSpeed.value.toFloat() * dt.toFloat())
            if (step > maxStep) step = maxStep
        }

        val ratio = (step / angularDist).coerceIn(0f, 1f)
        PlayerUtils.rotate(currentYaw + deltaYaw * ratio, currentPitch + deltaPitch * ratio)

        if (SimonSaysBridge.isInTargetArea(targetButton, SSAimHelper.aimingOffset.value) || ratio >= 1f) {
            if (detailedSettings.value && holdDelay.value > 0) {
                if (holdStartTime == 0L) holdStartTime = now
            } else {
                onAimed()
            }
        }
    }

    override fun reset() {
        pendingDelta = 0.0
        holdStartTime = 0L
    }
}
