package com.github.noamm9.nexclusive.utils.render

import com.github.noamm9.event.impl.RenderOverlayEvent
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.render.Render2D.drawAnnularSegment
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec2
import java.awt.Color
import kotlin.math.hypot

object CircleRenderer {
    val centerX get() = Resolution.width / 2f
    val centerY get() = Resolution.height / 2f

    fun GuiGraphicsExtractor.drawScreenCircle(
        radius: Number,
        color: Color,
        thickness: Float = 1.2f,
        cx: Float = centerX,
        cy: Float = centerY
    ) {
        val r = radius.toFloat()
        Resolution.push(this)
        drawAnnularSegment(
            centerX = cx,
            centerY = cy,
            innerRadius = r - thickness,
            outerRadius = r,
            startAngle = 0.0,
            endAngle = Math.PI * 2.0,
            color = color
        )
        Resolution.pop(this)
    }

    fun RenderOverlayEvent.drawAimCircle(
        radius: Number,
        color: Color,
        thickness: Float = 1.2f,
        cx: Float = centerX,
        cy: Float = centerY
    ) = context.drawScreenCircle(radius, color, thickness, cx, cy)

    fun distanceToCenter(screenPos: Vec2): Double =
        hypot(screenPos.x.toDouble() - (Resolution.width / 2.0), screenPos.y.toDouble() - (Resolution.height / 2.0))

    fun isInsideCircle(screenPos: Vec2?, radius: Number): Boolean =
        screenPos != null && distanceToCenter(screenPos) <= radius.toDouble()
}
