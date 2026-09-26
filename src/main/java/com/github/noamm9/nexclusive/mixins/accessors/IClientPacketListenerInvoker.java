package com.github.noamm9.nexclusive.mixins.accessors;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(ClientPacketListener.class)
public interface IClientPacketListenerInvoker {
    @Invoker("setValuesFromPositionPacket")
    static boolean invokeSetValuesFromPositionPacket(PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean bl) {
        throw new AssertionError();
    }
}
