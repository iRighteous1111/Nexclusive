package com.github.noamm9.nexclusive.features.impl.nexclusive

import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MessageSentEvent
import com.github.noamm9.event.impl.MouseClickEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.interfaces.IChatComponent
import com.github.noamm9.nexclusive.interfaces.INexChatComponent
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

object ChatFeatures : Feature("Various chat related tweaks.", name = "Chat Features", jsonName = "ChatFeatures") {
    // --- Compact Chat ---
    private val compactChat by ToggleSetting("Compact Chat", true)
        .section("Compact Chat")
        .withDescription("Compacts duplicate messages into (xN).")
    private val compactTime by SliderSetting("Compact Time", 60, 5, 120, 1)
        .withDescription("Time in seconds to compact identical messages.")

    // --- Chat Replacements ---
    private val cleanDungeonMessages by ToggleSetting("Cleaner Dungeons", true)
        .section("Chat Replacements")
        .withDescription("Shortens and cleans up dungeon chat messages.")
    private val cleanPartyFinderMessages by ToggleSetting("Cleaner PF", true)
        .withDescription("Shortens Party Finder announcements.")
    private val hideUselessMessages by ToggleSetting("Hide Useless Messages", true)
        .withDescription("Hides useless spam and notification messages.")
    private val hideNonRankInvites by ToggleSetting("Hide Non-Rank Invites", true)
        .withDescription("Hides party invites from non-ranked players.")

    // --- Chat Bypass ---
    private val chatBypass by ToggleSetting("Chat Bypass", false)
        .section("Chat Bypass")
        .withDescription("Bypasses chat filters on Hypixel.")
    private val bypassMode by DropdownSetting("Bypass Mode", 0, listOf("Cyrillic", "Wide", "Dots", "SmallCaps"))
        .withDescription("Chat filter bypass mode.")

    // --- Chat Peek ---
    private val chatPeek by ToggleSetting("Chat Peek", false)
        .section("Chat Peek")
        .withDescription("Shows chat when holding the peek key.")
    private val peekKey by KeybindSetting("Peek Key", GLFW.GLFW_KEY_Z)
        .withDescription("Key to peek at chat.")

    // --- Chat Emojis ---
    private val chatEmojis by ToggleSetting("Chat Emojis", true)
        .section("Chat Emojis")
        .withDescription("Converts emote codes like <3, :cat:, :dab:, :skull: into symbols.")

    // --- Copy Chat ---
    private val copyChat by ToggleSetting("Copy Chat", true)
        .section("Copy Chat")
        .withDescription("Right click or middle click a chat message to copy it.")
    private val copyButton by DropdownSetting("Copy Button", 0, listOf("Right Click", "Middle Click"))
        .withDescription("Mouse button used to copy messages.")
    private val copyWithCodes by ToggleSetting("Copy Color Codes", false)
        .withDescription("Whether to copy formatting codes (§).")

    // --- Chat Tweaks ---
    private val disableAutoScroll by ToggleSetting("Disable Auto Scroll", true)
        .section("Chat Tweaks")
        .withDescription("Prevents chat from scrolling down when scrolled up.")
    private val infiniteChatLimit by ToggleSetting("Infinite Chat Limit", true)
        .withDescription("Keeps all chat history instead of trimming at 100 messages.")
    private val keepChatHistory by ToggleSetting("Keep Chat History", true)
        .withDescription("Keeps chat history when changing servers or worlds.")

    // State
    private val chatList = mutableMapOf<String, Pair<Int, Long>>()
    private var wasPeeking = false

    // Methods accessed by MixinChatComponent
    @JvmStatic
    fun isPeeking(): Boolean = enabled && chatPeek.value && peekKey.isDown()

    @JvmStatic
    fun displayMode(mode: ChatComponent.DisplayMode): ChatComponent.DisplayMode =
        if (isPeeking()) ChatComponent.DisplayMode.FOREGROUND else mode

    @JvmStatic
    fun disablesAutoScroll(): Boolean = enabled && disableAutoScroll.value

    @JvmStatic
    fun keepsAllChatMessages(): Boolean = enabled && infiniteChatLimit.value

    @JvmStatic
    fun keepsChatHistory(): Boolean = enabled && keepChatHistory.value

