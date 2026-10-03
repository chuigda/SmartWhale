package dev.smartwhale.bridge.event;

import com.google.gson.JsonObject;
import dev.smartwhale.bridge.server.BridgeServer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import dev.smartwhale.bridge.observe.Json;
import dev.smartwhale.bridge.observe.ScreenObserver;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Translates client game events into JSON-RPC notifications (docs/DESIGN.md §6.4). */
public final class GameEvents {
    private final BridgeServer server;

    public GameEvents(BridgeServer server) {
        this.server = server;
    }

    private boolean inWorld;
    private boolean wasDead;
    private float lastHealth = -1;
    private float pendingDamage;
    private int hurtAge;
    private String lastPhase;
    private Set<String> knownPlayers;
    private int tick;

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        inWorld = true;
        wasDead = false;
        lastHealth = -1;
        lastPhase = null;
        knownPlayers = null;
        JsonObject p = new JsonObject();
        ServerData data = mc.getCurrentServer();
        if (data != null) p.addProperty("server", data.ip);
        p.addProperty("dimension", event.getPlayer().level().dimension().location().toString());
        server.notify("event.world_ready", p);
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // NeoForge also fires LoggingOut when a connection screen opens without a world; ignore that.
        if (!inWorld) return;
        inWorld = false;
        server.notify("event.disconnected", new JsonObject());
    }

    @SubscribeEvent
    public void onClone(ClientPlayerNetworkEvent.Clone event) {
        LocalPlayer p = event.getNewPlayer();
        JsonObject o = new JsonObject();
        o.add("pos", Json.vec(p.getX(), p.getY(), p.getZ()));
        o.addProperty("dimension", p.level().dimension().location().toString());
        lastHealth = -1;
        if (wasDead) {
            wasDead = false;
            server.notify("event.respawned", o);
        } else {
            server.notify("event.dimension_changed", o);
        }
    }

    @SubscribeEvent
    public void onScreenOpen(ScreenEvent.Opening event) {
        Screen screen = event.getNewScreen();
        if (screen == null) return;
        if (screen instanceof DeathScreen death) {
            onDeath(death);
            return;
        }
        server.notify("event.screen_opened", describe(screen));
    }

    @SubscribeEvent
    public void onScreenClose(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof DeathScreen) return;
        server.notify("event.screen_closed", describe(event.getScreen()));
    }

    private static JsonObject describe(Screen screen) {
        JsonObject o = new JsonObject();
        o.addProperty("title", screen.getTitle().getString());
        o.addProperty("class", screen.getClass().getName());
        if (screen instanceof AbstractContainerScreen<?> cs) o.addProperty("menu_type", ScreenObserver.menuType(cs.getMenu()));
        return o;
    }

    private void onDeath(DeathScreen screen) {
        if (wasDead) return;
        wasDead = true;
        JsonObject o = new JsonObject();
        String cause = deathMessage(screen);
        if (cause != null) o.addProperty("message", cause);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            o.add("pos", Json.vec(p.getX(), p.getY(), p.getZ()));
            o.addProperty("dimension", p.level().dimension().location().toString());
        }
        server.notify("event.death", o);
    }

    private static String deathMessage(DeathScreen screen) {
        // DeathScreen keeps the cause in a private Component field; the first declared one is causeOfDeath.
        for (Field f : DeathScreen.class.getDeclaredFields()) {
            if (f.getType() != Component.class) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(screen);
                return v != null ? ((Component) v).getString() : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        tick++;

        JsonObject picked = PickupTracker.tick();
        if (picked != null) server.notify("event.item_picked", picked);

        float health = p.getHealth();
        if (lastHealth >= 0 && health < lastHealth - 0.01F) {
            if (pendingDamage == 0) hurtAge = 0;
            pendingDamage += lastHealth - health;
        }
        lastHealth = health;
        // Coalesce damage over half a second (fire, cactus, swarms).
        if (pendingDamage > 0 && ++hurtAge >= 10) {
            JsonObject o = new JsonObject();
            o.addProperty("amount", Json.round(pendingDamage));
            o.addProperty("health", Json.round(health));
            DamageSource src = p.getLastDamageSource();
            if (src != null) {
                o.addProperty("source", src.type().msgId());
                if (src.getEntity() != null) {
                    o.addProperty("attacker", src.getEntity().getName().getString());
                    o.addProperty("attacker_id", src.getEntity().getId());
                    o.addProperty("attacker_type", Game.id(src.getEntity()));
                }
            }
            pendingDamage = 0;
            server.notify("event.hurt", o);
        }

        long dayTime = mc.level.getDayTime() % 24000L;
        String phase = dayTime < 1000 || dayTime >= 23000 ? "dawn" : dayTime < 12000 ? "day" : dayTime < 13000 ? "dusk" : "night";
        if (lastPhase != null && !phase.equals(lastPhase) && (phase.equals("dawn") || phase.equals("dusk"))) {
            JsonObject o = new JsonObject();
            o.addProperty("phase", phase);
            server.notify("event.time", o);
        }
        lastPhase = phase;

        if (tick % 20 == 0) {
            Set<String> now = new HashSet<>();
            for (PlayerInfo info : p.connection.getOnlinePlayers()) now.add(info.getProfile().getName());
            if (knownPlayers != null) {
                for (String name : now) if (!knownPlayers.contains(name)) playerEvent("event.player_joined", name);
                for (String name : knownPlayers) if (!now.contains(name)) playerEvent("event.player_left", name);
            }
            knownPlayers = now;
        }
    }

    private void playerEvent(String method, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        server.notify(method, o);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        String self = mc.getUser().getName();
        JsonObject p = new JsonObject();
        String message = event.getMessage().getString();
        UUID sender = event.getSender();
        String kind = "system";
        if (event instanceof ClientChatReceivedEvent.Player player) {
            kind = "player";
            message = player.getPlayerChatMessage().signedContent();
            String chatType = event.getBoundChatType() != null
                    ? event.getBoundChatType().chatType().unwrapKey().map(k -> k.location().getPath()).orElse("")
                    : "";
            if (chatType.startsWith("msg_command")) kind = "whisper";
        }
        if (sender != null && !Util.NIL_UUID.equals(sender)) {
            p.addProperty("sender_uuid", sender.toString());
            ClientPacketListener conn = mc.getConnection();
            PlayerInfo info = conn != null ? conn.getPlayerInfo(sender) : null;
            if (info != null) {
                p.addProperty("sender", info.getProfile().getName());
                if (info.getProfile().getName().equals(self)) return;
            }
        }
        p.addProperty("kind", kind);
        p.addProperty("message", message);
        p.addProperty("raw", event.getMessage().getString());
        p.addProperty("mentions_me", "whisper".equals(kind)
                || message.toLowerCase(Locale.ROOT).contains(self.toLowerCase(Locale.ROOT)));
        server.notify("event.chat", p);
    }
}
