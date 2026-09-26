package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.ChatFeatures;
import com.github.noamm9.nexclusive.interfaces.INexChatComponent;
import com.github.noamm9.nexclusive.interfaces.INexGuiMessage;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class MixinChatComponent implements INexChatComponent {
    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    @Shadow
    public abstract void addClientSystemMessage(Component message);

    @Shadow
    public abstract void refreshTrimmedMessages();

    @Shadow
    private int chatScrollbarPos;

    @Unique
    private int nexNextId;

    @Override
    public void nexAdd(Component message, int id) {
        if (id != 0) {
            trimmedMessages.removeIf(msg -> ((INexGuiMessage) (Object) msg).nexGetId() == id);
            allMessages.removeIf(msg -> ((INexGuiMessage) (Object) msg).nexGetId() == id);
        }

        nexNextId = id;
        addClientSystemMessage(message);
        nexNextId = 0;
    }

    @Override
    public boolean nexRemoveLines(int id, String text) {
        boolean removed = false;
        var it = allMessages.listIterator();
        while (it.hasNext()) {
            var msg = it.next();
            int msgId = ((INexGuiMessage) (Object) msg).nexGetId();
            String clean = msg.content().getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            if (msgId == id || clean.equals(text)) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            refreshTrimmedMessages();
        }
        return removed;
    }

    @Override
    public int nexGetScrollPos() {
        return chatScrollbarPos;
    }

    @Override
    public void nexSetScrollPos(int pos) {
        this.chatScrollbarPos = pos;
    }

    @Override
    public List<GuiMessage> nexGetAllMessages() {
        return allMessages;
    }

    @Override
    public List<GuiMessage.Line> nexGetTrimmedMessages() {
        return trimmedMessages;
    }

    @ModifyArg(
            method = "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;addFirst(Ljava/lang/Object;)V"
            ),
            index = 0
    )
    private Object onAddVisibleLine(Object line) {
        if (nexNextId != 0 && line instanceof INexGuiMessage) {
            ((INexGuiMessage) line).nexSetId(nexNextId);
        }
        return line;
    }

    @Inject(
            method = "addMessageToQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
            at = @At("TAIL")
    )
    private void onAddMessageAfterNewLine(GuiMessage message, CallbackInfo ci) {
        if (nexNextId != 0 && !allMessages.isEmpty()) {
            ((INexGuiMessage) (Object) allMessages.getFirst()).nexSetId(nexNextId);
        }
    }

    @ModifyVariable(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private ChatComponent.DisplayMode renderFocused(ChatComponent.DisplayMode mode) {
        return ChatFeatures.displayMode(mode);
    }

    @ModifyExpressionValue(
            method = {
                    "getHeight()I",
                    "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"
            )
    )
    private boolean focusWhenPeeking(boolean original) {
        return original || ChatFeatures.isPeeking();
    }

    @WrapOperation(
            method = "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;scrollChat(I)V"
            )
    )
    private void disableAutoScroll(ChatComponent instance, int amount, Operation<Void> original) {
        if (ChatFeatures.disablesAutoScroll()) return;
        original.call(instance, amount);
    }

    @ModifyExpressionValue(
            method = {
                    "addMessageToDisplayQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V",
                    "addMessageToQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;size()I"
            ),
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Ljava/util/List;addFirst(Ljava/lang/Object;)V"),
                    to = @At(value = "INVOKE", target = "Ljava/util/List;removeLast()Ljava/lang/Object;")
            ),
            require = 2,
            expect = 2
    )
    private int applyInfiniteChatLimit(int size) {
        return ChatFeatures.keepsAllChatMessages() ? 0 : size;
    }

    @Inject(
            method = "clearMessages(Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void keepChatHistory(boolean clearRecentChat, CallbackInfo ci) {
        if (clearRecentChat && ChatFeatures.keepsChatHistory()) {
            ci.cancel();
        }
    }
}