    override fun init() {
        // Handle peek key release to reset chat scroll
        register<TickEvent> {
            val currentlyPeeking = isPeeking()
            if (wasPeeking && !currentlyPeeking) {
                mc.gui.chat.scrollChat(-100000)
            }
            wasPeeking = currentlyPeeking
        }

        // Receive messages: Replacements and Compact Chat
        register<ChatMessageEvent>(priority = EventPriority.LOWEST) {
            if (!enabled) return@register
            val formatted = event.formattedText
            val plain = event.unformattedText

            // Replacements
            when (val action = resolveReceivedMessage(formatted, plain)) {
                is HideMessage -> {
                    event.isCanceled = true
                    return@register
                }
                is ReplaceMessage -> {
                    event.isCanceled = true
                    val replacedComp = if (action.prefix.isEmpty()) {
                        Component.literal(action.message)
                    } else {
                        Component.literal("${action.prefix} ${action.message}")
                    }

                    if (compactChat.value && tryCompact(replacedComp, replacedComp.string.removeFormatting())) {
                        return@register
                    }

                    mc.execute {
                        mc.gui.chat.addClientSystemMessage(replacedComp)
                    }
                    return@register
                }
                KeepMessage -> Unit
            }

            // Compact Chat
            if (compactChat.value) {
                if (tryCompact(event.component, plain)) {
                    event.isCanceled = true
                    return@register
                }
            }
        }

        // Sent messages: Emojis and Chat Bypass
        register<MessageSentEvent> {
            if (!enabled) return@register
            var text = event.message

            if (chatEmojis.value) {
                text = replaceEmojis(text)
            }

            if (chatBypass.value) {
                text = applyChatBypass(text)
            }

            event.message = text
        }

        // Copy Chat on click
        register<MouseClickEvent> {
            if (!enabled || !copyChat.value) return@register
            if (mc.screen !is ChatScreen) return@register
            if (event.action != GLFW.GLFW_PRESS) return@register

            val targetButton = if (copyButton.value == 0) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_MIDDLE
            if (event.button != targetButton) return@register

            val copied = getHoveredChatMessage(copyWithCodes.value) ?: return@register
            if (copied.isBlank()) return@register

            mc.keyboardHandler.clipboard = copied
            NotificationManager.push("Chat Features", "Copied message to clipboard!")
            event.isCanceled = true
        }
    }

    private fun getHoveredChatMessage(withCodes: Boolean): String? {
        val chatHud = (mc.gui.chat as? IChatComponent) ?: return null
        val idx = chatHud.lineIndex.toInt()
        val ext = (mc.gui.chat as? INexChatComponent) ?: return null
        val visible = ext.nexGetTrimmedMessages()
        if (idx !in visible.indices) return null

        var fullIndex = -1
        for (i in visible.indices) {
            if (visible[i].endOfEntry) fullIndex++
            if (i == idx) break
        }

        val all = ext.nexGetAllMessages()
        val msg = all.getOrNull(fullIndex) ?: return null
        return if (withCodes) msg.content.formattedText else msg.content.unformattedText
    }

    private fun tryCompact(component: Component, plainText: String): Boolean {
        val msg = plainText.trim()
        if (msg.isEmpty()) return false
        if (msg.all { it == '-' || it == '=' || it == '▬' }) return false

        val data = chatList[msg]
        val lastTime = data?.second
        val id = msg.hashCode()

        if (lastTime != null && System.currentTimeMillis() - lastTime < compactTime.value.toLong() * 1000L) {
            val count = data.first + 1
            mc.execute {
                val ext = mc.gui.chat as? INexChatComponent ?: return@execute
                val scrollBefore = ext.nexGetScrollPos()
                ext.nexRemoveLines(id, msg)
                val newComp = component.copy().append(Component.literal(" §7($count)"))
                ext.nexAdd(newComp, id)
                chatList[msg] = Pair(count, System.currentTimeMillis())
                ext.nexSetScrollPos(scrollBefore)
            }
            return true
        }
        chatList[msg] = Pair(1, System.currentTimeMillis())
        return false
    }

    // --- Chat Bypass Implementation ---
    private val socialCommands = setOf("pc", "ac", "gc", "cc", "r", "msg", "w", "m", "message", "whisper", "tell", "pm")

    private fun applyChatBypass(message: String): String {
        val isCmd = message.startsWith("/")
        val clean = if (isCmd) message.substring(1) else message
        val prefix = if (isCmd) "/" else ""

        val matchedSocial = socialCommands.firstOrNull {
            clean.equals(it, ignoreCase = true) || clean.startsWith("$it ", ignoreCase = true)
        }

        if (matchedSocial != null) {
            val isPm = socialCommands.drop(4).any { clean.startsWith(it, ignoreCase = true) } && !clean.startsWith("r ", ignoreCase = true)
            val text = clean.substring(matchedSocial.length).trimStart()
            val target = if (isPm) text.split(" ").firstOrNull() ?: "" else ""
            val content = if (isPm) text.removePrefix(target).trimStart() else text

            return buildString {
                append(prefix)
                append(matchedSocial)
                if (target.isNotEmpty()) append(" $target")
                if (content.isNotEmpty()) append(" ${bypassString(content)}")
            }
        }

        if (isCmd) {
            return message
        }

        return bypassString(message)
    }

