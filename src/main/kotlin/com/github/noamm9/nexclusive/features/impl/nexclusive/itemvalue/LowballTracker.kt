package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.items.ItemUtils.customData
import com.github.noamm9.utils.items.ItemUtils.itemUUID
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.item.ItemStack
import java.awt.Color
import java.util.IdentityHashMap
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs

object LowballTracker {
    private const val EXPENSIVE_ENCHANT_MIN = 10_000_000L
    private const val GEM_CLOSE_COINS = 15_000_000L
    private const val ENCHANT_CLOSE_COINS = 15_000_000L
    private const val BOOK_CLOSE_COINS = 20_000_000L
    private const val MAX_GRADIENT_PCT = 0.50

    private val pageNumRegex = Regex("""\b(\d+)\b""")

    private val trackedBooks = listOf(
        "rarity_upgrades" to "RECOMBOBULATOR_3000",
        "artOfPeaceApplied" to "THE_ART_OF_PEACE",
        "art_of_war_count" to "THE_ART_OF_WAR",
        "wood_singularity_count" to "WOOD_SINGULARITY",
        "jalapeno_count" to "JALAPENO_BOOK",
        "divan_powder_coating" to "DIVAN_POWDER_COATING",
        "mithril_infusion" to "MITHRIL_INFUSION",
        "free_will" to "FREE_WILL"
    )

    class Listing(
        val uid: String,
        val page: Int,
        val binPrice: Long,
        val sbId: String,
        val normalStars: Int,
        val masterStars: Int,
        val ultEnchant: String?,
        val scrollCount: Int,
        val unlockedSlotKeys: Set<String>,
        val gemAppliedValue: Long,
        val expensiveEnchantValue: Long,
        val booksValue: Long
    )

    private var activeTitle = ""
    private var currentPage = 1
    private var lastConfigHash = 0
    private var scanVersion = 0

    private val pageListings = HashMap<Int, LinkedHashMap<String, Listing>>()
    private val currentStacks = IdentityHashMap<ItemStack, Listing>()
    private val dealStacks = IdentityHashMap<ItemStack, Boolean>()
    private val lastSlotStacks = ArrayList<ItemStack>(54)

    private var hoveredStack: ItemStack? = null
    private var prevPageStack: ItemStack? = null
    private var lastHoverTime = 0L
    private var computedHoverStack: ItemStack? = null
    private var computedHoverVersion = - 1
    private val hoverColors = IdentityHashMap<ItemStack, Color>()
    private var highlightPrevPage = false

    fun reset() {
        activeTitle = ""
        currentPage = 1
        lastConfigHash = 0
        pageListings.clear()
        currentStacks.clear()
        dealStacks.clear()
        lastSlotStacks.clear()
        prevPageStack = null
        clearHover()
    }

    fun clearHover() {
        hoveredStack = null
        lastHoverTime = 0L
        computedHoverStack = null
        computedHoverVersion = - 1
        hoverColors.clear()
        highlightPrevPage = false
    }

    fun onHover(stack: ItemStack) {
        if (stack.isEmpty) return
        if (stack === prevPageStack) {
            if (highlightPrevPage) lastHoverTime = System.currentTimeMillis()
            return
        }
        if (currentStacks.containsKey(stack)) {
            hoveredStack = stack
            lastHoverTime = System.currentTimeMillis()
        } else {
            clearHover()
        }
    }

