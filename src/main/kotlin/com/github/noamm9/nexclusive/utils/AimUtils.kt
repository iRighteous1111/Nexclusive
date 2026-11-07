package com.github.noamm9.nexclusive.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

object AimUtils {
    fun easedRatio(angularDist: Float, speedDegPerSec: Double, dt: Double): Float {
        val baseStep = (speedDegPerSec * dt).toFloat()
        val ease = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
        val step = (baseStep * ease).coerceIn(0.05f, angularDist)
        return (step / angularDist).coerceIn(0f, 1f)
    }

    fun rotateTo(targetPos: Vec3) {
        val rot = MathUtils.calcYawPitch(targetPos)
        PlayerUtils.rotate(rot.yaw, rot.pitch)
    }

    /**
     * Smoothly rotates the player towards [targetPos] and returns `true` once locked onto the target.
     */
    fun rotateTowards(targetPos: Vec3, speed: Double, dt: Double): Boolean {
        val player = mc.player ?: return false
        val targetRot = MathUtils.calcYawPitch(targetPos)
        val currentYaw = player.yRot
        val currentPitch = player.xRot
        val deltaYaw = MathUtils.normalizeYaw(targetRot.yaw - currentYaw)
        val deltaPitch = MathUtils.normalizePitch(targetRot.pitch - currentPitch)
        val angularDist = hypot(deltaYaw.toDouble(), deltaPitch.toDouble()).toFloat()

        if (angularDist <= 0.25f) {
            PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
            return true
        }

        val ratio = easedRatio(angularDist, speed * 4.5, dt)
        val newYaw = currentYaw + deltaYaw * ratio
        val newPitch = currentPitch + deltaPitch * ratio
        PlayerUtils.rotate(newYaw, newPitch)

        val remaining = hypot(
            MathUtils.normalizeYaw(targetRot.yaw - newYaw).toDouble(),
            MathUtils.normalizePitch(targetRot.pitch - newPitch).toDouble()
        ).toFloat()

        if (remaining <= 0.25f) {
            PlayerUtils.rotate(targetRot.yaw, targetRot.pitch)
            return true
        }
        return false
    }
}
