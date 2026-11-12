package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.items.ItemRarity
import com.github.noamm9.utils.items.ItemUtils
import com.github.noamm9.utils.items.ItemUtils.customData
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.RenderHelper.width
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.item.ItemStack
import kotlin.jvm.optionals.getOrNull

object ItemValueCalculator {
    class CalculatedValue(
        val totalPrice: Long,
        val basePrice: Long,
        val lines: List<String>,
        val width: Float,
        val height: Float
    )

    private val singleModifiers = listOf(
        "rarity_upgrades" to ("§7Recombobulated:" to "RECOMBOBULATOR_3000"),
        "art_of_war_count" to ("§7The Art of War:" to "THE_ART_OF_WAR"),
        "artOfPeaceApplied" to ("§7The Art Of Peace:" to "THE_ART_OF_PEACE"),
        "wood_singularity_count" to ("§7Wood Singularity:" to "WOOD_SINGULARITY"),
        "jalapeno_count" to ("§7Jalapeno Book:" to "JALAPENO_BOOK"),
        "divan_powder_coating" to ("§7Divan Powder Coating:" to "DIVAN_POWDER_COATING"),
        "mithril_infusion" to ("§7Mithril Infusion:" to "MITHRIL_INFUSION"),
        "free_will" to ("§7Free Will:" to "FREE_WILL")
    )

    private val countedModifiers = listOf(
        Triple("farming_for_dummies_count", 5, "§7Farming for Dummies" to "FARMING_FOR_DUMMIES"),
        Triple("polarvoid", 5, "§7Polarvoid Books" to "POLARVOID_BOOK"),
        Triple("wet_book_count", 5, "§7Wet Books" to "WET_BOOK"),
        Triple("bookworm_books", 5, "§7Bookworm's Favorite Books" to "BOOKWORM_BOOK"),
        Triple("tuned_transmission", 4, "§7Transmission Tuners" to "TRANSMISSION_TUNER"),
        Triple("mana_disintegrator_count", 10, "§7Mana Disintegrators" to "MANA_DISINTEGRATOR"),
        Triple("levelable_overclocks", 10, "§7Overclocker 3000" to "OVERCLOCKER_3000"),
        Triple("sack_pss", 3, "§7Pocket Sack-in-a-Sack" to "POCKET_SACK_IN_A_SACK")
    )

    private val drillPartTags = listOf("drill_part_upgrade_module", "drill_part_engine", "drill_part_fuel_tank")
    private val rodPartTags = listOf("line", "hook", "sinker")

    fun formatPrice(value: Long) = if (EstimatedItemValue.exactPrice.value) NumbersUtils.formatComma(value) else NumbersUtils.format(value)

