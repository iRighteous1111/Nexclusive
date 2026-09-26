package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.OffTickTeleport;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Connection.class, priority = 480)
public class MixinLowPriorityConnection {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void channelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        OffTickTeleport ott = OffTickTeleport.INSTANCE;
        if (ott.getEnabled() && ott.getPacketProcessing()) {
            ott.onReceive(packet);
        }
    }
}
