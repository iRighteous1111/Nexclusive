package com.github.noamm9.nexclusive.ui

import com.github.noamm9.config.ConfigHolder
import com.github.noamm9.ui.clickgui.TooltipManager
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.clickgui.components.settings.Widget
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class PlusMinusSetting(
    name: String,
    val countSupplier: () -> Int,
    val minCount: Int = 1,
    val maxCount: Int = 4,
    val onAdd: () -> Unit,
    val onRemove: () -> Unit
) : ConfigHolder<Unit>(name, Unit)

class PlusMinusWidget(val setting: PlusMinusSetting) : Widget<Unit>(setting) {
    override val height: Int get() = 18

    private val btnSize = 16
    private val spacing = 4

    private val plusAnim = Animation(150L)
    private val minusAnim = Animation(150L)

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val count = setting.countSupplier()
        val canAdd = count < setting.maxCount
        val canRemove = count > setting.minCount

        val plusX = x + 2
        val plusY = y + 1

        val isPlusHovered = canAdd && mouseX >= plusX && mouseX <= plusX + btnSize && mouseY >= plusY && mouseY <= plusY + btnSize
        plusAnim.update(if (isPlusHovered) 1f else 0f)

        // Draw (+) button
        ctx.drawRect(plusX.toFloat(), plusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), if (canAdd) Color(24, 24, 24, 220) else Color(18, 18, 18, 120))
        if (canAdd && plusAnim.value > 0.01f) {
            ctx.drawRect(plusX.toFloat(), plusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), Style.accentColor.withAlpha((45 * plusAnim.value).toInt()))
        }
        val plusBorder = if (canAdd) {
            if (isPlusHovered) Style.accentColor else Color(255, 255, 255, 40)
        } else {
            Color(255, 255, 255, 15)
        }
        ctx.drawBorder(plusX.toFloat(), plusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), plusBorder)

        val plusTextColor = if (canAdd) {
            if (isPlusHovered) Style.accentColor else Color.WHITE
        } else {
            Color(100, 100, 100, 120)
        }
        ctx.drawCenteredString("+", plusX + (btnSize / 2f), plusY + 4f, plusTextColor)

        if (isPlusHovered) {
            TooltipManager.hover("Add Leap", mouseX, mouseY)
        }

        // Draw (-) button immediately next to (+)
        if (canRemove) {
            val minusX = plusX + btnSize + spacing
            val minusY = plusY
            val isMinusHovered = mouseX >= minusX && mouseX <= minusX + btnSize && mouseY >= minusY && mouseY <= minusY + btnSize
            minusAnim.update(if (isMinusHovered) 1f else 0f)

            ctx.drawRect(minusX.toFloat(), minusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), Color(24, 24, 24, 220))
            if (minusAnim.value > 0.01f) {
                ctx.drawRect(minusX.toFloat(), minusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), Style.accentColor.withAlpha((45 * minusAnim.value).toInt()))
            }
            val minusBorder = if (isMinusHovered) Style.accentColor else Color(255, 255, 255, 40)
            ctx.drawBorder(minusX.toFloat(), minusY.toFloat(), btnSize.toFloat(), btnSize.toFloat(), minusBorder)

            val minusTextColor = if (isMinusHovered) Style.accentColor else Color.WHITE
            ctx.drawCenteredString("-", minusX + (btnSize / 2f), minusY + 4f, minusTextColor)

            if (isMinusHovered) {
                TooltipManager.hover("Remove Leap", mouseX, mouseY)
            }
        } else {
            minusAnim.update(0f)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return false
        val count = setting.countSupplier()
        val canAdd = count < setting.maxCount
        val canRemove = count > setting.minCount

        val plusX = x + 2
        val plusY = y + 1

        if (canAdd && mouseX >= plusX && mouseX <= plusX + btnSize && mouseY >= plusY && mouseY <= plusY + btnSize) {
            Style.playClickSound(1f)
            setting.onAdd()
            return true
        }

        if (canRemove) {
            val minusX = plusX + btnSize + spacing
            val minusY = plusY
            if (mouseX >= minusX && mouseX <= minusX + btnSize && mouseY >= minusY && mouseY <= minusY + btnSize) {
                Style.playClickSound(1f)
                setting.onRemove()
                return true
            }
        }

        return false
    }
}
