package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.config.ConfigHolder;
import com.github.noamm9.nexclusive.ui.PlusMinusSetting;
import com.github.noamm9.nexclusive.ui.PlusMinusWidget;
import com.github.noamm9.ui.clickgui.components.settings.Widget;
import com.github.noamm9.ui.clickgui.components.settings.WidgetFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WidgetFactory.class)
public class MixinWidgetFactory {

    @Inject(method = "fromSetting", at = @At("HEAD"), cancellable = true)
    private void onFromSetting(ConfigHolder<?> configHolder, CallbackInfoReturnable<Widget<?>> cir) {
        if (configHolder instanceof PlusMinusSetting) {
            cir.setReturnValue(new PlusMinusWidget((PlusMinusSetting) configHolder));
        }
    }
}
