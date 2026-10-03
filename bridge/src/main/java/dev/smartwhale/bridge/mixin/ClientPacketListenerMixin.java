package dev.smartwhale.bridge.mixin;

import dev.smartwhale.bridge.event.PickupTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    // After the thread check, so this runs once on the client thread while the item entity still exists.
    @Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER))
    private void smartwhale$onTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        PickupTracker.onTake(packet.getItemId(), packet.getPlayerId(), packet.getAmount());
    }
}