    fun calculate(stack: ItemStack): CalculatedValue? {
        if (stack.isEmpty) return null
        val sbId = stack.skyblockId.ifEmpty { return null }
        val data = stack.customData

        val list = ArrayList<String>(16)
        list.add("§eEstimated Item Value:")

        val basePrice = addBaseItem(stack, sbId, list)
        var totalPrice = basePrice

        totalPrice += addCoinsConsumed(stack, sbId, data, list)
        totalPrice += ItemValueBreakdown.addStars(sbId, data, list)
        totalPrice += ItemValueBreakdown.addMasterStars(sbId, data, list)
        totalPrice += addReforgeStone(stack, data, list)

        // Single & boolean modifiers
        for ((tag, info) in singleModifiers) {
            if (data.getIntOr(tag, 0) > 0 || data.getByteOr(tag, 0) > 0) {
                totalPrice += addSingle(info.first, info.second, list)
            }
        }
        if (data.contains("stats_book")) totalPrice += addSingle("§7Book of Stats:", "BOOK_OF_STATS", list)
        if (data.getIntOr("ethermerge", 0) > 0 || data.getByteOr("ethermerge", 0) > 0) totalPrice += addEtherwarp(list)

        data.getString("power_ability_scroll").getOrNull()?.takeIf(String::isNotEmpty)?.let {
            totalPrice += addNamedSingle("§7Power Scroll:", it, list)
        }
        data.getString("talisman_enrichment").getOrNull()?.takeIf(String::isNotEmpty)?.let {
            totalPrice += addNamedSingle("§7Enrichment:", "TALISMAN_ENRICHMENT_${it.uppercase()}", list)
        }

        // Cosmetics
        if (! EstimatedItemValue.ignoreHelmetSkins.value) totalPrice += addSkin(sbId, data, list)
        if (! EstimatedItemValue.ignoreArmorDyes.value) {
            data.getString("dye_item").getOrNull()?.takeIf(String::isNotEmpty)?.let {
                totalPrice += addNamedSingle("§7Dye:", it, list)
            }
        }
        if (! EstimatedItemValue.ignoreRunes.value && ! sbId.startsWith("RUNE") && sbId != "UNIQUE_RUNE") {
            totalPrice += addRune(data, list)
        }

        // Counted modifiers
        totalPrice += addHotPotatoBooks(data, list)
        for ((tag, max, info) in countedModifiers) {
            totalPrice += addCounted(data.getIntOr(tag, 0), max, info.first, info.second, list, "§e")
        }
        totalPrice += addSilex(sbId, data, list)

        // Dynamic lists & parts
        data.getList("ability_scroll").getOrNull()?.forEach { el ->
            (el as? StringTag)?.value()?.let { totalPrice += addNamedSingle("§7Ability Scroll:", it, list) }
        }
        data.getList("boosters").getOrNull()?.forEach { el ->
            (el as? StringTag)?.value()?.let { totalPrice += addNamedSingle("§7Booster:", "${it.uppercase()}_BOOSTER", list) }
        }
        for (tag in drillPartTags) {
            data.getString(tag).getOrNull()?.takeIf(String::isNotEmpty)?.let {
                totalPrice += addNamedSingle("§7Drill Part:", it.uppercase(), list)
            }
        }
        for (tag in rodPartTags) {
            data.getCompound(tag).getOrNull()?.getString("part")?.getOrNull()?.takeIf(String::isNotEmpty)?.let {
                totalPrice += addNamedSingle("§7Rod Part:", it.uppercase(), list)
            }
        }

        totalPrice += ItemValueBreakdown.addGemstones(sbId, data, list)
        totalPrice += ItemValueBreakdown.addEnchantments(sbId, data, list)

        if (list.size <= 2 && (totalPrice <= 0L || sbId.startsWith("ENCHANTMENT_"))) return null

        list.add("")
        list.add("§eTotal: §6§l${formatPrice(totalPrice)}")

        val maxWidth = list.maxOfOrNull { it.width().toFloat() } ?: 0f
        return CalculatedValue(totalPrice, basePrice, list, maxWidth + 12f, list.size * 10f + 8f)
    }

    private fun addBaseItem(stack: ItemStack, sbId: String, list: MutableList<String>): Long {
        if (sbId.startsWith("ENCHANTMENT_") || sbId.startsWith("ENCHANTED_BOOK")) return 0L
        val baseId = ItemValueBreakdown.getCrimsonBaseId(sbId) ?: sbId
        val price = ItemValueData.getPrice(baseId).coerceAtLeast(0L)
        val name = if (baseId != sbId) ItemValueData.getItemName(baseId) else stack.hoverName.formattedText
        if (price > 0L) list.add("§7Base item: $name §7(§6${formatPrice(price)}§7)")
        else list.add("§7Base item: $name §8(Not on AH/BZ)")
        return price
    }

    private fun addCoinsConsumed(stack: ItemStack, sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        if (sbId == "CROWN_OF_AVARICE") {
            var coins = data.getLongOr("collected_coins", 0L)
            if (coins <= 0L) {
                for (line in stack.lore) {
                    val clean = line.removeFormatting()
                    if (! clean.startsWith("Coins Consumed: ")) continue
                    coins = clean.substringAfter("Coins Consumed: ").replace(",", "").trim().toLongOrNull() ?: 0L
                    break
                }
            }
            val capped = coins.coerceIn(0L, 1_000_000_000L)
            if (capped <= 0L) return 0L
            list.add("§7Coins Consumed: §6${formatPrice(capped)}")
            return capped
        }

        val midasCap = when (sbId) {
            "MIDAS_SWORD", "STARRED_MIDAS_SWORD" -> 250_000_000L
            "MIDAS_STAFF", "STARRED_MIDAS_STAFF" -> 500_000_000L
            else -> return 0L
        }

        val coins = (data.getLongOr("winning_bid", 0L) + data.getLongOr("additional_coins", 0L)).coerceIn(0L, midasCap)
        if (coins <= 0L) return 0L
        list.add("§7Coins Paid: §6${formatPrice(coins)}")
        return coins
    }

    private fun addReforgeStone(stack: ItemStack, data: CompoundTag, list: MutableList<String>): Long {
        val modifier = data.getString("modifier").getOrNull()?.takeIf(String::isNotEmpty) ?: return 0L
        val reforge = ItemValueData.reforgeStones[modifier] ?: return 0L
        var rarity = ItemUtils.getRarity(stack)
        if (data.getIntOr("rarity_upgrades", 0) > 0 && rarity.ordinal > 1) rarity = ItemRarity.entries[rarity.ordinal - 1]

        val stonePrice = ItemValueData.getPrice(reforge.stoneId)
        val total = stonePrice + (reforge.costs[rarity] ?: 0L)
        if (total <= 0L) return 0L

        val stoneInfo = if (stonePrice > 0L) " §7(§e${formatPrice(stonePrice)}§7)" else ""
        list.add("§7Reforge: §9${reforge.name}$stoneInfo §7(§6${formatPrice(total)}§7)")
        return total
    }

