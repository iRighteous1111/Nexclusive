package com.github.noamm9.nexclusive.mixins;

import com.github.noamm9.nexclusive.features.impl.nexclusive.OffTickTeleport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, priority = 100000)
public class MixinMinecraftOffTick {
    @Unique
    private boolean nex$hasTickedEntity = false;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", ordinal = 0), order = 0)
    void runTickBeforePacketProcess(boolean advanceGameTime, CallbackInfo ci) {
        OffTickTeleport.INSTANCE.releaseIfAvailable();
    }

    @Inject(method = "tick", at = @At("HEAD"), order = 0)
    void tick(CallbackInfo ci) {
        OffTickTeleport.INSTANCE.releaseIfAvailable();
        nex$hasTickedEntity = false;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 4), order = 0)
    void tickAfterTickedEntities(CallbackInfo ci) {
        nex$hasTickedEntity = true;
    }

    @Inject(method = "renderFrame", at = @At("HEAD"))
    void renderFrame(boolean advanceGameTime, CallbackInfo ci) {
        OffTickTeleport ott = OffTickTeleport.INSTANCE;
        if (ott.getEnabled() && ott.getPacketProcessing() && (!ott.getAfterEntityTick() || nex$hasTickedEntity)) {
            ott.applyPosition();
        }
    }

    @Inject(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    void handleKeybindsAfterRightClicks(CallbackInfo ci) {
        OffTickTeleport ott = OffTickTeleport.INSTANCE;
        if (ott.getEnabled() && ott.getPacketProcessing()) {
            ott.startBuffering();
        }
    }
}
