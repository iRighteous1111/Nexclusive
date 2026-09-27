package com.github.noamm9.nexclusive.utils

object AimUtils {
    // Shared acceleration curve for LeverHelper and SSAimHelper: ramps down near the target so aim doesn't overshoot.
    fun easedRatio(angularDist: Float, speedDegPerSec: Double, dt: Double): Float {
        val baseStep = (speedDegPerSec * dt).toFloat()
        val ease = if (angularDist < 1.5f) (angularDist / 1.5f).coerceIn(0.25f, 1.0f) else 1.0f
        val step = (baseStep * ease).coerceIn(0.05f, angularDist)
        return (step / angularDist).coerceIn(0f, 1f)
    }
}