    private fun bypassString(str: String): String {
        return when (bypassMode.value) {
            0 -> cyrillicBypass(str)
            1 -> wideBypass(str)
            2 -> dotsBypass(str)
            3 -> smallCapsBypass(str)
            else -> cyrillicBypass(str)
        }
    }

    private val cyrillicMap = mapOf(
        'a' to 'а', 'A' to 'А', 'e' to 'е', 'E' to 'Е',
        'o' to 'о', 'O' to 'О', 'c' to 'с', 'C' to 'С',
        'p' to 'р', 'P' to 'Р', 'x' to 'х', 'X' to 'Х',
        'y' to 'у', 'Y' to 'У'
    )
    private fun cyrillicBypass(str: String) = str.map { cyrillicMap[it] ?: it }.joinToString("")

    private val wideMap by lazy {
        val normal = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val notNormal =
            "ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ" +
            "ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ" +
            "０１２３４５６７８９"
        normal.zip(notNormal).toMap()
    }
    private fun wideBypass(str: String): String {
        val s = if (LocationUtils.onHypixel) str.lowercase() else str
        return s.map { wideMap[it] ?: it }.joinToString("")
    }

    private fun dotsBypass(str: String): String = buildString {
        for (i in str.indices) {
            append(str[i])
            if (i < str.length - 1 && str[i] != ' ' && str[i + 1] != ' ') {
                append('.')
            }
        }
    }

    private val smallCapsHMap = mapOf(
        'b' to 'ʙ', 'g' to 'ɢ', 'h' to 'ʜ', 'j' to 'ᴊ', 'q' to 'ǫ', 'z' to 'ᴢ', 'x' to 'x'
    )
    private val smallCapsMap by lazy {
        "abcdefghijklmnopqrstuvwxyz".zip("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ").toMap()
    }
    private fun smallCapsBypass(str: String): String {
        val m = if (LocationUtils.onHypixel) smallCapsHMap else smallCapsMap
        return str.lowercase().map { m[it] ?: it }.joinToString("")
    }

    // --- Chat Emojis Implementation ---
    private fun replaceEmojis(message: String): String {
        var replaced = false
        val words = message.split(" ").map { word ->
            emojiReplacements[word]?.also { replaced = true } ?: word
        }
        return if (replaced) words.joinToString(" ") else message
    }

    private val emojiReplacements = mapOf(
        "<3" to "❤",
        "o/" to "( ﾟ◡ﾟ)/",
        ":star:" to "✮",
        ":yes:" to "✔",
        ":no:" to "✖",
        ":java:" to "☕",
        ":arrow:" to "➜",
        ":shrug:" to "¯\\_(ツ)_/¯",
        ":tableflip:" to "(╯°□°）╯︵ ┻━┻",
        ":totem:" to "☉_☉",
        ":typing:" to "✎...",
        ":maths:" to "√(π+x)=L",
        ":snail:" to "@'-'",
        ":thinking:" to "(0.o?)",
        ":gimme:" to "༼つ◕_◕༽つ",
        ":wizard:" to "(' - ')⊃━☆ﾟ.*･｡ﾟ",
        ":pvp:" to "⚔",
        ":peace:" to "✌",
        ":puffer:" to "<('O')>",
        "h/" to "ヽ(^◇^*)/",
        ":sloth:" to "(・⊝・)",
        ":dog:" to "(ᵔᴥᵔ)",
        ":dj:" to "ヽ(⌐■_■)ノ♬",
        ":yey:" to "ヽ (◕◡◕) ﾉ",
        ":snow:" to "☃",
        ":dab:" to "<o/",
        ":cat:" to "= ＾● ⋏ ●＾ =",
        ":cute:" to "(✿◠‿◠)",
        ":skull:" to "☠",
        ":sob:" to ".ᐟ(つ╥﹏╥)つ",
        ":joy:" to "৻(≧ᗜ≦৻)"
    )

    // --- Chat Replacements Implementation ---
    private sealed interface ReceivedMessageAction
    private data object KeepMessage : ReceivedMessageAction
    private data object HideMessage : ReceivedMessageAction
    private data class ReplaceMessage(val message: String, val prefix: String = "") : ReceivedMessageAction
    private data class MessageReplacement(val pattern: Regex, val replacement: String)

    private fun resolveReceivedMessage(message: String, plainMessage: String): ReceivedMessageAction {
        if (shouldHideNonRankInvite(plainMessage)) return HideMessage

        if (cleanDungeonMessages.value) {
            resolveDungeonMessage(plainMessage)?.let { return it }
        }

        if (cleanPartyFinderMessages.value) {
            resolvePartyFinderMessage(plainMessage)?.let { return it }
        }

        if (hideUselessMessages.value) {
            resolveUselessMessage(message, plainMessage)?.let { return it }
        }

        return KeepMessage
    }

