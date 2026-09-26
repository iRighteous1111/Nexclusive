package com.github.noamm9.nexclusive.mixins.accessors;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface IKeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key getKey();

    @Accessor("clickCount")
    int getClickCount();

    @Accessor("clickCount")
    void setClickCount(int clickCount);
}
