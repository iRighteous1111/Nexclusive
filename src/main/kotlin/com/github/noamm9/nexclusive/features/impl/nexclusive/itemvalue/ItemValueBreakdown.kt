package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.utils.NumbersUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import kotlin.jvm.optionals.getOrNull

object ItemValueBreakdown {
    private val tierPrefixes = listOf("", "HOT_", "BURNING_", "FIERY_", "INFERNAL_")

    fun getCrimsonBaseId(sbId: String): String? {
        for ((prefix, _) in ItemValueData.crimsonTiers) {
            if (! sbId.startsWith(prefix)) continue
            val stripped = sbId.removePrefix(prefix)
            if (ItemValueData.crimsonArmors.any { stripped.startsWith("${it}_") }) return stripped
        }
        return null
    }

    fun addStars(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val rawStars = data.getIntOr("upgrade_level", data.getIntOr("dungeon_item_level", 0))
        val baseCrimson = getCrimsonBaseId(sbId)
        val crimsonTier = if (baseCrimson != null) {
            ItemValueData.crimsonTiers.entries.firstOrNull { sbId.startsWith(it.key) }?.value ?: 0
        } else 0

        if (rawStars <= 0 && crimsonTier <= 0) return 0L

        val aggregated = LinkedHashMap<String, Long>()
        var totalCoins = 0L

        fun collect(entries: List<ItemValueData.CostEntry>) {
            for (entry in entries) {
                if (entry.type == "COINS") totalCoins += entry.amount
                else aggregated[entry.itemId] = (aggregated[entry.itemId] ?: 0L) + entry.amount
            }
        }

        if (baseCrimson != null && crimsonTier > 0) {
            for (t in 0 until crimsonTier) {
                val tierId = "${tierPrefixes[t]}$baseCrimson"
                ItemValueData.upgradeCosts[tierId]?.forEach(::collect)
                ItemValueData.prestigeCosts[tierId]?.let(::collect)
            }
            ItemValueData.upgradeCosts[sbId]?.let { tiers ->
                for (i in 0 until rawStars.coerceAtMost(tiers.size)) collect(tiers[i])
            }
        } else {
            val tiers = ItemValueData.upgradeCosts[sbId] ?: return 0L
            for (i in 0 until rawStars.coerceAtMost(tiers.size)) collect(tiers[i])
        }

        if (aggregated.isEmpty() && totalCoins <= 0L) return 0L

        val materials = ArrayList<Pair<String, Long>>(aggregated.size + 1)
        var totalPrice = totalCoins
        if (totalCoins > 0L) materials.add("  §8- §6${ItemValueCalculator.formatPrice(totalCoins)} Coins" to totalCoins)

        for ((matId, amount) in aggregated) {
            val matPrice = ItemValueData.getPrice(matId) * amount
            if (matPrice <= 0L) continue
            totalPrice += matPrice
            materials.add("  §8- §7${NumbersUtils.formatComma(amount)}x ${ItemValueData.getItemName(matId)} §7(§6${ItemValueCalculator.formatPrice(matPrice)}§7)" to matPrice)
        }

        if (totalPrice <= 0L) return 0L

        val maxStars = ItemValueData.upgradeCosts[sbId]?.size ?: if (baseCrimson != null) 10 else 5
        val label = if (crimsonTier > 0) "§7Prestige & Stars" else "§7Stars"
        list.add("$label: §e${rawStars.coerceAtMost(maxStars)}/$maxStars §7(§6${ItemValueCalculator.formatPrice(totalPrice)}§7)")

        appendCappedList(materials, EstimatedItemValue.starMaterialCap.value, list)
        return totalPrice
    }

    fun addMasterStars(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        if (getCrimsonBaseId(sbId) != null) return 0L
        if ((ItemValueData.upgradeCosts[sbId]?.size ?: 5) > 5) return 0L

        val stars = data.getIntOr("upgrade_level", data.getIntOr("dungeon_item_level", 0))
        val masterCount = (stars - 5).coerceIn(0, 5)
        if (masterCount <= 0) return 0L

        var price = 0L
        for (i in 0 until masterCount) price += ItemValueData.getPrice(ItemValueData.masterStars[i])
        if (price <= 0L) return 0L

        list.add("§7Master Stars: §e$masterCount/5 §7(§6${ItemValueCalculator.formatPrice(price)}§7)")
        return price
    }

