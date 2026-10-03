package dev.smartwhale.bridge.util;

import dev.smartwhale.bridge.SmartWhaleBridge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs short-lived per-tick callbacks on the client thread until they return true. */
public final class Ticker {
    private static final List<BooleanSupplier> CALLBACKS = new ArrayList<>();

    /** Client thread only. */
    public static void add(BooleanSupplier callback) {
        CALLBACKS.add(callback);
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        if (CALLBACKS.isEmpty()) return;
        // Snapshot so callbacks may schedule further callbacks.
        List<BooleanSupplier> batch = new ArrayList<>(CALLBACKS);
        CALLBACKS.clear();
        List<BooleanSupplier> keep = new ArrayList<>();
        for (Iterator<BooleanSupplier> it = batch.iterator(); it.hasNext(); ) {
            BooleanSupplier cb = it.next();
            try {
                if (!cb.getAsBoolean()) keep.add(cb);
            } catch (RuntimeException e) {
                SmartWhaleBridge.LOGGER.error("Tick callback failed", e);
            }
        }
        CALLBACKS.addAll(0, keep);
    }
}
