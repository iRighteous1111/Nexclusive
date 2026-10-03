package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.features.impl.dungeon.LeapMenu
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ColorUtils.lerp
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawFloatingRect
import com.github.noamm9.utils.render.Render2D.drawPlayerHead
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.Minecraft
import java.awt.Color

object LeapMenuRenderer {
    private val mc get() = Minecraft.getInstance()
    private val boxBg = Color(33, 33, 33)
    private val boxBgHover = Color(67, 67, 67)

    fun render(
        event: ScreenEvent.PreRender,
        targetIndices: Set<Int>,
        useClassColor: Boolean,
        changeSize: Boolean,
        targetScale: Float,
        otherScale: Float,
        highlightCorrect: Boolean,
        borderCol: Color,
        darkenOthers: Boolean,
        darkenFactor: Float
    ) {
        Resolution.push(event.context)

        val rawScale = LeapMenuFeatures.menuScale
        val userScale = (rawScale / 100f) * 2.0f
        val screenWidth = Resolution.width / userScale
        val screenHeight = Resolution.height / userScale

        val pose = event.context.pose()
        pose.pushMatrix()
        pose.scale(userScale)

        val baseBoxW = 128f * 1.3f
        val baseBoxH = 80f * 0.8f
        val padding = 40f
        val baseHeadSize = 50f

        val gridW = (baseBoxW * 2) + padding
        val gridH = (baseBoxH * 2) + padding
        val startX = (screenWidth - gridW) / 2f
        val startY = (screenHeight - gridH) / 2f

        val slotCenters = listOf(
            (startX + baseBoxW / 2f) to (startY + baseBoxH / 2f),
            (startX + baseBoxW + padding + baseBoxW / 2f) to (startY + baseBoxH / 2f),
            (startX + baseBoxW / 2f) to (startY + baseBoxH + padding + baseBoxH / 2f),
            (startX + baseBoxW + padding + baseBoxW / 2f) to (startY + baseBoxH + padding + baseBoxH / 2f)
        )

        val hoveredIndex = getHoveredIndex()
        val hasTargets = targetIndices.isNotEmpty()

        LeapMenu.players.forEachIndexed { i, entry ->
            if (entry == null) return@forEachIndexed

            val isTarget = i in targetIndices
            val scaleFactor = if (changeSize) {
                if (isTarget) targetScale
                else if (hasTargets) otherScale
                else 1.0f
            } else 1.0f

            val boxWidth = baseBoxW * scaleFactor
            val boxHeight = baseBoxH * scaleFactor
            val headSize = (baseHeadSize * scaleFactor).toInt()

            val (cx, cy) = slotCenters[i]
            val x = cx - (boxWidth / 2f)
            val y = cy - (boxHeight / 2f)
            val isHovered = i == hoveredIndex

            val playerHighlightColor = if (useClassColor) entry.player.clazz.color else borderCol

            // Determine card background: fill cell with highlight color if target
            val cardBg = if (isTarget && highlightCorrect) {
                val alpha = if (playerHighlightColor.alpha in 1..254) playerHighlightColor.alpha else 180
                val finalAlpha = if (isHovered) (alpha + 35).coerceAtMost(255) else alpha
                playerHighlightColor.withAlpha(finalAlpha)
            } else {
                val base = when {
                    entry.player.isDead -> boxBg.withAlpha(210)
                    isHovered -> boxBgHover
                    else -> boxBg
                }
                if (hasTargets && darkenOthers && ! isTarget) {
                    base.lerp(Color.BLACK, darkenFactor.coerceIn(0f, 1f)).withAlpha(190)
                } else {
                    base.withAlpha(190)
                }
            }

            // 1. Draw card background (filled)
            event.context.drawFloatingRect(x, y, boxWidth, boxHeight, cardBg)

            // If target, draw a crisp highlight border around the filled card
            if (isTarget && highlightCorrect) {
                event.context.drawBorder(x, y, boxWidth, boxHeight, playerHighlightColor, thickness = 2)
            }

            // 2. Draw player head
            val headX = (x + (10f * scaleFactor)).toInt()
            val headY = (y + (boxHeight / 2f) - (headSize / 2f)).toInt()

            event.context.drawPlayerHead(headX, headY, headSize, entry.player.skin)
            event.context.drawBorder(headX, headY, headSize, headSize, entry.player.clazz.color)

            // 3. Draw text info
            val textX = (x + (10f * scaleFactor) + headSize + (5f * scaleFactor)).toInt()
            val textY = (y + (boxHeight / 2f) - mc.font.lineHeight).toInt()

            event.context.drawString(entry.player.name, textX, textY + 2, entry.player.clazz.color)

            val statusText = when {
                entry.player.isDead -> "§cDEAD"
                isTarget && highlightCorrect -> "§a★ TARGET"
                else -> entry.player.clazz.name
            }
            event.context.drawString(statusText, textX, textY + 12, entry.player.clazz.color)

            // 4. Darken non-target cell overlay
            if (hasTargets && darkenOthers && ! isTarget) {
                val overlayAlpha = (darkenFactor.coerceIn(0f, 1f) * 255).toInt()
                event.context.drawRect(x, y, boxWidth, boxHeight, Color.BLACK.withAlpha(overlayAlpha))
            }
        }

        pose.popMatrix()
        Resolution.pop(event.context)
    }

    fun getHoveredIndex(): Int? {
        val window = mc.window
        val cx = window.screenWidth / 2
        val cy = window.screenHeight / 2
        val mx = mc.mouseHandler.xpos()
        val my = mc.mouseHandler.ypos()

        return when {
            mx < cx && my < cy -> 0
            mx > cx && my < cy -> 1
            mx < cx && my > cy -> 2
            mx > cx && my > cy -> 3
            else -> null
        }
    }
}