    fun addGemstones(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val gems = data.getCompound("gems").getOrNull() ?: return 0L
        if (gems.isEmpty) return 0L

        var total = 0L

        // Unlocked slot costs
        val unlocked = gems.getList("unlocked_slots").getOrNull()
        val slotMap = ItemValueData.gemstoneSlotCosts[sbId]
        if (unlocked != null && slotMap != null && ! unlocked.isEmpty()) {
            var slotTotal = 0L
            var unlockedCount = 0
            for (el in unlocked) {
                val slotKey = (el as? StringTag)?.value() ?: continue
                val costs = slotMap[slotKey] ?: continue
                unlockedCount++
                for (cost in costs) {
                    slotTotal += if (cost.type == "COINS") cost.amount else ItemValueData.getPrice(cost.itemId) * cost.amount
                }
            }
            if (slotTotal > 0L) {
                list.add("§7Gemstone Slots: §e$unlockedCount §7(§6${ItemValueCalculator.formatPrice(slotTotal)}§7)")
                total += slotTotal
            }
        }

        // Applied gemstones
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

        for ((gemId, count) in counts) {
            val price = ItemValueData.getPrice(gemId) * count
            if (price <= 0L) continue
            total += price
            val prefix = if (count > 1) "§7${count}x " else ""
            list.add("§7Gemstone: $prefix${ItemValueData.getItemName(gemId)} §7(§6${ItemValueCalculator.formatPrice(price)}§7)")
        }

        return total
    }

    fun addEnchantments(sbId: String, data: CompoundTag, list: MutableList<String>): Long {
        val enchants = data.getCompound("enchantments").getOrNull() ?: return 0L
        if (enchants.isEmpty) return 0L

        val priced = ArrayList<Pair<String, Long>>(enchants.size())
        var total = 0L

        for (rawKey in enchants.keySet()) {
            val name = rawKey.lowercase()
            val rawLevel = enchants.getIntOr(rawKey, 0)
            if (rawLevel <= 0) continue
            if (name == "efficiency" && rawLevel > 5 && sbId != "PROMISING_SPADE") continue
            if (ItemValueData.alwaysActiveEnchants[name]?.contains(sbId) == true) continue

            val calcLevel = if (name in ItemValueData.stackingEnchants) 1 else rawLevel
            val price = getEnchantPrice(name, calcLevel)
            if (price <= 0L) continue

            total += price
            priced.add("  §8- ${ItemValueData.getEnchantName(name, rawLevel)} §7(§6${ItemValueCalculator.formatPrice(price)}§7)" to price)
        }

        if (priced.isEmpty() || total <= 0L) return 0L

        list.add("§7Enchantments: §6${ItemValueCalculator.formatPrice(total)}")
        appendCappedList(priced, EstimatedItemValue.enchantmentsCap.value, list)
        return total
    }

    private fun getEnchantPrice(name: String, level: Int): Long {
        ItemValueData.endcapEnchants[name]?.let { (reqLvl, endcapItem) ->
            if (level >= reqLvl) return getEnchantPrice(name, reqLvl - 1) + ItemValueData.getPrice(endcapItem)
        }

        val upper = name.uppercase()
        if (name in ItemValueData.onlyTierOnePrices && level > 1) {
            val mult = ItemValueData.getPrice("ENCHANTMENT_${upper}_1") * (1L shl (level - 1))
            val direct = ItemValueData.getPrice("ENCHANTMENT_${upper}_$level")
            return if (direct in 1 until mult) direct else mult
        }

        if (name in ItemValueData.onlyTierFivePrices && level > 5) {
            return ItemValueData.getPrice("ENCHANTMENT_${upper}_5") * (1L shl (level - 5))
        }

        val direct = ItemValueData.getPrice("ENCHANTMENT_${upper}_$level")
        if (direct > 0L) return direct

        if (level in 2..5) {
            val t1 = ItemValueData.getPrice("ENCHANTMENT_${upper}_1")
            if (t1 > 0L) return t1 * (1L shl (level - 1))
        }
        return 0L
    }

    private fun appendCappedList(entries: MutableList<Pair<String, Long>>, cap: Int, out: MutableList<String>) {
        entries.sortByDescending { it.second }
        var shown = 0
        var hiddenPrice = 0L
        for ((line, price) in entries) {
            if (shown < cap) {
                out.add(line)
                shown++
            } else hiddenPrice += price
        }
        val hidden = entries.size - shown
        if (hidden > 0) out.add("  §8- §7and $hidden more (§6${ItemValueCalculator.formatPrice(hiddenPrice)}§7)")
    }
}
