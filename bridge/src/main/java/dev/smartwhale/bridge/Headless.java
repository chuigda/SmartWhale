package dev.smartwhale.bridge;

/**
 * Headless mode keeps a real GL context (some mods, e.g. YSM, need one) but never shows the window
 * and skips rendering. Enabled by the agent's launcher via {@code -Dsmartwhale.bridge.headless=true};
 * attach mode against a normal client leaves it off.
 */
public final class Headless {
    public static final boolean ENABLED = Boolean.getBoolean("smartwhale.bridge.headless");

    private Headless() {}
}
