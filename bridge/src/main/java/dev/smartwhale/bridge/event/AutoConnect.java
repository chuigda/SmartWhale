package dev.smartwhale.bridge.event;

import com.google.gson.JsonObject;
import dev.smartwhale.bridge.SmartWhaleBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Joins {@code -Dsmartwhale.bridge.autoconnect=host:port} from the title screen and
 * reconnects with backoff after a disconnect.
 */
public final class AutoConnect {
    private static final long MIN_BACKOFF_MS = 5_000;
    private static final long MAX_BACKOFF_MS = 120_000;

    private final String address;
    private long nextAttemptAt;
    private long backoffMs = MIN_BACKOFF_MS;
    private final GameEventsSink sink;

    public interface GameEventsSink {
        void notify(String method, JsonObject params);
    }

    public AutoConnect(String address, GameEventsSink sink) {
        this.address = address;
        this.sink = sink;
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof DisconnectedScreen screen) {
            JsonObject p = new JsonObject();
            p.addProperty("title", screen.getTitle().getString());
            p.addProperty("reason", collectText(screen));
            p.addProperty("retry_in_ms", backoffMs);
            // Also covers failures before login, where no LoggingOut/event.disconnected fires.
            sink.notify("event.disconnect_screen", p);
            SmartWhaleBridge.LOGGER.info("Disconnected ({}); reconnecting in {} ms", p.get("reason").getAsString(), backoffMs);
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            backoffMs = MIN_BACKOFF_MS;
            return;
        }
        Screen screen = mc.screen;
        boolean idle = screen instanceof TitleScreen || screen instanceof DisconnectedScreen
                || screen instanceof JoinMultiplayerScreen;
        if (!idle || System.currentTimeMillis() < nextAttemptAt) return;
        nextAttemptAt = System.currentTimeMillis() + backoffMs;
        connect(mc);
    }

    public void connect(Minecraft mc) {
        SmartWhaleBridge.LOGGER.info("Connecting to {}", address);
        ServerData data = new ServerData("SmartWhale", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), data, false, null);
    }

    private static String collectText(Screen screen) {
        StringBuilder sb = new StringBuilder();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w) {
                String s = w.getMessage().getString();
                if (!s.isBlank()) {
                    if (!sb.isEmpty()) sb.append(" | ");
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }
}
