package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.commands.CommandBuilder
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.init.types.ICommandProvider
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.ColorUtils.lerp
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.Render2D.highlight
import com.github.noamm9.utils.render.RenderHelper.width
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.awt.Color
import java.util.IdentityHashMap

object EstimatedItemValue: Feature("Displays an estimated value breakdown of SkyBlock items.", "Estimated Item Value"), ICommandProvider {
    val priceSource by DropdownSetting("Price Source", 0, listOf("Instant Buy / LBIN", "Buy Order / LBIN", "NPC Sell")).section("General")
    val onlyInAh by ToggleSetting("Only in AH").withDescription("Only displays the estimated value when inside Auction House menus.")
    val ahHighlight by ToggleSetting("AH Highlight").withDescription("Highlights AH BIN items priced below their estimated value in green.")
    val highlightGradient by ToggleSetting("Highlight Gradient").showIf { ahHighlight.value && ! lowballGradient.value }.withDescription("Scales green brightness based on how far below estimated value the listing is.")
    val lowballGradient by ToggleSetting("Lowball Gradient").showIf { ahHighlight.value }.withDescription("Compares identical/close AH listings by price instead of normal AH Highlight.")
    val lowballGap by SliderSetting("Lowball Gap", 20, 5, 80, 1, "%").showIf { ahHighlight.value && lowballGradient.value }.withDescription("Minimum % discount below both the 2nd cheapest identical listing and estimated value to show a $ badge.")
    val disableInDungeons by ToggleSetting("Disable in Dungeons").withDescription("Hides the estimated value while inside Dungeons.")
    val disableInKuudra by ToggleSetting("Disable in Kuudra").withDescription("Hides the estimated value while inside Kuudra.")
    val exactPrice by ToggleSetting("Exact Price").withDescription("Shows exact coin amounts instead of compact numbers.")
    val showTooltip by ToggleSetting("Show in Tooltip").withDescription("Appends the total estimated value to the item's lore tooltip.")
    val enchantmentsCap by SliderSetting("Enchantments Cap", 7, 1, 30, 1).withDescription("Maximum number of individual enchantments shown.")
    val starMaterialCap by SliderSetting("Star Material Cap", 3, 1, 15, 1).withDescription("Maximum number of star upgrade materials shown.")

    val ignoreHelmetSkins by ToggleSetting("Ignore Helmet Skins").section("Ignore Cosmetics")
    val ignoreArmorDyes by ToggleSetting("Ignore Armor Dyes")
    val ignoreRunes by ToggleSetting("Ignore Runes")

    private val cache = IdentityHashMap<ItemStack, ItemValueCalculator.CalculatedValue>()
    private val nullSet = IdentityHashMap<ItemStack, Boolean>()
    private val ahPriceCache = IdentityHashMap<ItemStack, Long>()

    private var lastStack: ItemStack? = null
    private var lastValue: ItemValueCalculator.CalculatedValue? = null
    private var lastRenderTime = 0L
    private var lastConfigHash = 0