    private fun shouldHideNonRankInvite(message: String): Boolean {
        if (!hideNonRankInvites.value) return false
        val inviter = nonRankInviteRegex.find(message)?.groupValues?.get(1) ?: return false
        return '[' !in inviter
    }

    private fun resolveDungeonMessage(message: String): ReceivedMessageAction? {
        for (replacement in dungeonMessageReplacements) {
            val match = replacement.pattern.find(message) ?: continue
            val replacedMessage = match.value.replace(replacement.pattern, replacement.replacement)
            return ReplaceMessage(replacedMessage, "§dDungeon§f >")
        }

        if (message in hiddenDungeonRareDrops) return HideMessage
        if (hiddenDungeonMessagePatterns.any { it.containsMatchIn(message) }) return HideMessage
        return null
    }

    private fun resolvePartyFinderMessage(message: String): ReceivedMessageAction? {
        partyFinderMessageReplacements[message]?.let { return ReplaceMessage(it) }

        pfClassChangeRegex.find(message)?.let { match ->
            val (player, clazz, level) = match.destructured
            return ReplaceMessage("§dPF > §b$player §echanged to §b$clazz $level§e!")
        }

        pfJoinRegex.find(message)?.let { match ->
            val (player, clazz, level) = match.destructured
            return ReplaceMessage("§dPF > §b$player §ejoined the group! (§b$clazz $level§e)")
        }

        return null
    }

    private fun resolveUselessMessage(message: String, plainMessage: String): ReceivedMessageAction? {
        if (formattedUselessMessagePatterns.any { it.containsMatchIn(message) }) return HideMessage
        if (plainUselessMessagePatterns.any { it.containsMatchIn(plainMessage) }) return HideMessage

        if (discordWarningRegex.containsMatchIn(message)) {
            return ReplaceMessage(message.replace(discordWarningRegex, "").trimEnd())
        }

        if (microsoftWarningRegex.containsMatchIn(plainMessage)) return HideMessage
        if (message.isBlank()) return HideMessage
        return null
    }

    private val hiddenDungeonRareDrops = setOf(
        "RARE DROP! Machine Gun Shortbow",
        "RARE DROP! Beating Heart",
        "RARE DROP! Zombie Commander Boots",
        "RARE DROP! Skeleton Lord Chestplate",
        "RARE DROP! Earth Shard"
    )

    private val hiddenDungeonMessagePatterns = listOf(
        Regex("^There are blocks in the way!"),
        Regex("^You cannot use abilities in this room!"),
        Regex("^You haven't claimed your Spooky Rewards yet!"),
        Regex("^Talk to the Spooky Man in the Hub!"),
        Regex("^Profile ID:"),
        Regex("^You are playing on profile:"),
        Regex("RARE REWARD! (.+) found a (.+) in their (.+) Chest!"),
        Regex("^You are not allowed to use Potion Effects while in Dungeon.*stored\\."),
        Regex("^\\[Healer].*"),
        Regex("^\\[Mage].*"),
        Regex("^\\[Berserk].*"),
        Regex("^\\[Archer].*"),
        Regex("^\\[Tank].*"),
        Regex("^RIGHT CLICK on a WITHER door.*"),
        Regex("^(.+) has obtained Superboom TNT(.+)"),
        Regex("^RIGHT CLICK on the BLOOD DOOR.*"),
        Regex("^ {5}Granted you .+\\.$"),
        Regex("^ {5}Also granted you .+ & .+\\."),
        Regex("^A Blessing of .+ was picked up!$"),
        Regex("^.+ has obtained Blessing of .+!"),
        Regex("^has obtained Revive Stone!"),
        Regex("^This menu is disabled here!"),
        Regex("^The Lost Adventurer used Dragon's Breath on you!"),
        Regex("^A Crypt Wither Skull exploded, hitting you for (.+) damage."),
        Regex("^(.+) has obtained Premium Flesh!")
    )