    fun syncScreen(screen: ContainerScreen, configHash: Int) {
        val title = screen.title.unformattedText
        if (! isBrowserTitle(title)) return

        if (title != activeTitle) {
            activeTitle = title
            pageListings.clear()
            lastSlotStacks.clear()
            clearHover()
        }

        val playerInv = mc.player?.inventory
        val slots = screen.menu.slots
        var changed = configHash != lastConfigHash
        var slotCount = 0

        for (slot in slots) {
            if (slot.container == playerInv) continue
            if (! changed && (slotCount >= lastSlotStacks.size || lastSlotStacks[slotCount] !== slot.item)) {
                changed = true
            }
            slotCount++
        }
        if (slotCount != lastSlotStacks.size) changed = true
        if (! changed) return

        var detectedPage = 1
        prevPageStack = null
        for (slot in slots) {
            if (slot.container == playerInv) continue
            val stack = slot.item
            if (! stack.isEmpty && stack.hoverName.unformattedText == "Previous Page") {
                prevPageStack = stack
                val prevNum = stack.lore.firstOrNull()?.removeFormatting()?.let { pageNumRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (prevNum != null) detectedPage = prevNum + 1
                break
            }
        }

        lastConfigHash = configHash
        currentPage = detectedPage
        lastSlotStacks.clear()
        currentStacks.clear()

        val pageMap = LinkedHashMap<String, Listing>()
        for (slot in slots) {
            if (slot.container == playerInv) continue
            val stack = slot.item
            lastSlotStacks.add(stack)
            if (stack.isEmpty) continue

            val binPrice = EstimatedItemValue.getAhBinPrice(stack) ?: continue
            if (binPrice <= 0L) continue
            val listing = buildListing(stack, slot.index, detectedPage, binPrice) ?: continue
            currentStacks[stack] = listing
            pageMap[listing.uid] = listing
        }

        pageListings[detectedPage] = pageMap
        scanVersion++
        recomputeDeals()
        recomputeHover()
    }

    fun getSlotHighlight(stack: ItemStack): Color? {
        if (hoveredStack != null && System.currentTimeMillis() - lastHoverTime > 120L) clearHover()
        if (hoveredStack != null && (hoveredStack !== computedHoverStack || computedHoverVersion != scanVersion)) recomputeHover()

        if (highlightPrevPage && stack === prevPageStack) {
            return EstimatedItemValue.ahGradientPalette.last()
        }
        return hoverColors[stack]
    }

    fun isDeal(stack: ItemStack) = dealStacks[stack] == true

    private fun isBrowserTitle(title: String) = title == "Auctions Browser" || title.startsWith("Auctions: ")

    private fun buildListing(stack: ItemStack, slotIndex: Int, page: Int, binPrice: Long): Listing? {
        val sbId = stack.skyblockId.ifEmpty { return null }
        val data = stack.customData

        val rawStars = data.getIntOr("upgrade_level", data.getIntOr("dungeon_item_level", 0))
        val isMasterCapable = ItemValueBreakdown.getCrimsonBaseId(sbId) == null && (ItemValueData.upgradeCosts[sbId]?.size ?: 5) <= 5
        val normalStars = if (isMasterCapable) rawStars.coerceAtMost(5) else rawStars
        val masterStars = if (isMasterCapable) (rawStars - 5).coerceIn(0, 5) else 0

        var ultEnchant: String? = null
        var expensiveEnchantValue = 0L
        data.getCompound("enchantments").getOrNull()?.let { enchants ->
            for (rawKey in enchants.keySet()) {
                val name = rawKey.lowercase()
                val rawLevel = enchants.getIntOr(rawKey, 0)
                if (rawLevel <= 0) continue

                if (name.startsWith("ultimate_")) {
                    ultEnchant = "$name:$rawLevel"
                    continue
                }
                if (name == "efficiency" && rawLevel > 5 && sbId != "PROMISING_SPADE") continue
                if (ItemValueData.alwaysActiveEnchants[name]?.contains(sbId) == true) continue

                val calcLevel = if (name in ItemValueData.stackingEnchants) 1 else rawLevel
                val price = ItemValueBreakdown.getEnchantPrice(name, calcLevel)
                if (price >= EXPENSIVE_ENCHANT_MIN) expensiveEnchantValue += price
            }
        }

        val gemSummary = ItemValueBreakdown.collectGemstones(sbId, data)
        val scrollCount = data.getList("ability_scroll").getOrNull()?.size ?: 0

        var booksValue = 0L
        for ((tag, itemId) in trackedBooks) {
            if (data.getIntOr(tag, 0) > 0 || data.getByteOr(tag, 0) > 0) booksValue += ItemValueData.getPrice(itemId)
        }
        if (data.contains("stats_book")) booksValue += ItemValueData.getPrice("BOOK_OF_STATS")
        if (data.getIntOr("ethermerge", 0) > 0 || data.getByteOr("ethermerge", 0) > 0) {
            booksValue += ItemValueData.getPrice("ETHERWARP_CONDUIT") + ItemValueData.getPrice("ETHERWARP_MERGER")
        }

        val uid = stack.itemUUID.ifEmpty { "$page:$slotIndex:$sbId:$binPrice" }
        return Listing(
            uid = uid,
            page = page,
            binPrice = binPrice,
            sbId = sbId,
            normalStars = normalStars,
            masterStars = masterStars,
            ultEnchant = ultEnchant,
            scrollCount = scrollCount,
            unlockedSlotKeys = gemSummary?.unlockedSlotKeys ?: emptySet(),
            gemAppliedValue = gemSummary?.appliedCost ?: 0L,
            expensiveEnchantValue = expensiveEnchantValue,
            booksValue = booksValue
        )
    }

    private fun collectAllListings(): List<Listing> {
        val merged = LinkedHashMap<String, Listing>()
        for (page in pageListings.keys.sorted()) {
            if (page == currentPage) continue
            pageListings[page]?.forEach { (uid, listing) -> merged[uid] = listing }
        }
        pageListings[currentPage]?.forEach { (uid, listing) -> merged[uid] = listing }
        return merged.values.toList()
    }

    private fun recomputeDeals() {
        dealStacks.clear()
        if (currentStacks.isEmpty()) return

        val all = collectAllListings()
        if (all.size < 2) return
        val threshold = EstimatedItemValue.lowballGap.value.toDouble()

        for ((stack, listing) in currentStacks) {
            var cheapest = Long.MAX_VALUE
            var second = Long.MAX_VALUE
            var count = 0

            for (other in all) {
                if (! isClose(listing, other)) continue
                count++
                val p = other.binPrice
                if (p < cheapest) {
                    second = cheapest
                    cheapest = p
                } else if (p < second) {
                    second = p
                }
            }

            if (count >= 2 && listing.binPrice == cheapest && second > cheapest) {
                val gapPct = ((second - cheapest).toDouble() / second.toDouble()) * 100.0
                if (gapPct < threshold) continue
                val estValue = (EstimatedItemValue.getOrCalculate(stack)?.totalPrice ?: 0L) * stack.count
                if (estValue > cheapest && ((estValue - cheapest).toDouble() / estValue.toDouble()) * 100.0 >= threshold) {
                    dealStacks[stack] = true
                }
            }
        }
    }

    private fun recomputeHover() {
        hoverColors.clear()
        highlightPrevPage = false
        computedHoverStack = hoveredStack
        computedHoverVersion = scanVersion

        val targetStack = hoveredStack ?: return
        val target = currentStacks[targetStack] ?: return
        val all = collectAllListings()

        var minPrice = Long.MAX_VALUE
        var maxPrice = 0L
        var cheapestPage = Int.MAX_VALUE
        var matchCount = 0

        for (other in all) {
            if (! isClose(target, other)) continue
            matchCount++
            if (other.binPrice < minPrice) {
                minPrice = other.binPrice
                cheapestPage = other.page
            } else if (other.binPrice == minPrice && other.page < cheapestPage) {
                cheapestPage = other.page
            }
            if (other.binPrice > maxPrice) maxPrice = other.binPrice
        }

        if (matchCount < 2) return
        highlightPrevPage = cheapestPage < currentPage

        val palette = EstimatedItemValue.ahGradientPalette
        val maxPct = if (maxPrice > minPrice) {
            ((maxPrice - minPrice).toDouble() / minPrice.toDouble()).coerceAtMost(MAX_GRADIENT_PCT)
        } else 0.0

        for ((stack, listing) in currentStacks) {
            if (! isClose(target, listing)) continue
            val color = if (maxPct <= 0.0) palette.last()
            else {
                val pctAbove = (listing.binPrice - minPrice).toDouble() / minPrice.toDouble()
                val t = (1.0 - pctAbove / maxPct).coerceIn(0.0, 1.0)
                palette[(t * palette.lastIndex).toInt()]
            }
            hoverColors[stack] = color
        }
    }

    private fun isClose(a: Listing, b: Listing): Boolean {
        if (a.sbId != b.sbId) return false
        if (a.masterStars != b.masterStars) return false
        if (a.normalStars != b.normalStars) return false
        if (a.ultEnchant != b.ultEnchant) return false
        if (a.scrollCount != b.scrollCount) return false
        if (a.unlockedSlotKeys != b.unlockedSlotKeys) return false

        if (! isValueClose(a.gemAppliedValue, b.gemAppliedValue, GEM_CLOSE_COINS, 0.25)) return false
        if (! isValueClose(a.expensiveEnchantValue, b.expensiveEnchantValue, ENCHANT_CLOSE_COINS, 0.20)) return false
        if (! isValueClose(a.booksValue, b.booksValue, BOOK_CLOSE_COINS, 0.25)) return false
        return true
    }

    private fun isValueClose(a: Long, b: Long, flatTolerance: Long, pctTolerance: Double): Boolean {
        val diff = abs(a - b)
        if (diff <= flatTolerance) return true
        val max = maxOf(a, b)
        return max > 0L && diff <= max * pctTolerance
    }
}
