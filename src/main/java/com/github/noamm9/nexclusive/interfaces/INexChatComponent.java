package com.github.noamm9.nexclusive.interfaces;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.List;

public interface INexChatComponent {
    void nexAdd(Component message, int id);
    boolean nexRemoveLines(int id, String text);
    int nexGetScrollPos();
    void nexSetScrollPos(int pos);
    List<GuiMessage> nexGetAllMessages();
    List<GuiMessage.Line> nexGetTrimmedMessages();
}
