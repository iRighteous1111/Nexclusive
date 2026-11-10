package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.items.ItemRarity
import com.github.noamm9.utils.items.ItemUtils
import com.github.noamm9.utils.items.ItemUtils.customData
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.RenderHelper.width
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

    private val tierPrefixes = listOf("", "HOT_", "BURNING_", "FIERY_", "INFERNAL_")
    private val drillPartTags = listOf("drill_part_upgrade_module", "drill_part_engine", "drill_part_fuel_tank")
    private val rodPartTags = listOf("line", "hook", "sinker")

    fun formatPrice(value: Long): String {
        return if (EstimatedItemValue.exactPrice.value) NumbersUtils.formatComma(value)
        else NumbersUtils.format(value)
    }

    fun calculate(stack: ItemStack): CalculatedValue? {
        if (stack.isEmpty) return null
        val sbId = stack.skyblockId.ifEmpty { return null }
        val data = stack.customData

        val list = ArrayList<String>(16)
        list.add("§eEstimated Item Value:")

        val basePair = addBaseItem(stack, sbId, list)
        val basePrice = basePair.first
        var totalPrice = basePrice

        totalPrice += addAttributeStars(sbId, data, list)
        totalPrice += addReforgeStone(stack, data, list)

        // Single modifiers
        if (data.getIntOr("rarity_upgrades", 0) > 0) totalPrice += addSingle("§7Recombobulated:", "RECOMBOBULATOR_3000", list)
        if (data.getIntOr("art_of_war_count", 0) > 0) totalPrice += addSingle("§7The Art of War:", "THE_ART_OF_WAR", list)
        if (data.getIntOr("artOfPeaceApplied", 0) > 0) totalPrice += addSingle("§7The Art Of Peace:", "THE_ART_OF_PEACE", list)
        if (data.getIntOr("wood_singularity_count", 0) > 0) totalPrice += addSingle("§7Wood Singularity:", "WOOD_SINGULARITY", list)
        if (data.getIntOr("jalapeno_count", 0) > 0) totalPrice += addSingle("§7Jalapeno Book:", "JALAPENO_BOOK", list)
        if (data.contains("stats_book")) totalPrice += addSingle("§7Book of Stats:", "BOOK_OF_STATS", list)
        if (data.getIntOr("divan_powder_coating", 0) > 0) totalPrice += addSingle("§7Divan Powder Coating:", "DIVAN_POWDER_COATING", list)
        if (data.getIntOr("mithril_infusion", 0) > 0 || data.getByteOr("mithril_infusion", 0) > 0) totalPrice += addSingle("§7Mithril Infusion:", "MITHRIL_INFUSION", list)
        if (data.getIntOr("free_will", 0) > 0 || data.getByteOr("free_will", 0) > 0) totalPrice += addSingle("§7Free Will:", "FREE_WILL", list)
        if (data.getIntOr("ethermerge", 0) > 0 || data.getByteOr("ethermerge", 0) > 0) totalPrice += addEtherwarp(list)

        data.getString("power_ability_scroll").getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            totalPrice += addNamedSingle("§7Power Scroll:", it, list)
        }
        data.getString("talisman_enrichment").getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            totalPrice += addNamedSingle("§7Enrichment:", "TALISMAN_ENRICHMENT_${it.uppercase()}", list)
        }

        // Cosmetics
        if (! EstimatedItemValue.ignoreHelmetSkins.value) {
            data.getString("skin").getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                totalPrice += addNamedSingle("§7Skin:", it, list)
            }
        }
        if (! EstimatedItemValue.ignoreArmorDyes.value) {
            data.getString("dye_item").getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                totalPrice += addNamedSingle("§7Dye:", it, list)
            }
        }
        if (! EstimatedItemValue.ignoreRunes.value && ! sbId.startsWith("RUNE")) {
            data.getCompound("runes").getOrNull()?.let { runes ->
                val key = runes.keySet().singleOrNull()
                val lvl = key?.let { runes.getIntOr(it, 0) } ?: 0
                if (key != null && lvl > 0) totalPrice += addNamedSingle("§7Rune:", "RUNE-${key.uppercase()}-$lvl", list)
            }
        }

        // Counted modifiers
        totalPrice += addMasterStars(sbId, data, list)
        totalPrice += addHotPotatoBooks(data, list)
        totalPrice += addCounted(data.getIntOr("farming_for_dummies_count", 0), 5, "§7Farming for Dummies", "FARMING_FOR_DUMMIES", list, "§e")
        totalPrice += addCounted(data.getIntOr("polarvoid", 0), 5, "§7Polarvoid Books", "POLARVOID_BOOK", list, "§e")
        totalPrice += addCounted(data.getIntOr("wet_book_count", 0), 5, "§7Wet Books", "WET_BOOK", list, "§e")
        totalPrice += addCounted(data.getIntOr("bookworm_books", 0), 5, "§7Bookworm's Favorite Books", "BOOKWORM_BOOK", list, "§e")
        totalPrice += addCounted(data.getIntOr("tuned_transmission", 0), 4, "§7Transmission Tuners", "TRANSMISSION_TUNER", list, "§e")
        totalPrice += addCounted(data.getIntOr("mana_disintegrator_count", 0), 10, "§7Mana Disintegrators", "MANA_DISINTEGRATOR", list, "§e")
        totalPrice += addCounted(data.getIntOr("levelable_overclocks", 0), 10, "§7Overclocker 3000", "OVERCLOCKER_3000", list, "§e")
        totalPrice += addCounted(data.getIntOr("sack_pss", 0), 3, "§7Pocket Sack-in-a-Sack", "POCKET_SACK_IN_A_SACK", list, "§e")
        totalPrice += addSilex(sbId, data, list)

        // Dynamic lists & parts
        totalPrice += addAbilityScrolls(data, list)
        totalPrice += addBoosters(data, list)
        totalPrice += addDrillUpgrades(data, list)
        totalPrice += addRodParts(data, list)
        totalPrice += addGemstoneSlotCosts(sbId, data, list)
        totalPrice += addGemstones(data, list)
        totalPrice += addEnchantments(sbId, data, list)

        if (list.size <= 2 && (totalPrice <= 0L || sbId.startsWith("ENCHANTMENT_"))) return null

        list.add("")
        list.add("§eTotal: §6§l${formatPrice(totalPrice)}")

        var maxWidth = 0f
        for (i in 0 until list.size) {
            val w = list[i].width().toFloat()
            if (w > maxWidth) maxWidth = w
        }

        return CalculatedValue(totalPrice, basePrice, list, maxWidth + 12f, list.size * 10f + 8f)
    }

    private fun addBaseItem(stack: ItemStack, sbId: String, list: MutableList<String>): Pair<Long, String> {
        if (sbId.startsWith("ENCHANTMENT_") || sbId.startsWith("ENCHANTED_BOOK")) return 0L to sbId
        val baseId = getCrimsonBaseId(sbId) ?: sbId
        val price = ItemValueData.getPrice(baseId).coerceAtLeast(0L)
        val name = if (baseId != sbId) ItemValueData.getItemName(baseId) else stack.hoverName.formattedText
        if (price > 0L) {
            list.add("§7Base item: $name §7(§6${formatPrice(price)}§7)")
        } else {
            list.add("§7Base item: $name §8(Not on AH/BZ)")
        }
        return price to baseId
    }

    private fun getCrimsonBaseId(sbId: String): String? {
        for ((prefix, _) in ItemValueData.crimsonTiers) {
            if (! sbId.startsWith(prefix)) continue
            val stripped = sbId.removePrefix(prefix)
            if (ItemValueData.crimsonArmors.any { stripped.startsWith("${it}_") }) return stripped
        }
        return null
    }

    private fun addReforgeStone(stack: ItemStack, data: CompoundTag, list: MutableList<String>): Long {
        val modifier = data.getString("modifier").getOrNull()?.takeIf { it.isNotEmpty() } ?: return 0L
        val reforge = ItemValueData.reforgeStones[modifier] ?: return 0L
        var rarity = ItemUtils.getRarity(stack)
        if (data.getIntOr("rarity_upgrades", 0) > 0 && rarity.ordinal > 1) {
            rarity = ItemRarity.entries[rarity.ordinal - 1]
        }
        val stonePrice = ItemValueData.getPrice(reforge.stoneId)
        val applyCost = reforge.costs[rarity] ?: 0L
        val total = stonePrice + applyCost
        if (total <= 0L) return 0L

        val stoneInfo = if (stonePrice > 0L) " §7(§e${formatPrice(stonePrice)}§7)" else ""
        list.add("§7Reforge: §9${reforge.name}$stoneInfo §7(§6${formatPrice(total)}§7)")
        return total
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
        val hpb = count.coerceAtMost(10)
        val fuming = (count - 10).coerceAtLeast(0)
        var total = addCounted(hpb, 10, "§7Hot Potato Books", "HOT_POTATO_BOOK", list, "§e")
        total += addCounted(fuming, 5, "§7Fuming Potato Books", "FUMING_POTATO_BOOK", list, "§d")
        return total
    }

    private fun addSilex(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val enchants = data.getCompound("enchantments").getOrNull() ?: return 0L
        val eff = enchants.getIntOr("efficiency", 0)
        val baseEff = when (sbId) {
            "STONK_PICKAXE" -> 6
            "PROMISING_SPADE" -> 10
            else -> 5
        }
        val silexCount = eff - baseEff
        if (silexCount <= 0) return 0L
        val maxSilex = if (sbId == "STONK_PICKAXE") 4 else 5
        return addCounted(silexCount, maxSilex, "§7Silex", "SIL_EX", list, "§e")
    }

    private fun addMasterStars(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        if (getCrimsonBaseId(sbId) != null) return 0L
        val maxBaseStars = ItemValueData.upgradeCosts[sbId]?.size ?: 5
        if (maxBaseStars > 5) return 0L
        val stars = data.getIntOr("upgrade_level", data.getIntOr("dungeon_item_level", 0))
        val masterCount = (stars - 5).coerceIn(0, 5)
        if (masterCount <= 0) return 0L

        var price = 0L
        for (i in 0 until masterCount) {
            price += ItemValueData.getPrice(ItemValueData.masterStars[i])
        }
        if (price <= 0L) return 0L
        list.add("§7Master Stars: §e$masterCount/5 §7(§6${formatPrice(price)}§7)")
        return price
    }

    private fun addAttributeStars(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val rawStars = data.getIntOr("upgrade_level", data.getIntOr("dungeon_item_level", 0))
        val baseCrimson = getCrimsonBaseId(sbId)
        val crimsonTier = if (baseCrimson != null) {
            ItemValueData.crimsonTiers.entries.firstOrNull { sbId.startsWith(it.key) }?.value ?: 0
        } else 0

        if (rawStars <= 0 && crimsonTier <= 0) return 0L

        val aggregated = LinkedHashMap<String, Long>()
        var totalCoins = 0L

        fun collectCosts(entries: List<ItemValueData.CostEntry>) {
            for (entry in entries) {
                if (entry.type == "COINS") totalCoins += entry.amount
                else aggregated[entry.itemId] = (aggregated[entry.itemId] ?: 0L) + entry.amount
            }
        }

        if (baseCrimson != null && crimsonTier > 0) {
            for (t in 0 until crimsonTier) {
                val tierItemId = "${tierPrefixes[t]}$baseCrimson"
                ItemValueData.upgradeCosts[tierItemId]?.forEach(::collectCosts)
                ItemValueData.prestigeCosts[tierItemId]?.let(::collectCosts)
            }
            val currentTiers = ItemValueData.upgradeCosts[sbId]
            if (currentTiers != null) {
                val limit = rawStars.coerceAtMost(currentTiers.size)
                for (i in 0 until limit) collectCosts(currentTiers[i])
            }
        } else {
            val tiers = ItemValueData.upgradeCosts[sbId] ?: return 0L
            val limit = rawStars.coerceAtMost(tiers.size)
            for (i in 0 until limit) collectCosts(tiers[i])
        }

        if (aggregated.isEmpty() && totalCoins <= 0L) return 0L

        val pricedMaterials = ArrayList<Pair<String, Long>>(aggregated.size + 1)
        var totalStarPrice = totalCoins
        if (totalCoins > 0L) {
            pricedMaterials.add("  §8- §6${formatPrice(totalCoins)} Coins" to totalCoins)
        }
        for ((matId, amount) in aggregated) {
            val matPrice = ItemValueData.getPrice(matId) * amount
            if (matPrice <= 0L) continue
            totalStarPrice += matPrice
            val matName = ItemValueData.getItemName(matId)
            pricedMaterials.add("  §8- §7${NumbersUtils.formatComma(amount)}x $matName §7(§6${formatPrice(matPrice)}§7)" to matPrice)
        }

        if (totalStarPrice <= 0L) return 0L

        val maxStars = ItemValueData.upgradeCosts[sbId]?.size ?: if (baseCrimson != null) 10 else 5
        val displayStars = rawStars.coerceAtMost(maxStars)
        if (crimsonTier > 0) {
            list.add("§7Prestige & Stars: §e$displayStars/$maxStars §7(§6${formatPrice(totalStarPrice)}§7)")
        } else {
            list.add("§7Stars: §e$displayStars/$maxStars §7(§6${formatPrice(totalStarPrice)}§7)")
        }

        pricedMaterials.sortByDescending { it.second }
        val cap = EstimatedItemValue.starMaterialCap.value
        var shown = 0
        var hiddenPrice = 0L
        for ((line, p) in pricedMaterials) {
            if (shown < cap) {
                list.add(line)
                shown++
            } else {
                hiddenPrice += p
            }
        }
        val hiddenCount = pricedMaterials.size - shown
        if (hiddenCount > 0) {
            list.add("  §8- §7and $hiddenCount more (§6${formatPrice(hiddenPrice)}§7)")
        }

        return totalStarPrice
    }

    private fun addAbilityScrolls(data: CompoundTag, list: MutableList<String>): Long {
        val scrolls = data.getList("ability_scroll").getOrNull() ?: return 0L
        var total = 0L
        for (el in scrolls) {
            val id = (el as? StringTag)?.value() ?: continue
            total += addNamedSingle("§7Ability Scroll:", id, list)
        }
        return total
    }

    private fun addBoosters(data: CompoundTag, list: MutableList<String>): Long {
        val boosters = data.getList("boosters").getOrNull() ?: return 0L
        var total = 0L
        for (el in boosters) {
            val raw = (el as? StringTag)?.value() ?: continue
            total += addNamedSingle("§7Booster:", "${raw.uppercase()}_BOOSTER", list)
        }
        return total
    }

    private fun addDrillUpgrades(data: CompoundTag, list: MutableList<String>): Long {
        var total = 0L
        for (tag in drillPartTags) {
            val partId = data.getString(tag).getOrNull()?.takeIf { it.isNotEmpty() }?.uppercase() ?: continue
            total += addNamedSingle("§7Drill Part:", partId, list)
        }
        return total
    }

    private fun addRodParts(data: CompoundTag, list: MutableList<String>): Long {
        var total = 0L
        for (tag in rodPartTags) {
            val comp = data.getCompound(tag).getOrNull() ?: continue
            val partId = comp.getString("part").getOrNull()?.takeIf { it.isNotEmpty() }?.uppercase() ?: continue
            total += addNamedSingle("§7Rod Part:", partId, list)
        }
        return total
    }

    private fun addGemstoneSlotCosts(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val gems = data.getCompound("gems").getOrNull() ?: return 0L
        val unlocked = gems.getList("unlocked_slots").getOrNull() ?: return 0L
        if (unlocked.isEmpty()) return 0L

        val slotMap = ItemValueData.gemstoneSlotCosts[sbId] ?: return 0L
        var total = 0L
        val slotNames = ArrayList<String>()

        for (el in unlocked) {
            val slotKey = (el as? StringTag)?.value() ?: continue
            val costs = slotMap[slotKey] ?: continue
            for (cost in costs) {
                total += if (cost.type == "COINS") cost.amount else ItemValueData.getPrice(cost.itemId) * cost.amount
            }
            val prettySlot = slotKey.substringBefore('_').lowercase().replaceFirstChar(Char::uppercase)
            slotNames.add(prettySlot)
        }

        if (total > 0L && slotNames.isNotEmpty()) {
            list.add("§7Gemstone Slots: §e${slotNames.size} §7(§6${formatPrice(total)}§7)")
        }
        return total
    }

    private fun addGemstones(data: CompoundTag, list: MutableList<String>): Long {
        val gems = data.getCompound("gems").getOrNull() ?: return 0L
        if (gems.isEmpty) return 0L

        val counts = LinkedHashMap<String, Int>()
        for (key in gems.keySet()) {
            if (key == "unlocked_slots" || key.endsWith("_gem")) continue
            val quality = gems.getString(key).getOrNull()
                ?: gems.getCompound(key).getOrNull()?.getString("quality")?.getOrNull()
                ?: continue
            if (quality.isEmpty()) continue

            val type = gems.getString("${key}_gem").getOrNull() ?: key.substringBeforeLast('_')
            val gemId = "${quality.uppercase()}_${type.uppercase()}_GEM"
            counts[gemId] = (counts[gemId] ?: 0) + 1
        }

        var total = 0L
        for ((gemId, count) in counts) {
            val price = ItemValueData.getPrice(gemId) * count
            if (price <= 0L) continue
            total += price
            val prefix = if (count > 1) "§7${count}x " else ""
            list.add("§7Gemstone: $prefix${ItemValueData.getItemName(gemId)} §7(§6${formatPrice(price)}§7)")
        }
        return total
    }

    private fun addEnchantments(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val enchants = data.getCompound("enchantments").getOrNull() ?: return 0L
        if (enchants.isEmpty) return 0L

        val priced = ArrayList<Pair<String, Long>>(enchants.size())
        var total = 0L

        for (rawKey in enchants.keySet()) {
            val name = rawKey.lowercase()
            var level = enchants.getIntOr(rawKey, 0)
            if (level <= 0) continue

            if (name == "efficiency" && level > 5 && sbId != "PROMISING_SPADE") continue
            if (ItemValueData.alwaysActiveEnchants[name]?.contains(sbId) == true) continue
            if (name in ItemValueData.stackingEnchants) level = 1

            val price = getEnchantPrice(name, level)
            if (price <= 0L) continue

            total += price
            val display = ItemValueData.getEnchantName(name, enchants.getIntOr(rawKey, level))
            priced.add("  §8- $display §7(§6${formatPrice(price)}§7)" to price)
        }

        if (priced.isEmpty() || total <= 0L) return 0L

        list.add("§7Enchantments: §6${formatPrice(total)}")
        priced.sortByDescending { it.second }

        val cap = EstimatedItemValue.enchantmentsCap.value
        var shown = 0
        var hiddenPrice = 0L
        for ((line, p) in priced) {
            if (shown < cap) {
                list.add(line)
                shown++
            } else {
                hiddenPrice += p
            }
        }
        val hidden = priced.size - shown
        if (hidden > 0) {
            list.add("  §8- §7and $hidden more (§6${formatPrice(hiddenPrice)}§7)")
        }

        return total
    }

    private fun getEnchantPrice(name: String, level: Int): Long {
        ItemValueData.endcapEnchants[name]?.let { (reqLvl, endcapItem) ->
            if (level >= reqLvl) {
                val prevPrice = getEnchantPrice(name, reqLvl - 1)
                val capPrice = ItemValueData.getPrice(endcapItem)
                return prevPrice + capPrice
            }
        }

        val upper = name.uppercase()
        if (name in ItemValueData.onlyTierOnePrices && level > 1) {
            val t1 = ItemValueData.getPrice("ENCHANTMENT_${upper}_1")
            val mult = t1 * (1L shl (level - 1))
            val direct = ItemValueData.getPrice("ENCHANTMENT_${upper}_$level")
            return if (direct in 1 until mult) direct else mult
        }

        if (name in ItemValueData.onlyTierFivePrices && level > 5) {
            val t5 = ItemValueData.getPrice("ENCHANTMENT_${upper}_5")
            return t5 * (1L shl (level - 5))
        }

        val direct = ItemValueData.getPrice("ENCHANTMENT_${upper}_$level")
        if (direct > 0L) return direct

        if (level in 2..5) {
            val t1 = ItemValueData.getPrice("ENCHANTMENT_${upper}_1")
            if (t1 > 0L) return t1 * (1L shl (level - 1))
        }
        return 0L
    }
}
