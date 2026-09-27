package com.github.noamm9.nexclusive.features.impl.nexclusive.chat

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.nexclusive.interfaces.INexChatComponent
import net.minecraft.network.chat.Component

object ChatCompactor {
    private val history = mutableMapOf<String, Pair<Int, Long>>()

    fun tryCompact(component: Component, plainText: String, compactTimeSec: Int): Boolean {
        val msg = plainText.trim()
        if (msg.isEmpty() || msg.all { it == '-' || it == '=' || it == '▬' }) return false

        val data = history[msg]
        val lastTime = data?.second
        val id = msg.hashCode()
        val now = System.currentTimeMillis()

        if (lastTime != null && now - lastTime < compactTimeSec * 1000L) {
            val count = data.first + 1
            mc.execute {
                val ext = mc.gui.chat as? INexChatComponent ?: return@execute
                val scroll = ext.nexGetScrollPos()
                ext.nexRemoveLines(id, msg)
                ext.nexAdd(component.copy().append(Component.literal(" §7($count)")), id)
                history[msg] = count to now
                ext.nexSetScrollPos(scroll)
            }
            return true
        }

        history[msg] = 1 to now
        return false
    }

    fun clear() = history.clear()
}
