package dev.smartwhale.bridge.action;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.observe.Json;
import dev.smartwhale.bridge.observe.Perception;
import dev.smartwhale.bridge.rpc.RpcDispatcher;
import dev.smartwhale.bridge.rpc.RpcException;
import dev.smartwhale.bridge.util.Game;
import dev.smartwhale.bridge.util.Matchers;
import dev.smartwhale.bridge.util.P;
import dev.smartwhale.bridge.util.Ticker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** action.* (docs/DESIGN.md §6.3): instantaneous or short multi-tick player actions. */
public final class Actions {
    private static final Set<String> ALLOWED_COMMANDS = Set.of("msg", "tell", "w", "r", "me");
    private static final int MAX_CHAT = 256;

    private Actions() {
    }

    public static void register(RpcDispatcher d) {
        d.onMainThread("action.chat", Actions::chat);
        d.onMainThread("action.look", Actions::look);
        d.onMainThread("action.select_hotbar", Actions::selectHotbar);
        d.onMainThread("action.equip", Actions::equip);
        d.onMainThread("action.drop", Actions::drop);
        d.onMainThreadAsync("action.use_item", Actions::useItem);
        d.onMainThread("action.interact_block", Actions::interactBlock);
        d.onMainThreadAsync("action.break_block", Actions::breakBlock);
        d.onMainThread("action.place_block", Actions::placeBlock);
        d.onMainThread("action.interact_entity", Actions::interactEntity);
        d.onMainThreadAsync("action.attack", Actions::attack);
        d.onMainThread("action.respawn", Actions::respawn);
        d.onMainThread("menu.close", Actions::closeMenu);
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static RpcException target(String message, String hint) {
        return new RpcException(RpcException.INVALID_TARGET, message, hint);
    }

    private static JsonElement chat(JsonObject params) {
        LocalPlayer p = Game.player();
        String msg = P.str(params, "message").strip();
        if (msg.isEmpty()) throw P.invalid("Empty message");
        if (msg.length() > MAX_CHAT) throw new RpcException(RpcException.INVALID_PARAMS,
                "Message longer than " + MAX_CHAT + " characters", "Split it into several shorter messages");
        if (msg.startsWith("/")) {
            String cmd = msg.substring(1);
            String name = cmd.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (!ALLOWED_COMMANDS.contains(name)) {
                throw new RpcException(RpcException.INVALID_PARAMS, "Command not allowed: /" + name,
                        "Only /msg, /tell, /w, /r and /me are allowed");
            }
            p.connection.sendCommand(cmd);
        } else {
            p.connection.sendChat(msg);
        }
        return ok();
    }

    private static JsonElement look(JsonObject params) {
        LocalPlayer p = Game.player();
        if (P.has(params, "entity_id")) {
            Entity e = entity(params);
            Game.lookAt(p, e.getEyePosition());
        } else if (P.has(params, "pos")) {
            JsonObject o = P.obj(params, "pos");
            Vec3 v = P.vec(params, "pos");
            // Integer coordinates mean a block: look at its center.
            boolean block = o.get("x").getAsDouble() % 1 == 0 && o.get("y").getAsDouble() % 1 == 0 && o.get("z").getAsDouble() % 1 == 0;
            Game.lookAt(p, block ? v.add(0.5, 0.5, 0.5) : v);
        } else if (P.has(params, "yaw") || P.has(params, "pitch")) {
            float yaw = P.has(params, "yaw") ? (float) P.num(params, "yaw") : p.getYRot();
            float pitch = P.has(params, "pitch") ? (float) P.num(params, "pitch") : p.getXRot();
            Game.look(p, yaw, pitch);
        } else {
            throw P.invalid("Give pos, entity_id or yaw/pitch");
        }
        JsonObject r = new JsonObject();
        r.addProperty("yaw", Json.round(p.getYRot()));
        r.addProperty("pitch", Json.round(p.getXRot()));
        return r;
    }

    private static JsonElement selectHotbar(JsonObject params) {
        LocalPlayer p = Game.player();
        int slot = P.integer(params, "slot");
        if (slot < 0 || slot > 8) throw P.invalid("slot must be 0-8");
        p.getInventory().selected = slot;
        JsonObject r = new JsonObject();
        r.add("mainhand", Game.item(p.getMainHandItem()));
        return r;
    }

    private static int findItem(LocalPlayer p, JsonObject params) {
        Matchers.Items match = Matchers.items(P.strList(params, "item"));
        int idx = Inv.find(p, match);
        if (idx < 0) throw target("No " + String.join("/", match.spec()) + " in inventory", "Check observe.inventory");
        return idx;
    }

    private static JsonElement equip(JsonObject params) {
        LocalPlayer p = Game.player();
        String slotName = P.optStr(params, "slot", "mainhand").toLowerCase(Locale.ROOT);
        int idx = findItem(p, params);
        ItemStack stack = p.getInventory().getItem(idx);
        switch (slotName) {
            case "mainhand" -> Inv.toMainHand(p, idx);
            case "offhand" -> {
                if (idx != Inv.OFFHAND) {
                    Inv.requireInventoryMenu(p);
                    Inv.click(p, Inv.menuSlot(idx), Inv.OFFHAND, ClickType.SWAP);
                }
            }
            case "head", "chest", "legs", "feet" -> {
                EquipmentSlot want = EquipmentSlot.byName(slotName);
                if (p.getEquipmentSlotForItem(stack) != want) {
                    throw target(Game.id(stack.getItem()) + " can't be worn on " + slotName, null);
                }
                int armorIdx = 36 + want.getIndex();
                if (idx != armorIdx) {
                    Inv.requireInventoryMenu(p);
                    int src = Inv.menuSlot(idx);
                    Inv.click(p, src, 0, ClickType.PICKUP);
                    Inv.click(p, Inv.menuSlot(armorIdx), 0, ClickType.PICKUP);
                    if (!p.inventoryMenu.getCarried().isEmpty()) Inv.click(p, src, 0, ClickType.PICKUP);
                }
            }
            default -> throw P.invalid("slot must be mainhand, offhand, head, chest, legs or feet");
        }
        JsonObject r = new JsonObject();
        r.add("mainhand", Game.item(p.getMainHandItem()));
        r.add("offhand", Game.item(p.getOffhandItem()));
        return r;
    }

    private static JsonElement drop(JsonObject params) {
        LocalPlayer p = Game.player();
        Inv.requireInventoryMenu(p);
        Inventory inv = p.getInventory();
        int wanted = P.optInt(params, "count", Integer.MAX_VALUE);
        if (wanted <= 0) throw P.invalid("count must be positive");
        int dropped = 0;
        if (P.has(params, "slot")) {
            int slot = P.integer(params, "slot");
            if (slot < 0 || slot > Inv.OFFHAND) throw P.invalid("slot must be 0-40");
            dropped = dropFrom(p, slot, wanted);
        } else {
            Matchers.Items match = Matchers.items(P.strList(params, "item"));
            for (int i = 0; i <= Inv.OFFHAND && dropped < wanted; i++) {
                if (match.test(inv.getItem(i))) dropped += dropFrom(p, i, wanted - dropped);
            }
        }
        if (dropped == 0) throw target("Nothing to drop", "Check observe.inventory");
        JsonObject r = new JsonObject();
        r.addProperty("dropped", dropped);
        return r;
    }

    private static int dropFrom(LocalPlayer p, int invIndex, int max) {
        ItemStack stack = p.getInventory().getItem(invIndex);
        if (stack.isEmpty()) return 0;
        int n = Math.min(max, stack.getCount());
        int slot = Inv.menuSlot(invIndex);
        if (n == stack.getCount()) {
            Inv.click(p, slot, 1, ClickType.THROW);
        } else {
            for (int i = 0; i < n; i++) Inv.click(p, slot, 0, ClickType.THROW);
        }
        return n;
    }

    private static InteractionHand hand(JsonObject params) {
        String h = P.optStr(params, "hand", "main").toLowerCase(Locale.ROOT);
        return switch (h) {
            case "main", "mainhand", "main_hand" -> InteractionHand.MAIN_HAND;
            case "off", "offhand", "off_hand" -> InteractionHand.OFF_HAND;
            default -> throw P.invalid("hand must be main or off");
        };
    }

    private static CompletableFuture<JsonElement> useItem(JsonObject params) {
        LocalPlayer p = Game.player();
        InteractionHand hand = hand(params);
        ItemStack stack = p.getItemInHand(hand);
        if (stack.isEmpty()) throw target("Nothing in that hand", "Use action.equip first");
        InteractionResult result = Game.gameMode().useItem(p, hand);
        if (result.shouldSwing()) p.swing(hand);
        CompletableFuture<JsonElement> done = new CompletableFuture<>();
        if (!p.isUsingItem()) {
            JsonObject r = new JsonObject();
            r.addProperty("result", result.name().toLowerCase(Locale.ROOT));
            done.complete(r);
            return done;
        }
        int natural = stack.getUseDuration(p);
        int duration = P.has(params, "duration_ticks")
                ? P.clampInt(params, "duration_ticks", 20, 1, 1200)
                : Math.min(natural + 5, 1200);
        InputControl.holdUse(hand, duration, done);
        return done;
    }

    private static void requireReach(LocalPlayer p, BlockPos pos) {
        if (!p.canInteractWithBlock(pos, 0)) {
            throw target("Block out of reach (" + Game.distance(p, pos) + " blocks)",
                    "Move closer first, e.g. task.goto with range 2");
        }
    }

    /** The face the player is looking at when aiming at the block center, or the one facing the eyes. */
    private static Direction visibleFace(LocalPlayer p, BlockPos pos) {
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = p.level().clip(new ClipContext(eye, Vec3.atCenterOf(pos),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit.getDirection();
        Vec3 d = eye.subtract(Vec3.atCenterOf(pos));
        return Direction.getNearest(d.x, d.y, d.z);
    }

    private static JsonElement interactBlock(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos pos = P.blockPos(params, "pos");
        if (!level.isLoaded(pos)) throw target("Chunk not loaded", null);
        requireReach(p, pos);
        Direction face = P.optDirection(params, "face");
        if (face == null) face = visibleFace(p, pos);
        Vec3 hitVec = InputControl.faceCenter(pos, face);
        Game.lookAt(p, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
        JsonObject r = new JsonObject();
        r.addProperty("block", Game.id(level.getBlockState(pos).getBlock()));
        InteractionHand[] hands = P.has(params, "hand")
                ? new InteractionHand[] {hand(params)}
                : new InteractionHand[] {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};
        for (InteractionHand h : hands) {
            InteractionResult res = Game.gameMode().useItemOn(p, h, hit);
            if (res.consumesAction()) {
                if (res.shouldSwing()) p.swing(h);
                r.addProperty("result", res.name().toLowerCase(Locale.ROOT));
                r.addProperty("hand", h == InteractionHand.MAIN_HAND ? "main" : "off");
                return r;
            }
        }
        r.addProperty("result", "pass");
        r.addProperty("hint", "Nothing happened; the block may not be interactive");
        return r;
    }

    /**
     * Puts the best tool for the block in the main hand. Without a proper tool (one that is faster than a
     * bare hand or needed for drops) it switches to an empty hand, so held items such as guns, food or
     * swords are not used to break blocks (some of them cannot break blocks at all).
     */
    public static void selectBestTool(LocalPlayer p, BlockPos pos, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < 36; i++) {
            float score = toolScore(p, inv.getItem(i), pos, state);
            if (score > bestScore || score == bestScore && score > 0 && i == inv.selected) {
                best = i;
                bestScore = score;
            }
        }
        boolean inventoryUsable = p.containerMenu == p.inventoryMenu;
        if (best >= 0 && (best < 9 || inventoryUsable)) {
            Inv.toMainHand(p, best);
            return;
        }
        if (inv.getItem(inv.selected).isEmpty()) return;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.selected = i;
                return;
            }
        }
        // Hotbar full: move the held item into a free main-inventory slot.
        if (!inventoryUsable) return;
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                Inv.click(p, Inv.menuSlot(i), inv.selected, ClickType.SWAP);
                return;
            }
        }
    }

    /** 0 = no better than a bare hand. Getting drops matters more than speed. */
    private static float toolScore(LocalPlayer p, ItemStack s, BlockPos pos, BlockState state) {
        if (s.isEmpty() || !s.getItem().canAttackBlock(state, p.level(), pos, p)) return 0;
        float speed = s.getDestroySpeed(state);
        boolean neededForDrops = state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state);
        if (!neededForDrops && speed <= 1.0F) return 0;
        return (neededForDrops ? 1000.0F : 0.0F) + speed;
    }

    /** Whether breaking {@code state} with the best available tool yields drops. */
    public static boolean canHarvest(LocalPlayer p, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        return false;
    }

    /** Validates and starts breaking; shared with task.mine. */
    public static InputControl.Breaking startBreak(LocalPlayer p, BlockPos pos, boolean autoTool) {
        ClientLevel level = Game.level();
        if (!level.isLoaded(pos)) throw target("Chunk not loaded", null);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) throw target("There is no block at that position", null);
        if (state.getDestroySpeed(level, pos) < 0) throw target(Game.id(state.getBlock()) + " is unbreakable", null);
        requireReach(p, pos);
        if (!Perception.visible(p, level, pos)) {
            throw target("You can't see that block", "Clear the line of sight or break the block in front of it first");
        }
        if (autoTool) selectBestTool(p, pos, state);
        Direction face = visibleFace(p, pos);
        // Twice the expected time (it changes e.g. when the bot falls or gets wet), capped at 60 s.
        float perTick = state.getDestroyProgress(p, level, pos);
        int timeout = perTick <= 0 ? 20 * 60 : (int) Math.min(20 * 60, Math.ceil(1 / perTick) * 2 + 40);
        return InputControl.startBreaking(pos, face, state.getBlock(), timeout);
    }

    private static CompletableFuture<JsonElement> breakBlock(JsonObject params) {
        LocalPlayer p = Game.player();
        BlockPos pos = P.blockPos(params, "pos");
        String id = Game.id(Game.level().getBlockState(pos).getBlock());
        InputControl.Breaking b = startBreak(p, pos, P.optBool(params, "auto_tool", true));
        CompletableFuture<JsonElement> done = new CompletableFuture<>();
        b.future().whenComplete((ok, err) -> {
            if (ok != null && ok) {
                JsonObject r = new JsonObject();
                r.addProperty("broken", id);
                r.addProperty("ticks", b.ticks());
                done.complete(r);
            } else {
                done.completeExceptionally(target("Breaking " + id + " failed: " + b.failure(), null));
            }
        });
        // The RPC caller may time out; stop breaking then.
        done.whenComplete((r, err) -> {
            if (err != null) Minecraft.getInstance().execute(() -> InputControl.cancelBreaking("cancelled"));
        });
        return done;
    }

    private static JsonElement placeBlock(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos pos = P.blockPos(params, "pos");
        if (!level.isLoaded(pos)) throw target("Chunk not loaded", null);
        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced()) throw target("Position is occupied by " + Game.id(existing.getBlock()), null);
        if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) {
            throw target("You are standing in that position", "Place it somewhere else or move first");
        }
        Direction against = P.optDirection(params, "against");
        BlockPos support = null;
        Direction face = null;
        for (Direction d : against != null ? new Direction[] {against} : new Direction[] {
                Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).canBeReplaced()) continue;
            if (!p.canInteractWithBlock(n, 0)) continue;
            support = n;
            face = d.getOpposite();
            break;
        }
        if (support == null) {
            throw target("Nothing within reach to place against", "Place next to an existing solid block, within reach");
        }
        int idx = findItem(p, params);
        Inv.toMainHand(p, idx);
        Vec3 hitVec = InputControl.faceCenter(support, face);
        Game.lookAt(p, hitVec);
        InteractionResult res = Game.gameMode().useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitVec, face, support, false));
        if (res.shouldSwing()) p.swing(InteractionHand.MAIN_HAND);
        JsonObject r = new JsonObject();
        r.addProperty("result", res.name().toLowerCase(Locale.ROOT));
        r.addProperty("now", Game.id(level.getBlockState(pos).getBlock()));
        return r;
    }

    private static Entity entity(JsonObject params) {
        Entity e = Game.level().getEntity(P.integer(params, "entity_id"));
        if (e == null || !e.isAlive()) throw target("No such entity nearby", "Check observe.entities");
        return e;
    }

    private static void requireReach(LocalPlayer p, Entity e) {
        if (!p.canInteractWithEntity(e, 0)) {
            throw target("Entity out of reach (" + Json.round(e.distanceTo(p)) + " blocks)",
                    "Move closer first, e.g. task.follow or task.goto");
        }
    }

    private static JsonElement interactEntity(JsonObject params) {
        LocalPlayer p = Game.player();
        Entity e = entity(params);
        requireReach(p, e);
        Vec3 center = e.getBoundingBox().getCenter();
        Game.lookAt(p, center);
        EntityHitResult hit = new EntityHitResult(e, center);
        InteractionHand[] hands = P.has(params, "hand")
                ? new InteractionHand[] {hand(params)}
                : new InteractionHand[] {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};
        JsonObject r = new JsonObject();
        for (InteractionHand h : hands) {
            InteractionResult res = Game.gameMode().interactAt(p, e, hit, h);
            if (!res.consumesAction()) res = Game.gameMode().interact(p, e, h);
            if (res.consumesAction()) {
                if (res.shouldSwing()) p.swing(h);
                r.addProperty("result", res.name().toLowerCase(Locale.ROOT));
                return r;
            }
        }
        r.addProperty("result", "pass");
        return r;
    }

    private static CompletableFuture<JsonElement> attack(JsonObject params) {
        LocalPlayer p = Game.player();
        Entity e = entity(params);
        requireReach(p, e);
        CompletableFuture<JsonElement> done = new CompletableFuture<>();
        int[] waited = {0};
        Ticker.add(() -> {
            if (done.isDone()) return true;
            LocalPlayer pl = Minecraft.getInstance().player;
            if (pl == null || !e.isAlive()) {
                done.completeExceptionally(target("Target is gone", null));
                return true;
            }
            Game.lookAt(pl, e.getBoundingBox().getCenter());
            // Wait for the attack cooldown (at most 1.5 s) so the hit does full damage.
            if (pl.getAttackStrengthScale(0.5F) < 1.0F && waited[0]++ < 30) return false;
            if (!pl.canInteractWithEntity(e, 0)) {
                done.completeExceptionally(target("Target moved out of reach", null));
                return true;
            }
            Minecraft.getInstance().gameMode.attack(pl, e);
            pl.swing(InteractionHand.MAIN_HAND);
            JsonObject r = new JsonObject();
            r.addProperty("attacked", Game.id(e));
            r.addProperty("waited_ticks", waited[0]);
            done.complete(r);
            return true;
        });
        return done;
    }

    private static JsonElement respawn(JsonObject params) {
        LocalPlayer p = Game.player();
        if (!p.isDeadOrDying()) throw target("You are not dead", null);
        p.respawn();
        Minecraft.getInstance().setScreen(null);
        return ok();
    }

    private static JsonElement closeMenu(JsonObject params) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return ok();
        if (mc.screen instanceof AbstractContainerScreen<?> && mc.player != null) mc.player.closeContainer();
        else mc.setScreen(null);
        return ok();
    }
}
