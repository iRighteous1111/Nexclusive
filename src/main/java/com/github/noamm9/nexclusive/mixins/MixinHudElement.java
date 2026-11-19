package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.DisableTitles;
import com.github.noamm9.ui.hud.HudElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HudElement.class, remap = false)
public abstract class MixinHudElement {

    @Shadow
    public abstract String getName();

    @Inject(method = "renderElement", at = @At("HEAD"), cancellable = true)
    private void onRenderElement(GuiGraphicsExtractor ctx, boolean example, CallbackInfo ci) {
        if (! example && "Terminal Titles".equals(this.getName()) && DisableTitles.shouldHideTitles()) {
            ci.cancel();
        }
    }
}
