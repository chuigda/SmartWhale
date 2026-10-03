package dev.smartwhale.bridge.compat.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
import dev.smartwhale.bridge.SmartWhaleBridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Thin Baritone wrapper (docs/DESIGN.md §5.4).  Classes in this package must only be loaded when
 * {@code BridgeMethods.baritone()} is true (Baritone's mod id is spelled "baritoe").
 */
public final class Bari {
    private static boolean configured;
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static volatile int calcFailures;

    private Bari() {
    }

    public static IBaritone get() {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (!configured) configure(b);
        return b;
    }

    private static void configure(IBaritone b) {
        configured = true;
        Settings s = BaritoneAPI.getSettings();
        s.chatControl.value = false;
        s.prefixControl.value = false;
        s.echoCommands.value = false;
        s.chatDebug.value = false;
        s.allowBreak.value = true;
        s.allowPlace.value = true;
        s.allowSprint.value = true;
        s.allowInventory.value = true;
        s.autoTool.value = true;
        s.allowParkour.value = false;
        s.freeLook.value = false;
        s.antiCheatCompatibility.value = true;
        s.renderPath.value = false;
        s.renderGoal.value = false;
        s.renderSelectionBoxes.value = false;
        s.desktopNotifications.value = false;
        s.disconnectOnArrival.value = false;
        s.censorCoordinates.value = false;
        s.followRadius.value = 2;
        s.logger.value = c -> {
            String msg = c.getString();
            SmartWhaleBridge.LOGGER.info("[baritone] {}", msg);
            synchronized (LOG) {
                LOG.addLast(msg);
                while (LOG.size() > 20) LOG.removeFirst();
            }
        };
        b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                if (event == PathEvent.CALC_FAILED) calcFailures++;
            }
        });
    }

    public static int calcFailures() {
        return calcFailures;
    }

    public static List<String> drainLog() {
        synchronized (LOG) {
            List<String> out = new ArrayList<>(LOG);
            LOG.clear();
            return out;
        }
    }

    /** True while any Baritone process is in control or a path is executing. */
    public static boolean busy() {
        IBaritone b = get();
        if (b.getPathingBehavior().isPathing()) return true;
        Optional<IBaritoneProcess> p = b.getPathingControlManager().mostRecentInControl();
        return p.isPresent() && p.get().isActive();
    }

    public static void cancel() {
        try {
            get().getPathingBehavior().cancelEverything();
        } catch (RuntimeException e) {
            SmartWhaleBridge.LOGGER.debug("Baritone cancel failed", e);
        }
    }
}
