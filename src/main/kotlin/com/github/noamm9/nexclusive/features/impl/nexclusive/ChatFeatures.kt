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
import com.github.noamm9.nexclusive.features.impl.nexclusive.chat.*
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

object ChatFeatures: Feature("Various chat related tweaks.", name = "Chat Features", jsonName = "ChatFeatures") {
    private val compactChat by ToggleSetting("Compact Chat", true)
        .section("Compact Chat")
        .withDescription("Compacts duplicate messages into (xN).")
    private val compactTime by SliderSetting("Compact Time", 60, 5, 120, 1)
        .withDescription("Time in seconds to compact identical messages.")

    private val cleanDungeonMessages by ToggleSetting("Cleaner Dungeons", true)
        .section("Chat Replacements")
        .withDescription("Shortens and cleans up dungeon chat messages.")
    private val cleanPartyFinderMessages by ToggleSetting("Cleaner PF", true)
        .withDescription("Shortens Party Finder announcements.")
    private val hideUselessMessages by ToggleSetting("Hide Useless Messages", true)
        .withDescription("Hides useless spam and notification messages.")
    private val hideNonRankInvites by ToggleSetting("Hide Non-Rank Invites", true)
        .withDescription("Hides party invites from non-ranked players.")

    private val chatBypass by ToggleSetting("Chat Bypass", false)
        .section("Chat Bypass")
        .withDescription("Bypasses chat filters on Hypixel.")
    private val bypassMode by DropdownSetting("Bypass Mode", 0, listOf("Cyrillic", "Wide", "Dots", "SmallCaps"))
        .withDescription("Chat filter bypass mode.")

    private val chatPeek by ToggleSetting("Chat Peek", false)
        .section("Chat Peek")
        .withDescription("Shows chat when holding the peek key.")
    private val peekKey by KeybindSetting("Peek Key", GLFW.GLFW_KEY_Z)
        .withDescription("Key to peek at chat.")

    private val chatEmojis by ToggleSetting("Chat Emojis", true)
        .section("Chat Emojis")
        .withDescription("Converts emote codes like <3, :cat:, :dab:, :skull: into symbols.")

    private val copyChat by ToggleSetting("Copy Chat", true)
        .section("Copy Chat")
        .withDescription("Right click or middle click a chat message to copy it.")
    private val copyButton by DropdownSetting("Copy Button", 0, listOf("Right Click", "Middle Click"))
        .withDescription("Mouse button used to copy messages.")
    private val copyWithCodes by ToggleSetting("Copy Color Codes", false)
        .withDescription("Whether to copy formatting codes (§).")

    private val disableAutoScroll by ToggleSetting("Disable Auto Scroll", true)
        .section("Chat Tweaks")
        .withDescription("Prevents chat from scrolling down when scrolled up.")
    private val infiniteChatLimit by ToggleSetting("Infinite Chat Limit", true)
        .withDescription("Keeps all chat history instead of trimming at 100 messages.")
    private val keepChatHistory by ToggleSetting("Keep Chat History", true)
        .withDescription("Keeps chat history when changing servers or worlds.")

    private var wasPeeking = false

    @JvmStatic fun isPeeking(): Boolean = enabled && chatPeek.value && peekKey.isDown()
    @JvmStatic fun displayMode(mode: ChatComponent.DisplayMode): ChatComponent.DisplayMode =
        if (isPeeking()) ChatComponent.DisplayMode.FOREGROUND else mode
    @JvmStatic fun disablesAutoScroll(): Boolean = enabled && disableAutoScroll.value
    @JvmStatic fun keepsAllChatMessages(): Boolean = enabled && infiniteChatLimit.value
    @JvmStatic fun keepsChatHistory(): Boolean = enabled && keepChatHistory.value

    override fun init() {
        register<TickEvent.Start> {
            val peeking = isPeeking()
            if (wasPeeking && ! peeking) mc.gui.chat.scrollChat(- 100000)
            wasPeeking = peeking
        }

        register<ChatMessageEvent>(priority = EventPriority.LOWEST) {
            if (! enabled) return@register

            when (val action = ChatCleaner.resolve(
                event.formattedText,
                event.unformattedText,
                cleanDungeonMessages.value,
                cleanPartyFinderMessages.value,
                hideUselessMessages.value,
                hideNonRankInvites.value
            )) {
                is ChatCleaner.MessageAction.Hide -> {
                    event.cancel()
                    return@register
                }
                is ChatCleaner.MessageAction.Replace -> {
                    event.cancel()
                    val replaced = if (action.prefix.isEmpty()) Component.literal(action.message)
                    else Component.literal("${action.prefix} ${action.message}")

                    if (compactChat.value && ChatCompactor.tryCompact(replaced, replaced.string.removeFormatting(), compactTime.value)) {
                        return@register
                    }

                    mc.execute { mc.gui.chat.addClientSystemMessage(replaced) }
                    return@register
                }
                ChatCleaner.MessageAction.Keep -> Unit
            }

            if (compactChat.value && ChatCompactor.tryCompact(event.component, event.unformattedText, compactTime.value)) {
                event.cancel()
            }
        }

        register<MessageSentEvent> {
            if (! enabled) return@register
            var text = event.message
            if (chatEmojis.value) text = ChatEmojis.replaceEmojis(text)
            if (chatBypass.value) text = ChatBypass.bypass(text, bypassMode.value)
            event.message = text
        }

        register<MouseClickEvent> {
            if (! enabled || ! copyChat.value || mc.screen !is ChatScreen || event.action != GLFW.GLFW_PRESS) return@register
            val target = if (copyButton.value == 0) GLFW.GLFW_MOUSE_BUTTON_RIGHT else GLFW.GLFW_MOUSE_BUTTON_MIDDLE
            if (event.button == target && ChatCopy.copyHovered(copyWithCodes.value)) {
                event.cancel()
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        ChatCompactor.clear()
        wasPeeking = false
    }
}
