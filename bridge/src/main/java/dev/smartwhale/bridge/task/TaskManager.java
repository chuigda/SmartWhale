package dev.smartwhale.bridge.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.SmartWhaleBridge;
import dev.smartwhale.bridge.observe.Json;
import dev.smartwhale.bridge.server.BridgeServer;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Map;

/**
 * Runs at most one task at a time; starting a new one supersedes the old one. Emits
 * {@code event.task_finished} / {@code event.task_failed}. Client thread only.
 */
public final class TaskManager {
    private static final int STUCK_TICKS = 20 * 30;

    private static TaskManager instance;

    private final BridgeServer server;
    private int seq;
    private Task current;
    private Map<String, Integer> startInventory;
    private Vec3 anchor;
    private int stillTicks;
    private JsonObject last;

    public TaskManager(BridgeServer server) {
        this.server = server;
        instance = this;
    }

    public static TaskManager get() {
        return instance;
    }

    public JsonObject start(Task task) {
        LocalPlayer p = Game.player();
        task.id = "t" + (++seq);
        // Tasks validate their parameters in the constructor, so a bad request never gets here
        // and doesn't kill the running task.
        if (current != null) end(current, Task.Outcome.fail("superseded", "A new task was started"));
        task.start();
        current = task;
        startInventory = Game.inventoryCounts(p);
        anchor = p.position();
        stillTicks = 0;
        JsonObject r = new JsonObject();
        r.addProperty("task_id", task.id);
        JsonObject info = task.startInfo();
        if (info != null) info.entrySet().forEach(e -> r.add(e.getKey(), e.getValue()));
        return r;
    }

    public JsonElement cancel() {
        JsonObject r = new JsonObject();
        if (current == null) {
            r.addProperty("cancelled", false);
            return r;
        }
        r.addProperty("cancelled", true);
        r.addProperty("task_id", current.id);
        end(current, Task.Outcome.fail("cancelled", null));
        return r;
    }

    public JsonObject status() {
        JsonObject r = new JsonObject();
        if (current != null) r.add("current", describe(current));
        if (last != null) r.add("last", last);
        return r;
    }

    /** For observe.status; null when idle. */
    public JsonObject currentSummary() {
        return current == null ? null : describe(current);
    }

    private static JsonObject describe(Task t) {
        JsonObject o = new JsonObject();
        o.addProperty("task_id", t.id);
        o.addProperty("kind", t.kind);
        o.addProperty("elapsed_s", Json.round(t.ticks / 20.0));
        JsonObject progress = t.progress();
        if (progress != null) o.add("progress", progress);
        return o;
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Task t = current;
        if (t == null) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            end(t, Task.Outcome.fail("disconnected", null));
            return;
        }
        if (p.isDeadOrDying()) {
            end(t, Task.Outcome.fail("died", "Respawn with action.respawn"));
            return;
        }
        t.ticks++;
        if (t.durationTicks > 0 && t.ticks >= t.durationTicks) {
            end(t, Task.Outcome.success());
            return;
        }
        if (t.timeoutTicks > 0 && t.ticks >= t.timeoutTicks) {
            end(t, Task.Outcome.fail("timeout", "The task took longer than " + t.timeoutTicks / 20 + " s"));
            return;
        }
        if (t.ticks % 20 == 0) {
            if (p.position().distanceToSqr(anchor) > 1.0) {
                anchor = p.position();
                stillTicks = 0;
            } else if (t.stuckCheck && (stillTicks += 20) >= STUCK_TICKS) {
                end(t, Task.Outcome.fail("stuck", "No movement for 30 s; try another target or route"));
                return;
            }
        }
        Task.Outcome o;
        try {
            o = t.tick();
        } catch (RuntimeException e) {
            SmartWhaleBridge.LOGGER.error("Task {} crashed", t.id, e);
            o = Task.Outcome.fail("error", e.toString());
        }
        if (o != null) end(t, o);
    }

    /** Resets the stuck timer, for tasks that legitimately stand still (e.g. while breaking a block). */
    public void touch() {
        stillTicks = 0;
    }

    private void end(Task t, Task.Outcome o) {
        if (current == t) current = null;
        try {
            t.stop();
        } catch (RuntimeException e) {
            SmartWhaleBridge.LOGGER.error("Task {} stop failed", t.id, e);
        }
        JsonObject p = new JsonObject();
        p.addProperty("task_id", t.id);
        p.addProperty("kind", t.kind);
        p.addProperty("elapsed_s", Json.round(t.ticks / 20.0));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            p.add("pos", Json.vec(player.getX(), player.getY(), player.getZ()));
            if (startInventory != null) p.add("inventory_delta", Game.countDelta(startInventory, Game.inventoryCounts(player)));
        }
        if (!o.ok()) {
            p.addProperty("reason", o.reason());
            if (o.hint() != null) p.addProperty("hint", o.hint());
        }
        if (o.extra() != null) o.extra().entrySet().forEach(e -> p.add(e.getKey(), e.getValue()));
        last = p.deepCopy();
        last.addProperty("ok", o.ok());
        server.notify(o.ok() ? "event.task_finished" : "event.task_failed", p);
    }
}
