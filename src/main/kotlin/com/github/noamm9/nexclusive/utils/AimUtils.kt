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

    class AimLock<K> {
        private val aimedTimes = HashMap<K, Long>()
        private var currentKey: K? = null
        private var lockStartTime = 0L
        private var lastFrameTime = 0L

        fun isOnCooldown(key: K, cooldownMs: Long, now: Long = System.currentTimeMillis()): Boolean {
            return cooldownMs > 0L && now - (aimedTimes[key] ?: 0L) < cooldownMs
        }

        fun update(key: K, targetPos: Vec3, speed: Double, durationMs: Long, cooldownMs: Long) {
            val now = System.currentTimeMillis()
            if (isOnCooldown(key, cooldownMs, now)) return

            if (key != currentKey) {
                currentKey = key
                clearTiming()
            }

            if (lockStartTime > 0L) {
                if (now - lockStartTime < durationMs) return PlayerUtils.rotateTo(targetPos)
                aimedTimes[key] = now
                resetLock()
                return
            }

            val dt = if (lastFrameTime == 0L) 0.016 else ((now - lastFrameTime) / 1000.0).coerceIn(0.001, 0.05)
            lastFrameTime = now

            if (rotateTowards(targetPos, speed, dt)) {
                lockStartTime = now
                if (durationMs <= 0L) {
                    aimedTimes[key] = now
                    resetLock()
                }
            }
        }

        fun clearTiming() {
            lockStartTime = 0L
            lastFrameTime = 0L
        }

        fun resetLock() {
            currentKey = null
            clearTiming()
        }

        fun resetAll() {
            aimedTimes.clear()
            resetLock()
        }
    }
}
