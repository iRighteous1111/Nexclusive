package com.github.noamm9.nexclusive.features.impl.nexclusive.itemvalue

import com.github.noamm9.init.DataDownloader
import com.github.noamm9.init.NetworkLoop
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.items.ItemRarity
import com.github.noamm9.utils.items.ItemUtils.customData
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.uppercaseFirst
import kotlinx.serialization.json.*
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.jvm.optionals.getOrNull

object ItemValueData {
    private const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
    private const val REFORGE_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/reforgestones.json"
    private const val COFL_PRICE_URL = "https://sky.coflnet.com/api/item/price/"
    private const val COFL_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val COFL_ERROR_RETRY_MS = 60 * 1000L

    private class CachedPrice(val price: Long, val expiresAt: Long)
    class CostEntry(val type: String, val itemId: String, val amount: Long)
    class ReforgeStone(val name: String, val stoneId: String, val costs: Map<ItemRarity, Long>)

    val itemNames = ConcurrentHashMap<String, String>()
    val upgradeCosts = ConcurrentHashMap<String, List<List<CostEntry>>>()
    val prestigeCosts = ConcurrentHashMap<String, List<CostEntry>>()
    val gemstoneSlotCosts = ConcurrentHashMap<String, Map<String, List<CostEntry>>>()
    val reforgeStones = ConcurrentHashMap<String, ReforgeStone>()
    val enchantNames = ConcurrentHashMap<String, Pair<String, Boolean>>()
    private val coflPrices = ConcurrentHashMap<String, CachedPrice>()
    private val coflFetching = ConcurrentHashMap.newKeySet<String>()

    private val loading = AtomicBoolean(false)
    private val loadLatch = CountDownLatch(1)
    val dataVersion = AtomicInteger(0)
    @Volatile var loaded = false
        private set

    val crimsonTiers = mapOf("HOT_" to 1, "BURNING_" to 2, "FIERY_" to 3, "INFERNAL_" to 4)
    val crimsonArmors = setOf("CRIMSON", "TERROR", "AURORA", "FERVOR", "HOLLOW")
    val masterStars = listOf("FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR")
    val stackingEnchants = setOf("compact", "cultivating", "expertise", "hecatomb", "champion", "toxophilite", "absorb")

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

