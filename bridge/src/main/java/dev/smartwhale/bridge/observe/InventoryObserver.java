package dev.smartwhale.bridge.observe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * observe.inventory. Slot numbers are {@link Inventory} indices: 0-8 hotbar, 9-35 main,
 * 36-39 armor (feet, legs, chest, head), 40 offhand.
 */
public final class InventoryObserver {
    private static final String[] ARMOR = {"feet", "legs", "chest", "head"};

    private InventoryObserver() {
    }

    public static JsonObject inventory(JsonObject params) {
        LocalPlayer p = Game.player();
        Inventory inv = p.getInventory();
        JsonObject r = new JsonObject();
        r.addProperty("selected", inv.selected);
        JsonArray hotbar = new JsonArray();
        JsonArray main = new JsonArray();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                free++;
                continue;
            }
            JsonObject o = Game.item(s);
            o.addProperty("slot", i);
            (i < 9 ? hotbar : main).add(o);
        }
        r.add("hotbar", hotbar);
        r.add("main", main);
        r.addProperty("free_slots", free);
        JsonObject armor = new JsonObject();
        for (int i = 0; i < 4; i++) armor.add(ARMOR[i], Game.item(inv.getItem(36 + i)));
        r.add("armor", armor);
        r.add("offhand", Game.item(inv.getItem(40)));
        JsonObject totals = new JsonObject();
        for (Map.Entry<String, Integer> e : Game.inventoryCounts(p).entrySet()) totals.addProperty(e.getKey(), e.getValue());
        r.add("totals", totals);
        if (!p.containerMenu.getCarried().isEmpty()) r.add("carried", Game.item(p.containerMenu.getCarried()));
        return r;
    }
}
