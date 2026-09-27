package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.SSAimHelper;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(double movementTime, CallbackInfo ci) {
        if (SSAimHelper.INSTANCE.shouldSuppressMouseInput(this.accumulatedDX, this.accumulatedDY)) {
            // Cancelling skips vanilla's own accumulatedDX/DY reset, so the real movement would pile up and dump as one big jump once suppression ends.
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            ci.cancel();
        }
    }
}
