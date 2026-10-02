package com.github.noamm9.nexclusive.features.impl.nexclusive.leap

import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.features.impl.dungeon.LeapMenu
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawFloatingRect
import com.github.noamm9.utils.render.Render2D.drawPlayerHead
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.Minecraft
import java.awt.Color

object LeapMenuRenderer {
    private val mc get() = Minecraft.getInstance()
    private val boxBg = Color(33, 33, 33)
    private val boxBgHover = Color(67, 67, 67)

    fun render(
        event: ScreenEvent.PreRender,
        targetIndex: Int?,
        targetClass: DungeonClass?,
        changeSize: Boolean,
        targetScale: Float,
        otherScale: Float,
        highlightCorrect: Boolean,
        borderCol: Color
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

        LeapMenu.players.forEachIndexed { i, entry ->
            if (entry == null) return@forEachIndexed

            val isTarget = i == targetIndex
            val scaleFactor = if (changeSize) {
                if (isTarget) targetScale
                else if (targetIndex != null) otherScale
                else 1.0f
            } else 1.0f

            val boxWidth = baseBoxW * scaleFactor
            val boxHeight = baseBoxH * scaleFactor
            val headSize = (baseHeadSize * scaleFactor).toInt()

            val (cx, cy) = slotCenters[i]
            val x = cx - (boxWidth / 2f)
            val y = cy - (boxHeight / 2f)
            val isHovered = i == hoveredIndex

            val baseBg = when {
                entry.player.isDead -> boxBg.withAlpha(210)
                isHovered -> boxBgHover
                else -> boxBg
            }

            event.context.drawFloatingRect(x, y, boxWidth, boxHeight, baseBg.withAlpha(190))

            if (isTarget && highlightCorrect) {
                event.context.drawBorder(x, y, boxWidth, boxHeight, borderCol, thickness = 3)
            }

            val headX = (x + (10f * scaleFactor)).toInt()
            val headY = (y + (boxHeight / 2f) - (headSize / 2f)).toInt()

            event.context.drawPlayerHead(headX, headY, headSize, entry.player.skin)
            event.context.drawBorder(headX, headY, headSize, headSize, entry.player.clazz.color)

            val textX = (x + (10f * scaleFactor) + headSize + (5f * scaleFactor)).toInt()
            val textY = (y + (boxHeight / 2f) - mc.font.lineHeight).toInt()

            event.context.drawString(entry.player.name, textX, textY + 2, entry.player.clazz.color)

            val statusText = when {
                entry.player.isDead -> "§cDEAD"
                isTarget && highlightCorrect -> "§a★ TARGET"
                else -> entry.player.clazz.name
            }
            event.context.drawString(statusText, textX, textY + 12, entry.player.clazz.color)
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