    private fun addSkin(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val rawSkin = data.getString("skin").getOrNull()?.takeIf(String::isNotEmpty) ?: run {
            if (! sbId.startsWith("PET-")) return 0L
            val petInfo = data.getString("petInfo").getOrNull()?.takeIf(String::isNotEmpty) ?: return 0L
            runCatching { JsonUtils.json.decodeFromString<JsonObject>(petInfo)["skin"]?.jsonPrimitive?.content }.getOrNull()
        } ?: return 0L

        val candidates = if (sbId.startsWith("PET-")) {
            listOf(if (rawSkin.startsWith("PET_SKIN_")) rawSkin else "PET_SKIN_$rawSkin", rawSkin)
        } else {
            listOf(rawSkin, "SKIN_$rawSkin", "PET_SKIN_$rawSkin")
        }

        for (id in candidates) {
            val price = ItemValueData.getPrice(id)
            if (price > 0L) {
                list.add("§7Skin: ${ItemValueData.getItemName(id)} §7(§6${formatPrice(price)}§7)")
                return price
            }
        }
        return 0L
    }

    private fun addRune(data: CompoundTag, list: MutableList<String>): Long {
        val runes = data.getCompound("runes").getOrNull() ?: return 0L
        val key = runes.keySet().singleOrNull() ?: return 0L
        val lvl = runes.getIntOr(key, 0)
        if (lvl <= 0) return 0L

        val upper = key.uppercase()
        val candidates = listOf("RUNE-$upper-$lvl", "RUNE-${upper}_2-$lvl")
        for (id in candidates) {
            val price = ItemValueData.getPrice(id)
            if (price > 0L) {
                list.add("§7Rune: ${ItemValueData.getItemName(id)} §7(§6${formatPrice(price)}§7)")
                return price
            }
        }
        return 0L
    }

    private fun addSingle(label: String, itemId: String, list: MutableList<String>): Long {
        val price = ItemValueData.getPrice(itemId)
        if (price <= 0L) return 0L
        list.add("$label §a✔ §7(§6${formatPrice(price)}§7)")
        return price
    }

    private fun addNamedSingle(label: String, itemId: String, list: MutableList<String>): Long {
        val price = ItemValueData.getPrice(itemId)
        if (price <= 0L) return 0L
        list.add("$label ${ItemValueData.getItemName(itemId)} §7(§6${formatPrice(price)}§7)")
        return price
    }

    private fun addEtherwarp(list: MutableList<String>): Long {
        val price = ItemValueData.getPrice("ETHERWARP_CONDUIT") + ItemValueData.getPrice("ETHERWARP_MERGER")
        if (price <= 0L) return 0L
        list.add("§7Etherwarp: §a✔ §7(§6${formatPrice(price)}§7)")
        return price
    }

    private fun addCounted(count: Int, max: Int, label: String, itemId: String, list: MutableList<String>, color: String): Long {
        if (count <= 0) return 0L
        val price = ItemValueData.getPrice(itemId) * count
        if (price <= 0L) return 0L
        list.add("$label: $color$count/$max §7(§6${formatPrice(price)}§7)")
        return price
    }

    private fun addHotPotatoBooks(data: CompoundTag, list: MutableList<String>): Long {
        val count = data.getIntOr("hot_potato_count", 0)
        if (count <= 0) return 0L
        var total = addCounted(count.coerceAtMost(10), 10, "§7Hot Potato Books", "HOT_POTATO_BOOK", list, "§e")
        total += addCounted((count - 10).coerceAtLeast(0), 5, "§7Fuming Potato Books", "FUMING_POTATO_BOOK", list, "§d")
        return total
    }

    private fun addSilex(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val eff = data.getCompound("enchantments").getOrNull()?.getIntOr("efficiency", 0) ?: return 0L
        val baseEff = when (sbId) {
            "STONK_PICKAXE" -> 6
            "PROMISING_SPADE" -> 10
            else -> 5
        }
        val silexCount = eff - baseEff
        if (silexCount <= 0) return 0L
        return addCounted(silexCount, if (sbId == "STONK_PICKAXE") 4 else 5, "§7Silex", "SIL_EX", list, "§e")
    }
}
