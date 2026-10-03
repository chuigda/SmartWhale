package dev.smartwhale.bridge.mixin;

import dev.smartwhale.bridge.action.InputControl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void smartwhale$continueAttack(boolean leftClick, CallbackInfo ci) {
        if (InputControl.onContinueAttack()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void smartwhale$startUseItem(CallbackInfo ci) {
        if (InputControl.suppressUseItem()) ci.cancel();
    }
}
