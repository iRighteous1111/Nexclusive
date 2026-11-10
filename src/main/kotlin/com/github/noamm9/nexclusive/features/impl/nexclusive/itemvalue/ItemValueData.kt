package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.init.DataDownloader
import com.github.noamm9.init.NetworkLoop
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.items.ItemRarity
import com.github.noamm9.utils.network.WebUtils
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ItemValueData {
    private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
    private const val REFORGE_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/reforgestones.json"

    class CostEntry(val type: String, val itemId: String, val amount: Long)
    class ReforgeStone(val name: String, val stoneId: String, val costs: Map<ItemRarity, Long>)

    val itemNames = ConcurrentHashMap<String, String>()
    val upgradeCosts = ConcurrentHashMap<String, List<List<CostEntry>>>()
    val prestigeCosts = ConcurrentHashMap<String, List<CostEntry>>()
    val gemstoneSlotCosts = ConcurrentHashMap<String, Map<String, List<CostEntry>>>()
    val reforgeStones = ConcurrentHashMap<String, ReforgeStone>()
    val enchantNames = ConcurrentHashMap<String, Pair<String, Boolean>>()

    private val loading = AtomicBoolean(false)
    @Volatile var loaded = false
        private set

    val crimsonTiers = mapOf(
        "HOT_" to 1,
        "BURNING_" to 2,
        "FIERY_" to 3,
        "INFERNAL_" to 4
    )

    val crimsonArmors = setOf("CRIMSON", "TERROR", "AURORA", "FERVOR", "HOLLOW")

    val masterStars = listOf(
        "FIRST_MASTER_STAR",
        "SECOND_MASTER_STAR",
        "THIRD_MASTER_STAR",
        "FOURTH_MASTER_STAR",
        "FIFTH_MASTER_STAR"
    )

    val stackingEnchants = setOf(
        "compact", "cultivating", "expertise", "hecatomb", "champion", "toxophilite", "absorb"
    )

    val alwaysActiveEnchants = mapOf(
        "compact" to setOf("PROMISING_PICKAXE", "PICKONIMBUS"),
        "cultivating" to setOf(
            "MELON_DICER", "MELON_DICER_2", "MELON_DICER_3",
            "PUMPKIN_DICER", "PUMPKIN_DICER_2", "PUMPKIN_DICER_3",
            "THEORETICAL_HOE_CARROT_1", "THEORETICAL_HOE_CARROT_2", "THEORETICAL_HOE_CARROT_3",
            "THEORETICAL_HOE_NETHER_1", "THEORETICAL_HOE_NETHER_2", "THEORETICAL_HOE_NETHER_3",
            "THEORETICAL_HOE_POTATO_1", "THEORETICAL_HOE_POTATO_2", "THEORETICAL_HOE_POTATO_3",
            "THEORETICAL_HOE_CANE_1", "THEORETICAL_HOE_CANE_2", "THEORETICAL_HOE_CANE_3",
            "THEORETICAL_HOE_WHEAT_1", "THEORETICAL_HOE_WHEAT_2", "THEORETICAL_HOE_WHEAT_3",
            "CACTUS_KNIFE", "COCO_CHOPPER", "FUNGI_CUTTER"
        ),
        "replenish" to setOf("ADVANCED_GARDENING_AXE", "ADVANCED_GARDENING_HOE", "COCO_CHOPPER")
    )

    val onlyTierOnePrices = setOf(
        "ultimate_chimera", "ultimate_fatal_tempo", "smoldering", "ultimate_flash",
        "divine_gift", "ice_cold", "pessimist", "pesterminator", "transylvanian"
    )

    val onlyTierFivePrices = setOf(
        "ferocious_mana", "hardened_mana", "mana_vampire", "strong_mana"
    )

    val endcapEnchants = mapOf(
        "scavenger" to (6 to "GOLDEN_BOUNTY"),
        "pesterminator" to (6 to "PESTHUNTING_GUIDE"),
        "luck_of_the_sea" to (7 to "GOLD_BOTTLE_CAP"),
        "piscary" to (7 to "TROUBLED_BUBBLE"),
        "frail" to (7 to "SEVERED_PINCER"),
        "spiked_hook" to (7 to "OCTOPUS_TENDRIL"),
        "charm" to (6 to "CHAIN_END_TIMES"),
        "venomous" to (7 to "FATEFUL_STINGER"),
        "dedication" to (4 to "OVERGROWN_GRASS"),
        "sunder" to (6 to "ENCHANTED_TURBO_GOURD"),
        "efficiency" to (6 to "SIL_EX")
    )

    fun ensureLoaded() {
        if (loaded || ! loading.compareAndSet(false, true)) return
        ThreadUtils.async {
            loadLocalEnchants()
            loadHypixelItems()
            loadReforgeStones()
            loaded = true
            EstimatedItemValue.clearCache()
        }
    }

    fun getPrice(itemId: String, priceSource: Int = EstimatedItemValue.priceSource.value): Long {
        val id = itemId.replace(':', '-')
        if (id == "SKYBLOCK_COIN") return 1L
        return when (priceSource) {
            0 -> NetworkLoop.getBazaarPrice(id)?.sell?.takeIf { it > 0 } ?: NetworkLoop.getLowestBin(id) ?: NetworkLoop.getNpcSellPrice(id) ?: 0L
            1 -> NetworkLoop.getBazaarPrice(id)?.buy?.takeIf { it > 0 } ?: NetworkLoop.getLowestBin(id) ?: NetworkLoop.getNpcSellPrice(id) ?: 0L
            else -> NetworkLoop.getNpcSellPrice(id) ?: 0L
        }
    }

    fun getItemName(itemId: String): String {
        val id = itemId.replace(':', '-')
        itemNames[id]?.let { return it }
        if (id.startsWith("ESSENCE_")) {
            val type = id.removePrefix("ESSENCE_").lowercase().replaceFirstChar { it.uppercase() }
            return "§d$type Essence"
        }
        return "§f" + id.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    private fun getRarityColorCode(tier: String?): String = when (tier?.uppercase()) {
        "COMMON" -> "§f"
        "UNCOMMON" -> "§a"
        "RARE" -> "§9"
        "EPIC" -> "§5"
        "LEGENDARY" -> "§6"
        "MYTHIC" -> "§d"
        "DIVINE" -> "§b"
        "SPECIAL", "VERY_SPECIAL" -> "§c"
        "ULTIMATE", "SUPREME" -> "§4"
        else -> "§f"
    }

    fun getEnchantName(rawKey: String, level: Int): String {
        val cleanKey = rawKey.lowercase()
        val info = enchantNames[cleanKey] ?: enchantNames[cleanKey.removePrefix("ultimate_")]
        val display = info?.first ?: cleanKey.removePrefix("ultimate_").split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val isUlt = info?.second == true || cleanKey.startsWith("ultimate_")
        val prefix = if (isUlt) "§d§l" else "§9"
        return "$prefix$display $level"
    }

    private fun loadLocalEnchants() = runCatching {
        val raw = DataDownloader.loadJson<Map<String, Map<String, Map<String, Any>>>>("enchants.json")
        for ((type, list) in raw) {
            val isUlt = type == "ULTIMATE"
            for ((_, data) in list) {
                val loreName = data["loreName"] as? String ?: continue
                val nbtKey = loreName.lowercase().replace("'", "").replace("-", "_").replace(" ", "_")
                enchantNames[nbtKey] = loreName to isUlt
            }
        }
    }

    private suspend fun loadHypixelItems() = runCatching {
        val data = WebUtils.getAs<JsonObject>(ITEMS_URL).getOrThrow()
        val items = data["items"]?.jsonArray ?: return@runCatching
        for (element in items) {
            val obj = element.jsonObject
            val rawId = obj["id"]?.jsonPrimitive?.content ?: continue
            val id = rawId.replace(':', '-')
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            val tierColor = getRarityColorCode(obj["tier"]?.jsonPrimitive?.content)
            itemNames[id] = "$tierColor$name"

            obj["upgrade_costs"]?.jsonArray?.let { tiersArr ->
                val tiers = ArrayList<List<CostEntry>>(tiersArr.size)
                for (tierEl in tiersArr) {
                    val entries = parseCostArray(tierEl.jsonArray)
                    tiers.add(entries)
                }
                upgradeCosts[id] = tiers
            }

            obj["prestige"]?.jsonObject?.get("costs")?.jsonArray?.let { costsArr ->
                prestigeCosts[id] = parseCostArray(costsArr)
            }

            obj["gemstone_slots"]?.jsonArray?.let { slotsArr ->
                val counts = HashMap<String, Int>()
                val slotMap = HashMap<String, List<CostEntry>>()
                for (slotEl in slotsArr) {
                    val slotObj = slotEl.jsonObject
                    val slotType = slotObj["slot_type"]?.jsonPrimitive?.content ?: continue
                    val idx = counts.getOrDefault(slotType, 0)
                    counts[slotType] = idx + 1
                    val costsArr = slotObj["costs"]?.jsonArray ?: continue
                    slotMap["${slotType}_$idx"] = parseCostArray(costsArr)
                }
                if (slotMap.isNotEmpty()) gemstoneSlotCosts[id] = slotMap
            }
        }
    }

    private fun parseCostArray(arr: JsonArray): List<CostEntry> {
        val list = ArrayList<CostEntry>(arr.size)
        for (el in arr) {
            val c = el.jsonObject
            when (val type = c["type"]?.jsonPrimitive?.content) {
                "ESSENCE" -> {
                    val essenceType = c["essence_type"]?.jsonPrimitive?.content ?: continue
                    val amount = c["amount"]?.jsonPrimitive?.longOrNull ?: continue
                    list.add(CostEntry(type, "ESSENCE_$essenceType", amount))
                }
                "ITEM" -> {
                    val itemId = c["item_id"]?.jsonPrimitive?.content?.replace(':', '-') ?: continue
                    val amount = c["amount"]?.jsonPrimitive?.longOrNull ?: continue
                    list.add(CostEntry(type, itemId, amount))
                }
                "COINS" -> {
                    val amount = c["coins"]?.jsonPrimitive?.longOrNull ?: c["amount"]?.jsonPrimitive?.longOrNull ?: continue
                    list.add(CostEntry(type, "SKYBLOCK_COIN", amount))
                }
            }
        }
        return list
    }

    private suspend fun loadReforgeStones() = runCatching {
        val data = WebUtils.getAs<JsonObject>(REFORGE_URL).getOrThrow()
        for ((key, el) in data) {
            val obj = el.jsonObject
            val reforgeName = obj["reforgeName"]?.jsonPrimitive?.content ?: continue
            val stoneId = (obj["internalName"]?.jsonPrimitive?.content ?: key).replace(':', '-')
            val modifier = (obj["nbtModifier"]?.jsonPrimitive?.content ?: reforgeName)
                .lowercase()
                .replace(Regex("[^a-z0-9\\s_-]"), "")
                .replace(Regex("[\\s-]"), "_")

            val costsMap = HashMap<ItemRarity, Long>()
            obj["reforgeCosts"]?.jsonObject?.forEach { (rarityKey, costEl) ->
                val rarity = ItemRarity.fromName(rarityKey)
                val cost = costEl.jsonPrimitive.longOrNull
                if (rarity != ItemRarity.NONE && cost != null) costsMap[rarity] = cost
            }
            reforgeStones[modifier] = ReforgeStone(reforgeName.addColor(), stoneId, costsMap)
        }
    }
}