    val onlyTierFivePrices = setOf("ferocious_mana", "hardened_mana", "mana_vampire", "strong_mana")

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
            try {
                loadLocalEnchants()
                loadHypixelItems()
                loadReforgeStones()
                loaded = true
                dataVersion.incrementAndGet()
            } finally {
                loadLatch.countDown()
            }
        }
    }

    fun preloadSync(stack: ItemStack) {
        ensureLoaded()
        runCatching { loadLatch.await(10, TimeUnit.SECONDS) }

        val sbId = stack.skyblockId.ifEmpty { return }
        val data = stack.customData
        val priceSource = EstimatedItemValue.priceSource.value
        if (priceSource == 2) return

        val baseId = ItemValueBreakdown.getCrimsonBaseId(sbId) ?: sbId
        val tier = data.getIntOr("item_tier", - 1)
        if (tier >= 0) getDungeonItemPrice(baseId, tier, data.getIntOr("baseStatBoostPercentage", 0) >= 50, sync = true)

        data.getCompound("runes").getOrNull()?.let { runes ->
            val key = runes.keySet().firstOrNull() ?: return@let
            val lvl = runes.getIntOr(key, 0)
            if (lvl > 0) getRunePrice(key, lvl, priceSource, sync = true)
        }
    }

    fun getPrice(itemId: String, priceSource: Int = EstimatedItemValue.priceSource.value): Long {
        val id = itemId.replace(':', '-')
        if (id == "SKYBLOCK_COIN") return 1L
        if (id.startsWith("RUNE-") && priceSource != 2) {
            val parts = id.split('-')
            val lvl = parts.lastOrNull()?.toIntOrNull()
            if (parts.size >= 3 && lvl != null) {
                return getRunePrice(parts.subList(1, parts.lastIndex).joinToString("-"), lvl, priceSource)
            }
        }
        return when (priceSource) {
            0 -> NetworkLoop.getBazaarPrice(id)?.sell?.takeIf { it > 0 } ?: NetworkLoop.getLowestBin(id) ?: NetworkLoop.getNpcSellPrice(id) ?: 0L
            1 -> NetworkLoop.getBazaarPrice(id)?.buy?.takeIf { it > 0 } ?: NetworkLoop.getLowestBin(id) ?: NetworkLoop.getNpcSellPrice(id) ?: 0L
            else -> NetworkLoop.getNpcSellPrice(id) ?: 0L
        }
    }

    fun getRunePrice(runeKey: String, level: Int, priceSource: Int = EstimatedItemValue.priceSource.value, sync: Boolean = false): Long {
        if (level <= 0) return 0L
        if (priceSource == 2) return NetworkLoop.getNpcSellPrice("RUNE") ?: 0L

        val upper = runeKey.uppercase()
        NetworkLoop.getLowestBin("RUNE-$upper-$level")?.takeIf { it > 0L }?.let { return it }
        NetworkLoop.getLowestBin("RUNE-${upper}_2-$level")?.takeIf { it > 0L }?.let { return it }

        if (upper.isEmpty() || ! upper.all { it.isLetterOrDigit() || it == '_' }) return 0L
        return getCoflPrice("rune:$upper:$level", sync) { fetchCoflRunePrice(upper) } ?: 0L
    }

    fun getDungeonItemPrice(itemId: String, itemTier: Int, isMaxQuality: Boolean, sync: Boolean = false): Long? {
        val id = itemId.replace(':', '-')
        if (id.isEmpty() || ! id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null

        val boost = if (isMaxQuality) "50" else "0-49"
        return getCoflPrice("dungeon:$id:$itemTier:$boost", sync) {
            val data = fetchJson("$COFL_PRICE_URL$id/bin?ItemTier=$itemTier&BaseStatBoost=$boost", 5_000)
            data["lowest"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        }
    }

    private fun getCoflPrice(key: String, sync: Boolean, fetcher: () -> Long): Long? {
        val cached = coflPrices[key]
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) return cached.price.takeIf { it > 0L }

        if (sync) {
            val price = runCatching(fetcher).getOrDefault(0L).coerceAtLeast(0L)
            coflPrices[key] = CachedPrice(price, System.currentTimeMillis() + if (price > 0L) COFL_CACHE_TTL_MS else COFL_ERROR_RETRY_MS)
            if (price != cached?.price) dataVersion.incrementAndGet()
            return price.takeIf { it > 0L }
        }

        if (coflFetching.add(key)) {
            ThreadUtils.async {
                try {
                    val price = fetcher().coerceAtLeast(0L)
                    coflPrices[key] = CachedPrice(price, System.currentTimeMillis() + if (price > 0L) COFL_CACHE_TTL_MS else COFL_ERROR_RETRY_MS)
                    if (price != cached?.price) dataVersion.incrementAndGet()
                } catch (_: Throwable) {
                    coflPrices[key] = CachedPrice(cached?.price ?: 0L, System.currentTimeMillis() + COFL_ERROR_RETRY_MS)
                } finally {
                    coflFetching.remove(key)
                }
            }
        }

        return cached?.price?.takeIf { it > 0L }
    }

    private fun fetchCoflRunePrice(upper: String): Long {
        val unique = runCatching {
            fetchJson("${COFL_PRICE_URL}UNIQUE_RUNE_$upper/current", 5_000)["buy"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        }.getOrDefault(0L)
        if (unique > 0L) return unique

        return runCatching {
            fetchJson("${COFL_PRICE_URL}RUNE_$upper/current", 5_000)["buy"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        }.getOrDefault(0L)
    }

    fun getItemName(itemId: String): String {
        val id = itemId.replace(':', '-')
        itemNames[id]?.let { return it }
        if (id.startsWith("ESSENCE_")) {
            val type = id.removePrefix("ESSENCE_").lowercase().uppercaseFirst()
            return "§d$type Essence"
        }
        return "§f" + id.lowercase().split('_').joinToString(" ") { it.uppercaseFirst() }
    }

    fun getEnchantName(rawKey: String, level: Int): String {
        val cleanKey = rawKey.lowercase()
        val info = enchantNames[cleanKey] ?: enchantNames[cleanKey.removePrefix("ultimate_")]
        val display = info?.first ?: cleanKey.removePrefix("ultimate_").split('_').joinToString(" ") { it.uppercaseFirst() }
        val isUlt = info?.second == true || cleanKey.startsWith("ultimate_")
        return "${if (isUlt) "§d§l" else "§9"}$display $level"
    }

    private fun getRarityColorCode(tier: String?) = when (tier?.uppercase()) {
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

    private fun fetchJson(url: String, timeoutMs: Int = 15_000): JsonObject {
        val conn = URI.create(url).toURL().openConnection() as HttpsURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Nexclusive")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        return JsonUtils.json.decodeFromString(text)
    }

    private fun loadHypixelItems() = runCatching {
        val data = fetchJson(ITEMS_URL)
        val items = data["items"]?.jsonArray ?: return@runCatching
        for (element in items) {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content?.replace(':', '-') ?: continue
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            itemNames[id] = "${getRarityColorCode(obj["tier"]?.jsonPrimitive?.content)}$name"

            obj["upgrade_costs"]?.jsonArray?.let { tiersArr ->
                upgradeCosts[id] = tiersArr.map { parseCostArray(it.jsonArray) }
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

    private fun parseCostArray(arr: JsonArray): List<CostEntry> = arr.mapNotNull { el ->
        val c = el.jsonObject
        when (val type = c["type"]?.jsonPrimitive?.content) {
            "ESSENCE" -> {
                val essenceType = c["essence_type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val amount = c["amount"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                CostEntry(type, "ESSENCE_$essenceType", amount)
            }
            "ITEM" -> {
                val itemId = c["item_id"]?.jsonPrimitive?.content?.replace(':', '-') ?: return@mapNotNull null
                val amount = c["amount"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                CostEntry(type, itemId, amount)
            }
            "COINS" -> {
                val amount = c["coins"]?.jsonPrimitive?.longOrNull ?: c["amount"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                CostEntry(type, "SKYBLOCK_COIN", amount)
            }
            else -> null
        }
    }

    private fun loadReforgeStones() = runCatching {
        val data = fetchJson(REFORGE_URL)
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
