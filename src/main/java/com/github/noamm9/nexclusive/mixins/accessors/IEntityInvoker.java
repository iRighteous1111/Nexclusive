package com.github.noamm9.nexclusive.mixins.accessors;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface IEntityInvoker {
    @Invoker("setOldPos")
    void invokeSetOldPos();
}