    private val dungeonMessageReplacements = listOf(
        MessageReplacement(Regex("^Your (.+) stats are doubled because you are the only player using this class!$"), "§7Recieved double class stats. (No dupe)"),
        MessageReplacement(Regex("^Starting in (\\d) (.+)"), "§aStarting in $1..."),
        MessageReplacement(Regex("^(.+) Milestone (.+): You have (.+)$"), "§6Milestone $2"),
        MessageReplacement(Regex("^DUNGEON BUFF! (.+) found a Blessing of (Power|Life|Stone|Wisdom) (.+)! ?(.+)?$"), "§7$2 $3"),
        MessageReplacement(Regex("^DUNGEON BUFF! A Blessing of (Power|Life|Stone|Wisdom|Time) (.+) was found! (.+)$"), "§7$1 $2"),
        MessageReplacement(Regex("^A Blessing of (Power|Life|Stone|Wisdom) (.+) was found! (.+)$"), "§7$1"),
        MessageReplacement(Regex("^ESSENCE! (.+) found x10 (Ice|Spider|Gold|Diamond) Essence!$"), "§b$2 Essence."),
        MessageReplacement(Regex("^(.+) found a Wither Essence! Everyone gains an extra essence!$"), "§bWither Essence."),
        MessageReplacement(Regex("^You hear the sound of something opening\\.\\.\\.$"), "§7You used a lever."),
        MessageReplacement(Regex("^This lever has already been used\\.$"), "§cThis lever has been used."),
        MessageReplacement(Regex("^This chest has already been searched!$"), "§cThis chest has been searched!"),
        MessageReplacement(Regex("^That chest is locked!$"), "§cThat chest is locked!"),
        MessageReplacement(Regex("^(.+) is ready to use! Press DROP to activate it!$"), "§9Ultimate Available"),
        MessageReplacement(Regex("^(.+) has obtained Wither Key!$"), "§eWither Key picked up!"),
        MessageReplacement(Regex("^A Wither Key was picked up!$"), "§eWither Key picked up!"),
        MessageReplacement(Regex("^(.+) has obtained Blood Key!$"), "§cBlood Key picked up!"),
        MessageReplacement(Regex("^A Blood Key was picked up!$"), "§cBlood Key picked up"),
        MessageReplacement(Regex("^◕ You picked up a (.+) from (.+) healing you for (.+) and granting you \\+(.+)% (.+) for 10 seconds.$"), "§e$1 picked up §7(§e+§c$3§7)§e!")
    )

    private val formattedUselessMessagePatterns = listOf(
        Regex("§f +§r§7You are now §r§.Event Level §r§.*§r§7!"),
        Regex("§f +§r§7You earned §r§.* Event Silver§r§7!"),
        Regex("§f +§r§.§k#§r§. LEVEL UP! §r§.§k#"),
        Regex("§aYou earned §r§2.* GEXP (§r§a\\+ §r§.* Event EXP )?§r§afrom playing SkyBlock!"),
        Regex("^§.* §r§7has been promoted to §r§7\\[.*§r§7] §r§.*§r§7!"),
        Regex("^§7Your §r§aRabbit Barn §r§7capacity has been increased to §r§a.* Rabbits§r§7!"),
        Regex("^§7You will now produce §r§6.* Chocolate §r§7per click!"),
        Regex("^§7You upgraded to §r§d.*?§r§7!"),
        Regex("^§d§lHOPPITY'S HUNT §r§dA §r§.Chocolate (.+) Egg §r§dhas appeared!"),
        Regex("^§6§k§lA§r §c§lFIRE SALE §r§6§k§lA(?:\\n|.)*"),
        Regex("^§c♨ §eFire Sales for .* §eare starting soon!"),
        Regex("^§c\\s*♨ .* (?:Skin|Rune|Dye) §e(?:for a limited time )?\\(.* §eleft\\)(?:§c|!)"),
        Regex("^§c♨ §eVisit the Community Shop in the next §c.* §eto grab yours! §a§l\\[WARP]"),
        Regex("^§c♨ §eA Fire Sale for .* §eis starting soon!"),
        Regex("^§c♨ §r§eFire Sales? for .* §r§eended!"),
        Regex("^§c {3}♨ §eAnd \\d+ more!"),
        Regex("^§.§l\\+(.*) Kill Combo (.*)"),
        Regex("^§cYour Kill Combo has expired! You reached a (.*) Kill Combo!"),
        Regex("^§6§l\\+50 Kill Combo"),
        Regex("^§aYou are playing on profile: §e"),
        Regex("^§8Profile ID: "),
        Regex("§6§lRARE REWARD! (.*) §r§efound a (.*) §r§ein their (.*) Chest§r§e!")
    )

