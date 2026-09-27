package com.github.noamm9.nexclusive.features.impl.nexclusive.chat

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.interfaces.IChatComponent
import com.github.noamm9.nexclusive.interfaces.INexChatComponent
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.unformattedText

object ChatCopy {
    fun copyHovered(withCodes: Boolean): Boolean {
        val chatHud = (mc.gui.chat as? IChatComponent) ?: return false
        val idx = chatHud.lineIndex.toInt()
        val ext = (mc.gui.chat as? INexChatComponent) ?: return false
        val visible = ext.nexGetTrimmedMessages()
        if (idx !in visible.indices) return false

        var fullIndex = - 1
        for (i in visible.indices) {
            if (visible[i].endOfEntry) fullIndex ++
            if (i == idx) break
        }

        val all = ext.nexGetAllMessages()
        val msg = all.getOrNull(fullIndex) ?: return false
        val copied = if (withCodes) msg.content.formattedText else msg.content.unformattedText
        if (copied.isBlank()) return false

        mc.keyboardHandler.clipboard = copied
        NotificationManager.push("Chat Features", "Copied message to clipboard!")
        return true
    }
}
