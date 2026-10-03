package dev.smartwhale.bridge.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.rpc.RpcException;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Multi-tick mouse input. Vanilla calls {@code continueAttack(false)} every tick when the (hidden) window
 * has no grabbed mouse, which aborts block breaking, so {@code MinecraftMixin} suppresses that call while
 * we are breaking and we drive breaking from our own tick. Holding "use" is done with the real key binding plus suppressing vanilla's re-use.
 */
public final class InputControl {
    private static Breaking breaking;
    private static UseHold using;

    /** Breaks one block; completes with true when it is gone, false when it failed or was cancelled. */
    public static final class Breaking {
        final BlockPos pos;
        final Direction face;
        final Block original;
        final int timeoutTicks;
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        int ticks;
        boolean started;
        String failure;

        Breaking(BlockPos pos, Direction face, Block original, int timeoutTicks) {
            this.pos = pos;
            this.face = face;
            this.original = original;
            this.timeoutTicks = timeoutTicks;
        }

        public CompletableFuture<Boolean> future() {
            return done;
        }

        public String failure() {
            return failure;
        }

        public int ticks() {
            return ticks;
        }

        void fail(String why) {
            failure = why;
            if (started && Minecraft.getInstance().gameMode != null) Minecraft.getInstance().gameMode.stopDestroyBlock();
            done.complete(false);
        }

        /** Called in place of vanilla continueAttack. */
        void step() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || mc.gameMode == null) {
                fail("not in world");
                return;
            }
            if (mc.level.getBlockState(pos).getBlock() != original || mc.level.getBlockState(pos).isAir()) {
                done.complete(true);
                return;
            }
            if (++ticks > timeoutTicks) {
                fail("timed out after " + timeoutTicks + " ticks");
                return;
            }
            if (!p.canInteractWithBlock(pos, 0)) {
                fail("out of reach");
                return;
            }
            Game.lookAt(p, faceCenter(pos, face));
            if (!started) {
                started = true;
                mc.gameMode.startDestroyBlock(pos, face);
            } else {
                mc.gameMode.continueDestroyBlock(pos, face);
            }
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static final class UseHold {
        final InteractionHand hand;
        final int maxTicks;
        final CompletableFuture<JsonElement> done;
        int ticks;

        UseHold(InteractionHand hand, int maxTicks, CompletableFuture<JsonElement> done) {
            this.hand = hand;
            this.maxTicks = maxTicks;
            this.done = done;
        }
    }

    public static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
    }

    public static boolean busyBreaking() {
        return breaking != null && !breaking.done.isDone();
    }

    public static Breaking startBreaking(BlockPos pos, Direction face, Block original, int timeoutTicks) {
        cancelBreaking("superseded");
        breaking = new Breaking(pos.immutable(), face, original, timeoutTicks);
        return breaking;
    }

    public static void cancelBreaking(String why) {
        if (breaking != null && !breaking.done.isDone()) breaking.fail(why);
        breaking = null;
    }

    /** Starts holding "use" with {@code hand}; the player must already be using an item. */
    public static void holdUse(InteractionHand hand, int maxTicks, CompletableFuture<JsonElement> done) {
        releaseUse("superseded");
        using = new UseHold(hand, maxTicks, done);
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    private static void releaseUse(String why) {
        if (using == null) return;
        Minecraft.getInstance().options.keyUse.setDown(false);
        if (!using.done.isDone()) {
            using.done.completeExceptionally(new RpcException(RpcException.INTERNAL_ERROR, "Use interrupted: " + why));
        }
        using = null;
    }

    /** MinecraftMixin hook; true = vanilla must not touch block breaking this tick (it would abort ours). */
    public static boolean onContinueAttack() {
        return breaking != null && !breaking.done.isDone();
    }

    /** MinecraftMixin hook; true = vanilla must not start another use while we hold the key. */
    public static boolean suppressUseItem() {
        return using != null;
    }

    /**
     * Breaking is driven from here rather than from vanilla's continueAttack, which only runs while no
     * screen is open (e.g. not during the loading screen after a respawn).
     */
    @SubscribeEvent
    public void onTickPre(ClientTickEvent.Pre event) {
        if (breaking == null) return;
        if (breaking.done.isDone()) {
            breaking = null;
            return;
        }
        breaking.step();
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (using == null) return;
        LocalPlayer p = mc.player;
        if (p == null) {
            releaseUse("not in world");
            return;
        }
        if (using.done.isDone()) {
            // Caller timed out.
            mc.options.keyUse.setDown(false);
            if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);
            using = null;
            return;
        }
        using.ticks++;
        if (!p.isUsingItem()) {
            mc.options.keyUse.setDown(false);
            using.done.complete(useResult("finished", using.ticks));
            using = null;
        } else if (using.ticks >= using.maxTicks) {
            mc.options.keyUse.setDown(false);
            mc.gameMode.releaseUsingItem(p);
            using.done.complete(useResult("released", using.ticks));
            using = null;
        }
    }

    private static JsonObject useResult(String result, int ticks) {
        JsonObject o = new JsonObject();
        o.addProperty("result", result);
        o.addProperty("ticks", ticks);
        return o;
    }
}
