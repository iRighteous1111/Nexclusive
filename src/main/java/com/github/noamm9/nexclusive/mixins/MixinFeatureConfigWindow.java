package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.features.Feature;
import com.github.noamm9.nexclusive.features.impl.nexclusive.leap.LeapMenuFeatures;
import com.github.noamm9.ui.clickgui.components.FeatureConfigWindow;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FeatureConfigWindow.class, remap = false)
public class MixinFeatureConfigWindow {
    @Shadow
    @Final
    private Feature feature;

    @Dynamic
    @Inject(method = "getUseSidebar", at = @At("HEAD"), cancellable = true, require = 0)
    private void onGetUseSidebar(CallbackInfoReturnable<Boolean> cir) {
        if (this.feature == LeapMenuFeatures.INSTANCE) {
            cir.setReturnValue(true);
        }
    }
}
