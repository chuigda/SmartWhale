package dev.smartwhale.bridge.mixin;

import dev.smartwhale.bridge.Headless;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadStatusManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The loading screen after joining/respawning waits until the player's chunk section is compiled for
 * rendering. Rendering is skipped in headless mode, so that never happens and the screen only closes
 * after its 30 s timeout; wait for the chunk data instead.
 */
@Mixin(LevelLoadStatusManager.class)
public abstract class LevelLoadStatusManagerMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean smartwhale$chunkLoaded(LevelRenderer renderer, BlockPos pos) {
        if (!Headless.ENABLED) return renderer.isSectionCompiled(pos);
        return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
