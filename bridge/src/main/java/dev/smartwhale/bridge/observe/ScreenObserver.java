package dev.smartwhale.bridge.observe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** observe.screen: the open screen, its menu slots, widgets and data slots. */
public final class ScreenObserver {
    private static Field dataSlotsField;

    private ScreenObserver() {
    }

    public static String menuType(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) return "minecraft:inventory";
        try {
            var key = BuiltInRegistries.MENU.getKey(menu.getType());
            return key != null ? key.toString() : null;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    public static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener l : screen.children()) {
            if (l instanceof AbstractWidget w) out.add(w);
        }
        return out;
    }

    public static JsonObject screen(JsonObject params) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        JsonObject r = new JsonObject();
        r.addProperty("open", screen != null);
        if (screen == null) return r;
        r.addProperty("class", screen.getClass().getName());
        r.addProperty("title", screen.getTitle().getString());

        JsonArray widgets = new JsonArray();
        List<AbstractWidget> list = widgets(screen);
        for (int i = 0; i < list.size(); i++) {
            AbstractWidget w = list.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("index", i);
            o.addProperty("type", w.getClass().getSimpleName());
            o.addProperty("text", w.getMessage().getString());
            o.addProperty("active", w.active && w.visible);
            widgets.add(o);
        }
        r.add("widgets", widgets);

        if (screen instanceof AbstractContainerScreen<?> cs && mc.player != null) {
            AbstractContainerMenu menu = cs.getMenu();
            JsonObject m = new JsonObject();
            m.addProperty("container_id", menu.containerId);
            m.addProperty("menu_type", menuType(menu));
            JsonArray slots = new JsonArray();
            JsonArray emptyContainer = new JsonArray();
            int containerSlots = 0;
            for (Slot slot : menu.slots) {
                boolean player = slot.container instanceof Inventory;
                if (!player) containerSlots++;
                if (!slot.hasItem()) {
                    if (!player) emptyContainer.add(slot.index);
                    continue;
                }
                JsonObject o = Game.item(slot.getItem());
                o.addProperty("index", slot.index);
                o.addProperty("owner", player ? "player" : "container");
                if (player) o.addProperty("inventory_slot", slot.getContainerSlot());
                slots.add(o);
            }
            m.add("slots", slots);
            m.addProperty("container_slot_count", containerSlots);
            m.add("empty_container_slots", emptyContainer);
            if (!menu.getCarried().isEmpty()) m.add("carried", Game.item(menu.getCarried()));
            JsonArray data = dataSlots(menu);
            if (data != null && !data.isEmpty()) m.add("data", data);
            r.add("menu", m);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static JsonArray dataSlots(AbstractContainerMenu menu) {
        try {
            if (dataSlotsField == null) {
                for (Field f : AbstractContainerMenu.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())
                            && f.getGenericType().getTypeName().contains(DataSlot.class.getName())) {
                        f.setAccessible(true);
                        dataSlotsField = f;
                        break;
                    }
                }
                if (dataSlotsField == null) return null;
            }
            JsonArray out = new JsonArray();
            for (DataSlot ds : (List<DataSlot>) dataSlotsField.get(menu)) out.add(ds.get());
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
