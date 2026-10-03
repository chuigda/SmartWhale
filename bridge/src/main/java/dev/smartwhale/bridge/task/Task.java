package dev.smartwhale.bridge.task;

import com.google.gson.JsonObject;

/**
 * A long-running activity advanced by {@link TaskManager} on the client tick (docs/DESIGN.md §6.3 task.*).
 * Lifecycle: {@link #start()} once, {@link #tick()} every tick until it returns an outcome, then {@link #stop()}.
 */
public abstract class Task {
    public record Outcome(boolean ok, String reason, String hint, JsonObject extra) {
        public static Outcome success() {
            return new Outcome(true, null, null, null);
        }

        public static Outcome success(JsonObject extra) {
            return new Outcome(true, null, null, extra);
        }

        public static Outcome fail(String reason, String hint) {
            return new Outcome(false, reason, hint, null);
        }

        public static Outcome fail(String reason, String hint, JsonObject extra) {
            return new Outcome(false, reason, hint, extra);
        }
    }

    final String kind;
    /** Hard limit; 0 = none. */
    int timeoutTicks;
    /** Open-ended tasks (follow, explore, farm) end successfully after this many ticks; 0 = not open-ended. */
    int durationTicks;
    boolean stuckCheck = true;
    String id;
    int ticks;

    protected Task(String kind, int timeoutTicks) {
        this.kind = kind;
        this.timeoutTicks = timeoutTicks;
    }

    public String kind() {
        return kind;
    }

    protected int ticks() {
        return ticks;
    }

    protected void openEnded(int durationTicks) {
        this.durationTicks = durationTicks;
        if (timeoutTicks > 0 && timeoutTicks < durationTicks) timeoutTicks = durationTicks;
    }

    protected void noStuckCheck() {
        stuckCheck = false;
    }

    /** Extra fields for the start response (e.g. the chosen target). */
    protected JsonObject startInfo() {
        return null;
    }

    /** May throw RpcException to reject the request. */
    protected abstract void start();

    /** null while running. */
    protected abstract Outcome tick();

    /** Cleanup; called exactly once after the task ends for any reason. */
    protected void stop() {
    }

    /** Short progress description for task.status / observe.status. */
    protected JsonObject progress() {
        return null;
    }
}
