package com.github.noamm9.nexclusive.mixins;

import com.mojang.blaze3d.platform.InputConstants;
import com.github.noamm9.nexclusive.features.impl.nexclusive.OffTickTeleport;
import com.github.noamm9.nexclusive.mixins.accessors.IKeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
public class MixinKeyMapping {

    @Inject(method = "click", at = @At("TAIL"))
    private static void click(InputConstants.Key key, CallbackInfo ci) {
        OffTickTeleport ott = OffTickTeleport.INSTANCE;
        if (!ott.getEnabled() || !ott.getFastTeleport()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.keyUse == null) {
            return;
        }

        IKeyMappingAccessor keyUseAccessor = (IKeyMappingAccessor) (Object) mc.options.keyUse;
        if (keyUseAccessor.getKey() != key) {
            return;
        }

        if (mc.options.keyUse.consumeClick()) {
            if (!ott.fastTeleport()) {
                keyUseAccessor.setClickCount(keyUseAccessor.getClickCount() + 1);
            }
        }
    }
}
