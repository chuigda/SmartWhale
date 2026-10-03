package dev.smartwhale.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.observe.Json;
import dev.smartwhale.bridge.rpc.RpcException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.TreeMap;

/** Client-thread accessors shared by observers, actions and tasks. */
public final class Game {
    private Game() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static LocalPlayer player() {
        LocalPlayer p = mc().player;
        if (p == null || mc().level == null) {
            throw new RpcException(RpcException.NOT_IN_WORLD, "Not in a world", "Wait for event.world_ready");
        }
        return p;
    }

    public static ClientLevel level() {
        player();
        return mc().level;
    }

    public static MultiPlayerGameMode gameMode() {
        player();
        return mc().gameMode;
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String id(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    public static JsonObject item(ItemStack stack) {
        if (stack.isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("id", id(stack.getItem()));
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            o.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
            o.addProperty("max_durability", stack.getMaxDamage());
        }
        return o;
    }

    public static double distance(LocalPlayer p, BlockPos pos) {
        return Json.round(Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))));
    }

    public static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F);
        float pitch = Mth.wrapDegrees((float) (-(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG)));
        look(p, yaw, pitch);
    }

    public static void look(LocalPlayer p, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    /** Item id → total count over the whole player inventory (main, armor, offhand). */
    public static Map<String, Integer> inventoryCounts(LocalPlayer p) {
        Map<String, Integer> counts = new TreeMap<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) counts.merge(id(s.getItem()), s.getCount(), Integer::sum);
        }
        return counts;
    }

    public static JsonArray countDelta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> delta = new TreeMap<>();
        after.forEach((k, v) -> delta.merge(k, v, Integer::sum));
        before.forEach((k, v) -> delta.merge(k, -v, Integer::sum));
        JsonArray out = new JsonArray();
        delta.forEach((k, v) -> {
            if (v == 0) return;
            JsonObject o = new JsonObject();
            o.addProperty("item", k);
            o.addProperty("count", v);
            out.add(o);
        });
        return out;
    }
}
