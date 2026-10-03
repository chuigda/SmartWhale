package dev.smartwhale.bridge.mixin;

import dev.smartwhale.bridge.Headless;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void smartwhale$skipRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // The loading overlay drives the end of mod loading from its render method, so it must keep drawing.
        if (Headless.ENABLED && Minecraft.getInstance().getOverlay() == null) ci.cancel();
    }
}
