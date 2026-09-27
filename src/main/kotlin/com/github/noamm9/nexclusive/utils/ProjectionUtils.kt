package com.github.noamm9.nexclusive.utils

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.render.RenderHelper.renderVec
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object ProjectionUtils {
    fun worldToScreen(target: Vec3): Vec2? {
        val player = mc.player ?: return null
        val eyePos = player.renderVec.add(0.0, player.eyeHeight.toDouble(), 0.0)

        val dx = target.x - eyePos.x
        val dy = target.y - eyePos.y
        val dz = target.z - eyePos.z

        val yawRad = Math.toRadians(player.yRot.toDouble())
        val pitchRad = Math.toRadians(player.xRot.toDouble())

        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)

        val fx = - sinYaw * cosPitch
        val fy = - sinPitch
        val fz = cosYaw * cosPitch

        val rx = cosYaw
        val ry = 0.0
        val rz = sinYaw

        val ux = - sinPitch * sinYaw
        val uy = cosPitch
        val uz = sinPitch * cosYaw

        val xCam = dx * rx + dy * ry + dz * rz
        val yCam = dx * ux + dy * uy + dz * uz
        val zCam = dx * fx + dy * fy + dz * fz

        if (zCam <= 0.05) return null

        val fov = mc.options.fov().get().toDouble()
        val fovRad = Math.toRadians(fov)
        val halfHeight = Resolution.height / 2.0
        val halfWidth = Resolution.width / 2.0
        val focalLength = halfHeight / tan(fovRad / 2.0)

        val screenX = halfWidth + (xCam / zCam) * focalLength
        val screenY = halfHeight - (yCam / zCam) * focalLength

        return Vec2(screenX.toFloat(), screenY.toFloat())
    }
}