    private val plainUselessMessagePatterns = listOf(
        Regex("Sending to server .+"),
        Regex("Rabbit .+"),
        Regex("Wait a moment..."),
        Regex("DUPLICATE RABBIT! .+"),
        Regex("Putting item in escrow..."),
        Regex("Setting up the auction..."),
        Regex("You are not allowed to use Potion Effects while in Dungeon, therefore all active effects have been paused and stored. They will be restored when you leave Dungeon!"),
        Regex("The BLOOD DOOR has been opened!"),
        Regex("A shiver runs down your spine..."),
        Regex("Moved .+ from your Sacks to your inventory."),
        Regex(" ❣ .+"),
        Regex(" ☠ .+"),
        Regex("^ESSENCE! .+"),
        Regex("◕ .+"),
        Regex("Warping..."),
        Regex(" {2}➤ .+"),
        Regex("✦ .+"),
        Regex("\\[SKULL] .+"),
        Regex("Someone else is currently reviving that player!"),
        Regex("Queueing your party..."),
        Regex("De-listing your group..."),
        Regex("Only the instance creator can re-queue!"),
        Regex("Depositing coins..."),
        Regex("Withdrawing coins..."),
        Regex("You can only use this item inside dungeons!"),
        Regex("The dungeon hasn't started yet!"),
        Regex("You have teleported to .+"),
        Regex("Attempting to add you to the party..."),
        Regex("You're already in this channel!"),
        Regex("You bought .+"),
        Regex("You sold .+"),
        Regex("This menu is currently occupied!"),
        Regex("You may only use this command after 4s on the server!"),
        Regex("The .+ you for .+ damage."),
        Regex("You hear something open..."),
        Regex("You found a Secret Redstone Key!"),
        Regex("Be careful! Using Ender Pearls on this island will anger nearby Endermen!"),
        Regex("\\[Bazaar] .+"),
        Regex("Putting coins in escrow..."),
        Regex("Strike using the .+ attunement on your dagger!"),
        Regex("Your hit was reduced by Hellion Shield!"),
        Regex("Processing purchase..."),
        Regex("Claiming BIN auction..."),
        Regex("Visit the Auction House to collect your item!"),
        Regex("RARE! .+"),
        Regex("SWEET! .+"),
        Regex("COMMON! .+"),
        Regex("ALLOWANCE! You earned .+ coins!"),
        Regex("You are sending commands too fast! Please slow down."),
        Regex("You haven't claimed your Holidays Rewards yet!"),
        Regex("Talk to the Gingerbread Man in the Hub!"),
        Regex(".+ FIRE SALE .+"),
        Regex("♨ Selling multiple items for a limited time!"),
        Regex("♨ .+ \\(.+ left\\)"),
        Regex("♨ \\[WARP] To Elizabeth in the next .+ to grab yours!"),
        Regex("Your bone plating reduced the damage you took by .+!"),
        Regex("Warping you to your SkyBlock island..."),
        Regex("You earned .+ Event EXP from playing SkyBlock!"),
        Regex("Watchdog has banned .+ players in the last 7 days."),
        Regex("Error initializing players: undefined"),
        Regex("Goldor's TNT Trap hit you for .+ true damage."),
        Regex("This Terminal doesn't seem to be responsive at the moment."),
        Regex("Whow! Slow down there!"),
        Regex("⚠ Storm is enraged! ⚠"),
        Regex("Giga Lightning.+"),
        Regex("Necron's Nuclear Frenzy hit you for .+ damage."),
        Regex("Woah slow down, you're doing that too fast!"),
        Regex("Command Failed: This command is on cooldown! Try again in about a second!"),
        Regex("Someone has already activated this lever!"),
        Regex("Goldor's Greatsword hit you for .+ damage."),
        Regex("A mystical force in this room prevents you from using that ability!"),
        Regex("The Frozen Adventurer used Ice Spray on you!"),
        Regex("It isn't your turn!"),
        Regex("Don't move diagonally! Bad!"),
        Regex("Oops! You stepped on the wrong block!"),
        Regex("Used Ragnarok!"),
        Regex("Your Auto Recombobulator recombobulated .+"),
        Regex("Blacklisted modifications are a bannable offense!"),
        Regex("\\[WATCHDOG ANNOUNCEMENT]"),
        Regex("Staff have banned an additional .+"),
        Regex("Your Ultimate is currently on cooldown for .+ more seconds."),
        Regex("You hear the sound of something opening..."),
        Regex("You sold .+ x.* for .+"),
        Regex("You don't have enough space in your inventory to pick up this item!.*"),
        Regex("Inventory full\\? Don't forget to check out your Storage inside the SkyBlock Menu!"),
        Regex("Your Berserk ULTIMATE Ragnarok is now available!"),
        Regex("This item's ability is temporarily disabled!"),
        Regex("Throwing Axe is now available!"),
        Regex("Used Throwing Axe!"),
        Regex("Guided Sheep is now available!"),
        Regex("\\[STATUE].+"),
        Regex("PUZZLE SOLVED!.+"),
        Regex("DUNGEON BUFF! .+"),
        Regex("You summoned your.+"),
        Regex("\\[Sacks] .+ item.+"),
        Regex("The .+ Trap hit you for .+ damage!"),
        Regex("Healer Milestone.+"),
        Regex("Archer Milestone.+"),
        Regex("Mage Milestone.+"),
        Regex("Tank Milestone.+"),
        Regex("Berserk Milestone.+"),
        Regex("Welcome to Hypixel SkyBlock!"),
        Regex("Latest update: SkyBlock .+"),
        Regex(".+ is now ready!"),
        Regex("Queuing... .+"),
        Regex(".+ Milestone .+:.+ "),
        Regex("RIGHT CLICK on .+ to open it. .+"),
        Regex(".+ Mort: .+"),
        Regex("Your .+ hit .+ for [\\d,.]+ damage."),
        Regex("You do not have enough mana to do this!"),
        Regex(".+Kill Combo+"),
        Regex("Your .+ healed your entire team for .+"),
        Regex(".+ healed you for .+ health!"),
        Regex("You earned .+ GEXP .*"),
        Regex(".+ unlocked .+ Essence!"),
        Regex("This item is on cooldown.+"),
        Regex("This ability is on cooldown.+"),
        Regex("You do not have the key for this door!"),
        Regex("The Stormy .+ struck you for .+ damage!"),
        Regex("Please wait a few seconds between refreshing!"),
        Regex("You cannot move the silverfish in that direction!"),
        Regex("You cannot hit the silverfish while it's moving!"),
        Regex("Your Kill Combo has expired! You reached a .+ Kill Combo!"),
        Regex("Your active Potion Effects have been paused and stored. They will be restored when you leave Dungeons! You are not allowed to use existing Potion Effects while in Dungeons."),
        Regex(".+ has obtained Blood Key!"),
        Regex("The Flamethrower hit you for .+ damage!"),
        Regex(".+ found a Wither Essence! Everyone gains an extra essence!"),
        Regex(".+ is ready to use! Press DROP to activate it!"),
        Regex("This creature is immune to this kind of magic!"),
        Regex("FISHING FESTIVAL The festival is now underway! Break out your fishing rods and watch out for sharks!"),
        Regex("Starting in .+"),
        Regex("Your .+ stats are doubled because you are the only player using this class!"),
        Regex("\\[Healer] .+"),
        Regex("\\[Tank] .+"),
        Regex("\\[Archer] .+"),
        Regex("\\[Berserk] .+"),
        Regex("\\[Mage] .+"),
        Regex("BONUS! Temporarily earn .+ more skill experience!"),
        Regex("\n➔ Welcome to the Prototype Lobby\nAll games in this lobby are currently in development.\nClick here to leave feedback! ➤ https://hypixel.net/PTL\n"),
        Regex("You received .+ for killing .+"),
        Regex("SALT .+"),
        Regex("You caught a .+ Shard!"),
        Regex("You caught .+ Shards!"),
        Regex("DAVID: .+"),
        Regex("Profile ID: .+"),
        Regex("BUFF! .+"),
        Regex(".+ has obtained Wither Key!"),
        Regex(".+ opened a WITHER door!"),
        Regex("Used Healing Circle!"),
        Regex("Healing Circle is now available!"),
        Regex("\\[BOSS] .+"),
        Regex(" {2}ൠ .+"),
        Regex("Your Garden is no longer infested and your ☘ Farming Fortune has returned to normal!"),
        Regex("Party Finder > Your dungeon group is full! Click here to warp to the dungeon!"),
        Regex("You formed a tether with .+!"),
        Regex("Couldn't warp you! Try again later. \\(PLAYER_TRANSFER_COOLDOWN\\)"),
        Regex("Your .+ healed your entire team for .+ health and shielded them for .+!"),
        Regex(".+ activated a terminal! .+"),
        Regex(".+ activated a lever! .+"),
        Regex(".+ completed a device! .+"),
        Regex("The Core entrance is opening!"),
        Regex("The gate has been destroyed!"),
        Regex("Your .+ is now available!"),
        Regex("UNIVERSAL INCOME: You gained .+ Coins."),
        Regex("A total of .+ Coins have been distributed across .+ players."),
        Regex("The Energy Laser is charging up!"),
        Regex("That item cannot be sold!"),
        Regex(".+ picked up an Energy Crystal!"),
        Regex(".+ Energy Crystals are now active!"),
        Regex("Not enough mana! Creeper Veil De-activated!"),
        Regex("⚠ .+"),
        Regex("Creeper Veil Activated!"),
        Regex("Creeper Veil De-activated!"),
        Regex("The gate will open in 5 seconds!"),
        Regex("Claiming upgrade..."),
        Regex("Starting profile upgrade..."),
        Regex("Starting account upgrade..."),
        Regex("You claimed the .+ upgrade!"),
        Regex("You started the .+ upgrade!"),
        Regex("SPOOKY FESTIVAL .+"),
        Regex("SALT: .+"),
        Regex("Woah! Slow down there!"),
        Regex("CHARM .+"),
        Regex("You may only use this menu after 4s on the server!"),
        Regex("Evacuating to Hub..."),
        Regex(" >>> \\[MVP\\+\\+] .+ slid into the lobby! <<<"),
        Regex("\\[MVP\\+] .+ slid into the lobby!"),
        Regex("You are not allowed to use that command as a spectator!"),
        Regex("Boomer .+"),
        Regex("Your .+ saved your life!"),
        Regex("Your .+ leveled up to level .+!"),
        Regex("Warning! The instance will .+"),
        Regex("Your .+ saved you from certain death!"),
        Regex("Second Wind Activated! Your Spirit Mask saved your life!"),
        Regex("Your Tuning Points were auto-assigned as convenience!"),
        Regex("A .+ was picked up!"),
        Regex("^Bonzo's .+"),
        Regex("^Stormy .+"),
        Regex("^Goldor's .+"),
        Regex("^Necron's .+"),
        Regex("^Maxor's .+"),
        Regex("^Storm's .+"),
        Regex("^Livid's .+"),
        Regex("^Sadan's .+"),
        Regex("A Crypt Wither Skull exploded, hitting you for .+"),
        Regex(" {5}Granted you .+ ❁ Strength."),
        Regex("A mystical force prevents you digging in this room!"),
        Regex("Mute silenced you!"),
        Regex("A Event: New Year's Celebration! A"),
        Regex("A Everyone is having a party in the Village!"),
        Regex("A CLICK HERE to get your SPECIAL new year cake!"),
        Regex("RARE DROP! .+"),
        Regex("HOPPITY'S HUNT You found a .+"),
        Regex("Hoppity's Hunt has begun! Help Hoppity find his Chocolate Rabbit Eggs across SkyBlock each day during the Spring!"),
        Regex("HOPPITY'S HUNT"),
        Regex("You found a journal .+"),
        Regex("You have already collected this .+ Try again when it respawns!"),
        Regex("You have been re-queued!"),
        Regex("A Prince falls. +1 Bonus Score"),
        Regex("\\[NPC] .+"),
        Regex("\\[SkyBlockAPI] Loaded some data from pv! \\(hover\\)"),
        Regex(" {2}Clicking sketchy links can result in your account"),
        Regex(" {2}being stolen!"),
        Regex(" {2}Link looks suspicious\\? - Don't click it!"),
        Regex("✆ .+"),
        Regex(" ☺ .+"),
        Regex("You used a .+!"),
        Regex("Please wait..."),
        Regex("Submitting Report..."),
        Regex("A mystical force prevents you digging there!"),
        Regex("A mystical force prevents you from digging that block!"),
        Regex("The Time Tower is already active!"),
        Regex("Autopet equipped your .+"),
        Regex("\\[CROWD] .+"),
        Regex("You cannot drop items yet!"),
        Regex("There are no reachable enemies nearby!"),
        Regex("The wind has changed direction!"),
        Regex("Try switching servers to regain Mining Fatigue..."),
        Regex(" +Granted you .+"),
        Regex("You cannot use abilities in this room!"),
        Regex("""^Unknown command\. Type "/help" for help\. \('.+'\)$"""),
        Regex(".+ joined the lobby!"),
        Regex("You already tipped everyone that has boosters active, so there isn't anybody to be tipped right now!"),
        Regex("""^You tipped (\d+) players? in (\d+) (?:different )?games?!$"""),
        Regex("""^Cannot join SkyBlock for a moment! \(Queue join in cooldown\)$"""),
        Regex("""No one has a network booster active right now! Try again later.""")
    )

