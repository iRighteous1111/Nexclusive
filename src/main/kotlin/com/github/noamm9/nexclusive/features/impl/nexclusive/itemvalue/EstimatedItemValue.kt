package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.location.LocationUtils.inSkyblock
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.awt.Color
import java.util.IdentityHashMap

object EstimatedItemValue: Feature("Displays an estimated value breakdown of SkyBlock items.", "Estimated Item Value") {
    val priceSource by DropdownSetting("Price Source", 0, listOf("Instant Buy / LBIN", "Buy Order / LBIN", "NPC Sell")).section("General")
    val exactPrice by ToggleSetting("Exact Price").withDescription("Shows exact coin amounts instead of compact numbers.")
    val showTooltip by ToggleSetting("Show in Tooltip").withDescription("Also appends the total estimated value to the item's lore tooltip.")
    val enchantmentsCap by SliderSetting("Enchantments Cap", 7, 1, 30, 1).withDescription("Maximum number of individual enchantments shown.")
    val starMaterialCap by SliderSetting("Star Material Cap", 3, 1, 15, 1).withDescription("Maximum number of star upgrade materials shown.")

    val ignoreHelmetSkins by ToggleSetting("Ignore Helmet Skins").section("Ignore Cosmetics")
    val ignoreArmorDyes by ToggleSetting("Ignore Armor Dyes")
    val ignoreRunes by ToggleSetting("Ignore Runes")

    private val cache = IdentityHashMap<ItemStack, ItemValueCalculator.CalculatedValue>()
    private val nullSet = IdentityHashMap<ItemStack, Boolean>()

    private var lastStack: ItemStack? = null
    private var lastValue: ItemValueCalculator.CalculatedValue? = null
    private var lastRenderTime = 0L
    private var lastConfigHash = 0

    private lateinit var valueHud: HudElement
    private val bgColor = Color(15, 15, 15, 210)

    private val exampleLines = listOf(
        "§eEstimated Item Value:",
        "§7Base item: §6Hyperion §7(§6850m§7)",
        "§7Stars: §e5/5 §7(§612.5m§7)",
        "§7Master Stars: §e5/5 §7(§6420m§7)",
        "§7Recombobulated: §a✔ §7(§68.2m§7)",
        "§7Enchantments: §6580m",
        "  §8- §d§lUltimate Wise 5 §7(§63.2m§7)",
        "",
        "§eTotal: §6§l1.87b"
    )

    override fun init() {
        if (enabled) ItemValueData.ensureLoaded()

        valueHud = hudElement("Estimated Item Value", shouldDraw = { false }) { ctx, example ->
            if (example) {
                val w = 180f
                val h = exampleLines.size * 10f + 8f
                ctx.drawRect(0f, 0f, w, h, bgColor)
                ctx.drawBorder(0f, 0f, w, h, Style.accentColor)
                for (i in exampleLines.indices) {
                    ctx.drawString(exampleLines[i], 6f, 4f + i * 10f)
                }
                return@hudElement w to h
            }

            val calc = lastValue ?: return@hudElement 0f to 0f
            ctx.drawRect(0f, 0f, calc.width, calc.height, bgColor)
            ctx.drawBorder(0f, 0f, calc.width, calc.height, Style.accentColor)

            val lines = calc.lines
            for (i in lines.indices) {
                val line = lines[i]
                if (line.isNotEmpty()) ctx.drawString(line, 6f, 4f + i * 10f)
            }

            calc.width to calc.height
        } defaults {
            x = 20f
            y = 100f
        }

        register<ContainerEvent.Render.Tooltip> {
            if (! inSkyblock) return@register
            val stack = event.stack
            if (stack.isEmpty) return@register

            val hash = currentConfigHash()
            if (hash != lastConfigHash) {
                clearCache()
                lastConfigHash = hash
            }

            val value = if (stack === lastStack) {
                lastValue
            } else {
                lastStack = stack
                if (nullSet.containsKey(stack)) {
                    lastValue = null
                    null
                } else {
                    val computed = cache[stack] ?: ItemValueCalculator.calculate(stack)?.also {
                        if (cache.size > 256) cache.clear()
                        cache[stack] = it
                    }
                    if (computed == null) nullSet[stack] = true
                    lastValue = computed
                    computed
                }
            } ?: return@register

            lastRenderTime = System.currentTimeMillis()

            if (showTooltip.value && value.totalPrice > value.basePrice) {
                event.lore.add(Component.literal("§e§lEstimated Value: §6§l${NumbersUtils.formatComma(value.totalPrice)} coins"))
            }
        }

        register<ScreenEvent.PostRender> {
            if (! inSkyblock) return@register
            if (mc.gui.screen() !is AbstractContainerScreen<*>) return@register
            if (lastValue == null || System.currentTimeMillis() - lastRenderTime > 100L) return@register

            Resolution.push(event.context)
            valueHud.renderElement(event.context, false)
            Resolution.pop(event.context)
        }

        register<ContainerEvent.Close> { clearCache() }
        register<WorldChangeEvent> { clearCache() }
    }

    override fun onEnable() {
        super.onEnable()
        ItemValueData.ensureLoaded()
    }

    override fun onDisable() {
        super.onDisable()
        clearCache()
    }

    fun clearCache() {
        cache.clear()
        nullSet.clear()
        lastStack = null
        lastValue = null
        lastRenderTime = 0L
    }

    private fun currentConfigHash(): Int {
        var h = priceSource.value
        h = 31 * h + if (exactPrice.value) 1 else 0
        h = 31 * h + enchantmentsCap.value
        h = 31 * h + starMaterialCap.value
        h = 31 * h + if (ignoreHelmetSkins.value) 1 else 0
        h = 31 * h + if (ignoreArmorDyes.value) 1 else 0
        h = 31 * h + if (ignoreRunes.value) 1 else 0
        return h
    }
}
