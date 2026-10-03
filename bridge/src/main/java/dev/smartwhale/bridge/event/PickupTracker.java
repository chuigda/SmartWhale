package dev.smartwhale.bridge.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Map;
import java.util.TreeMap;

/** Collects pickups from ClientboundTakeItemEntityPacket and flushes them as one event per second. Client thread only. */
public final class PickupTracker {
    private static final int FLUSH_TICKS = 20;
    private static final Map<String, Integer> PENDING = new TreeMap<>();
    private static int age;

    private PickupTracker() {
    }

    public static void onTake(int itemEntityId, int playerId, int amount) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || playerId != mc.player.getId()) return;
        Entity e = mc.level.getEntity(itemEntityId);
        if (!(e instanceof ItemEntity item)) return;
        if (PENDING.isEmpty()) age = 0;
        PENDING.merge(Game.id(item.getItem().getItem()), amount, Integer::sum);
    }

    /** Called every tick; returns the event payload when it's time to flush. */
    static JsonObject tick() {
        if (PENDING.isEmpty() || ++age < FLUSH_TICKS) return null;
        JsonArray items = new JsonArray();
        PENDING.forEach((id, count) -> {
            JsonObject o = new JsonObject();
            o.addProperty("item", id);
            o.addProperty("count", count);
            items.add(o);
        });
        PENDING.clear();
        JsonObject p = new JsonObject();
        p.add("items", items);
        return p;
    }
}