    private val pfClassChangeRegex = Regex("""^Party Finder > (.+?) set their class to (\w+) Level (\d+)!$""")
    private val pfJoinRegex = Regex("""^Party Finder > (.+?) joined the dungeon group! \((\w+) Level (\d+)\)$""")

    private val partyFinderMessageReplacements = mapOf(
        "Party Finder > Your group has been de-listed!" to "§dPF > §aParty Delisted.",
        "Party Finder > Your party has been queued in the party finder!" to "§dPF > §aParty Queued.",
        "Party Finder > Your group has been removed from the party finder!" to "§dPF > §cParty Removed.",
        "Refreshing..." to "§dPF > §aRefreshing.",
        "Party Finder > You are already in a party!" to "§dPF > §cAlready In Party.",
        "Party Finder > Your party has been queued in the dungeon finder!" to "§dPF > §aParty Queued.",
        "Party Finder > This group has been de-listed." to "§dPF > §cGroup Delisted.",
        "Party Finder > This group is full and has been de-listed!" to "§dPF > §cGroup Delisted.",
        "Party Finder > You are already in a group!" to "§dPF > §cAlready In Group.",
        "Party Finder > This group doesn't exist!" to "§dPF > §cGroup Doesn't Exist.",
        "Party Finder > This group is full!" to "§dPF > §cGroup Full."
    )

    private val discordWarningRegex = Regex("""Please be mindful of Discord links in chat as they may pose a security risk""")
    private val nonRankInviteRegex = Regex("^(.+?) has invited you to join their party!\\nYou have 60 seconds to accept\\. Click here to join!.*$")
    private val microsoftWarningRegex = Regex(
        """-----------------------------------------------------
You should NEVER enter your Microsoft account details anywhere but on official Microsoft services!

External links from untrusted sources should be avoided.
-----------------------------------------------------"""
    )
}
