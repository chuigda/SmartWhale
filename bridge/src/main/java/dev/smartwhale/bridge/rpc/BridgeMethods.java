package dev.smartwhale.bridge.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.smartwhale.bridge.SmartWhaleBridge;
import dev.smartwhale.bridge.action.Actions;
import dev.smartwhale.bridge.compat.baritone.BaritoneTasks;
import dev.smartwhale.bridge.observe.InventoryObserver;
import dev.smartwhale.bridge.observe.Perception;
import dev.smartwhale.bridge.observe.ScreenObserver;
import dev.smartwhale.bridge.observe.StatusObserver;
import dev.smartwhale.bridge.observe.WorldObserver;
import dev.smartwhale.bridge.task.TaskManager;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;

public final class BridgeMethods {
    private BridgeMethods() {
    }

    private static RpcDispatcher dispatcher;

    /** Checked here, not in compat.baritone, so Baritone classes are never loaded when it is absent. */
    public static boolean baritone() {
        return ModList.get().isLoaded("baritoe");
    }

    public static void registerAll(RpcDispatcher d, TaskManager tasks) {
        dispatcher = d;
        d.onWorker("bridge.hello", BridgeMethods::hello);
        d.onWorker("bridge.ping", p -> new JsonPrimitive("pong"));
        d.onMainThread("observe.status", p -> StatusObserver.status());
        d.onMainThread("observe.inventory", InventoryObserver::inventory);
        d.onMainThread("observe.blocks", WorldObserver::blocks);
        d.onMainThread("observe.find_blocks", WorldObserver::findBlocks);
        d.onMainThread("observe.block", WorldObserver::block);
        d.onMainThread("observe.entities", WorldObserver::entities);
        d.onMainThread("observe.players", WorldObserver::players);
        d.onMainThread("observe.screen", ScreenObserver::screen);
        Actions.register(d);
        d.onMainThread("task.status", p -> tasks.status());
        d.onMainThread("task.cancel", p -> tasks.cancel());
        if (baritone()) {
            BaritoneTasks.register(d, tasks);
        } else {
            RpcDispatcher.Handler missing = p -> {
                throw new RpcException(RpcException.METHOD_NOT_FOUND, "Baritone is not installed",
                        "Movement tasks need the Baritone mod in the bot's mods folder");
            };
            for (String m : new String[] {"goto", "goto_block", "mine", "follow", "explore", "farm", "collect_items"}) {
                d.onWorker("task." + m, missing);
            }
        }
    }

    private static JsonObject hello(JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("bridge_version", SmartWhaleBridge.VERSION);
        r.addProperty("protocol", SmartWhaleBridge.PROTOCOL);
        r.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        JsonArray mods = new JsonArray();
        String neoforge = null;
        for (var info : ModList.get().getMods()) {
            JsonObject m = new JsonObject();
            m.addProperty("id", info.getModId());
            m.addProperty("name", info.getDisplayName());
            m.addProperty("version", info.getVersion().toString());
            mods.add(m);
            if ("neoforge".equals(info.getModId())) neoforge = info.getVersion().toString();
        }
        r.addProperty("neoforge", neoforge);
        r.add("mods", mods);
        JsonArray caps = new JsonArray();
        for (String m : dispatcher.methodNames()) caps.add(m);
        if (baritone()) caps.add("baritone");
        r.add("capabilities", caps);
        r.addProperty("perception", Perception.MODE);
        return r;
    }
}