    private lateinit var valueHud: HudElement
    private val bgColor = Color(15, 15, 15, 210)
    private val ahHighlightColor = Color.GREEN.withAlpha(100)
    private const val DEAL_BADGE = "§a§l$"
    internal val ahGradientPalette = Array(32) { i ->
        val t = i / 31.0
        Color(0, 75, 0).lerp(Color(110, 255, 110), t).withAlpha((95 + t * 55).toInt())
    }

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
                for (i in exampleLines.indices) ctx.drawString(exampleLines[i], 6f, 4f + i * 10f)
                return@hudElement w to h
            }

            val calc = lastValue ?: return@hudElement 0f to 0f
            ctx.drawRect(0f, 0f, calc.width, calc.height, bgColor)
            ctx.drawBorder(0f, 0f, calc.width, calc.height, Style.accentColor)

            for (i in calc.lines.indices) {
                val line = calc.lines[i]
                if (line.isNotEmpty()) ctx.drawString(line, 6f, 4f + i * 10f)
            }

            calc.width to calc.height
        } defaults {
            x = 20f
            y = 100f
        }

        register<ContainerEvent.Render.Tooltip> {
            if (ahHighlight.value && lowballGradient.value && LocationUtils.inSkyblock && isAuctionScreen(event.screen)) {
                LowballTracker.onHover(event.stack)
            }
            if (! shouldDisplay(event.screen)) return@register
            val stack = event.stack
            if (stack.isEmpty) return@register

            syncCacheVersion()
            val value = if (stack === lastStack) lastValue
            else {
                lastStack = stack
                getOrCalculate(stack).also { lastValue = it }
            } ?: return@register

            lastRenderTime = System.currentTimeMillis()
            if (showTooltip.value && value.totalPrice > value.basePrice) {
                event.lore.add(Component.literal("§e§lEstimated Value: §6§l${NumbersUtils.formatComma(value.totalPrice)} coins"))
            }
        }

        register<ContainerEvent.Render.Slot.Pre> {
            if (! ahHighlight.value) return@register
            if (! LocationUtils.inSkyblock) return@register
            val screen = event.screen as? ContainerScreen ?: return@register
            if (! isAuctionScreen(screen)) return@register
            if (event.slot.container == mc.player?.inventory) return@register

            syncCacheVersion()
            if (lowballGradient.value) {
                if (event.slot.index == 0) LowballTracker.syncScreen(screen, lastConfigHash)
                val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
                LowballTracker.getSlotHighlight(stack)?.let { event.slot.highlight(event.context, it) }
                return@register
            }

            val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
            if (ahPriceCache.size > 256) ahPriceCache.clear()
            val binPrice = ahPriceCache.getOrPut(stack) { getAhBinPrice(stack) ?: - 1L }
            if (binPrice <= 0L) return@register

            val value = getOrCalculate(stack) ?: return@register
            val estValue = value.totalPrice * stack.count
            if (estValue > binPrice) {
                event.slot.highlight(event.context, getAhHighlightColor(estValue, binPrice))
            }
        }

        register<ContainerEvent.Render.Slot.Post> {
            if (! ahHighlight.value || ! lowballGradient.value) return@register
            if (! LocationUtils.inSkyblock) return@register
            if (! isAuctionScreen(event.screen)) return@register
            if (event.slot.container == mc.player?.inventory) return@register
            val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
            if (! LowballTracker.isDeal(stack)) return@register

            event.context.pose().translate(event.slot.x.toFloat(), event.slot.y.toFloat())
            event.context.drawString(DEAL_BADGE, 16f - DEAL_BADGE.width() * 0.65f, 0f, scale = 0.65f)
            event.context.pose().translate(- event.slot.x.toFloat(), - event.slot.y.toFloat())
        }

        register<ScreenEvent.PostRender> {
            val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return@register
            if (! shouldDisplay(screen)) return@register
            if (lastValue == null || System.currentTimeMillis() - lastRenderTime > 100L) return@register

            Resolution.push(event.context)
            valueHud.renderElement(event.context, false)
            Resolution.pop(event.context)
        }

        register<ContainerEvent.Close> {
            clearCache()
            LowballTracker.clearHover()
        }
        register<WorldChangeEvent> {
            clearCache()
            LowballTracker.reset()
        }
    }

    override fun CommandBuilder.command() {
        setName("held", "holding")
        description("Sends your held item and its estimated value in chat")
        runs { sendHeldItemValue() }
    }

    override fun onEnable() {
        super.onEnable()
        ItemValueData.ensureLoaded()
    }

    override fun onDisable() {
        super.onDisable()
        clearCache()
        LowballTracker.reset()
    }

    fun clearCache() {
        cache.clear()
        nullSet.clear()
        ahPriceCache.clear()
        lastStack = null
        lastValue = null
        lastRenderTime = 0L
    }

    private fun syncCacheVersion() {
        val hash = currentConfigHash()
        if (hash != lastConfigHash) {
            clearCache()
            lastConfigHash = hash
        }
    }

    internal fun getOrCalculate(stack: ItemStack): ItemValueCalculator.CalculatedValue? {
        if (nullSet.containsKey(stack)) return null
        return cache[stack] ?: ItemValueCalculator.calculate(stack).also { computed ->
            if (computed == null) nullSet[stack] = true
            else {
                if (cache.size > 256) cache.clear()
                cache[stack] = computed
            }
        }
    }

    private fun getAhHighlightColor(estValue: Long, binPrice: Long): Color {
        if (! highlightGradient.value) return ahHighlightColor
        val t = ((estValue - binPrice).toDouble() / estValue.toDouble() / 0.30).coerceIn(0.0, 1.0)
        return ahGradientPalette[(t * ahGradientPalette.lastIndex).toInt()]
    }

    internal fun getAhBinPrice(stack: ItemStack): Long? {
        val lore = stack.lore
        for (i in lore.lastIndex downTo 0) {
            val clean = lore[i].removeFormatting()
            if (! clean.startsWith("Buy it now: ")) continue
            return clean.substringAfter("Buy it now: ").substringBefore(" ").replace(",", "").toLongOrNull()
        }
        return null
    }

    private fun sendHeldItemValue() {
        val stack = mc.player?.mainHandItem?.takeUnless { it.isEmpty }
            ?: return ChatUtils.modMessage("&cYou are not holding an item.")
        if (stack.skyblockId.isEmpty()) return ChatUtils.modMessage("&cYou are not holding a SkyBlock item.")

        val snapshot = stack.copy()
        ThreadUtils.async {
            ItemValueData.preloadSync(snapshot)
            mc.execute {
                val calc = ItemValueCalculator.calculate(snapshot, includeAllCosmetics = true)
                if (calc == null || calc.totalPrice <= 0L) {
                    ChatUtils.modMessage("&cCould not calculate value for this item.")
                    return@execute
                }
                val name = ItemValueCalculator.formatHeldName(snapshot)
                ChatUtils.sendMessage("Held item : $name, Value : ${ItemValueCalculator.formatPrice(calc.totalPrice)}")
            }
        }
    }

    private fun shouldDisplay(screen: Screen?): Boolean {
        if (! LocationUtils.inSkyblock) return false
        if (disableInDungeons.value && LocationUtils.world == WorldType.Catacombs) return false
        if (disableInKuudra.value && LocationUtils.world == WorldType.Kuudra) return false
        if (onlyInAh.value && ! isAuctionScreen(screen)) return false
        return true
    }

    private fun isAuctionScreen(screen: Screen?): Boolean {
        if (screen !is ContainerScreen) return false
        val title = screen.title.unformattedText
        return title.contains("Auction", ignoreCase = true) || title == "Your Bids"
    }

    private fun currentConfigHash(): Int {
        var h = priceSource.value
        h = 31 * h + if (exactPrice.value) 1 else 0
        h = 31 * h + enchantmentsCap.value
        h = 31 * h + starMaterialCap.value
        h = 31 * h + if (ignoreHelmetSkins.value) 1 else 0
        h = 31 * h + if (ignoreArmorDyes.value) 1 else 0
        h = 31 * h + if (ignoreRunes.value) 1 else 0
        h = 31 * h + if (lowballGradient.value) 1 else 0
        h = 31 * h + lowballGap.value
        h = 31 * h + ItemValueData.dataVersion.get()
        return h
    }
}
