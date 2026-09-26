package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.interfaces.INexGuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiMessage.Line.class)
public abstract class MixinGuiMessageLine implements INexGuiMessage {
    @Unique
    private int nexId;

    @Override
    public int nexGetId() {
        return nexId;
    }

    @Override
    public void nexSetId(int id) {
        this.nexId = id;
    }
}
