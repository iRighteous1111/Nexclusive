package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.OffTickTeleport;
import com.github.noamm9.nexclusive.interfaces.INexConnection;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Connection.class, priority = 0)
public abstract class MixinLowestPriorityConnection implements INexConnection {

    @Shadow
    protected abstract void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush);

    @Inject(method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), order = Integer.MAX_VALUE, cancellable = true)
    void doSendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        OffTickTeleport ott = OffTickTeleport.INSTANCE;
        if (ott.getEnabled() && ott.getPacketProcessing()) {
            ott.onSend(packet, ci);
        }
    }

    @Override
    public void sendPacketImmediately(Packet<?> packet) {
        this.sendPacket(packet, null, true);
    }
}
