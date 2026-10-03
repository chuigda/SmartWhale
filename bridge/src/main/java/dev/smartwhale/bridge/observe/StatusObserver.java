package dev.smartwhale.bridge.observe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.task.TaskManager;
import dev.smartwhale.bridge.util.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class StatusObserver {
    private StatusObserver() {
    }

    public static JsonObject status() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        JsonObject r = new JsonObject();
        r.addProperty("name", mc.getUser().getName());
        r.addProperty("in_world", p != null && level != null);
        ServerData server = mc.getCurrentServer();
        if (server != null) r.addProperty("server", server.ip);
        if (mc.screen != null) {
            JsonObject screen = new JsonObject();
            screen.addProperty("class", mc.screen.getClass().getName());
            screen.addProperty("title", mc.screen.getTitle().getString());
            r.add("screen", screen);
        }
        if (p == null || level == null) return r;

        r.addProperty("uuid", p.getUUID().toString());
        r.addProperty("dimension", level.dimension().location().toString());
        r.add("pos", Json.vec(p.getX(), p.getY(), p.getZ()));
        BlockPos bp = p.blockPosition();
        r.add("block_pos", Json.blockPos(bp));
        r.addProperty("yaw", Json.round(p.getYRot()));
        r.addProperty("pitch", Json.round(p.getXRot()));
        r.addProperty("health", Json.round(p.getHealth()));
        r.addProperty("max_health", Json.round(p.getMaxHealth()));
        r.addProperty("food", p.getFoodData().getFoodLevel());
        r.addProperty("saturation", Json.round(p.getFoodData().getSaturationLevel()));
        r.addProperty("xp_level", p.experienceLevel);
        r.addProperty("armor", p.getArmorValue());
        r.addProperty("on_ground", p.onGround());
        r.addProperty("in_water", p.isInWater());
        r.addProperty("on_fire", p.isOnFire());
        r.addProperty("dead", p.isDeadOrDying());
        level.getBiome(bp).unwrapKey().ifPresent(k -> r.addProperty("biome", k.location().toString()));
        r.addProperty("light", level.getMaxLocalRawBrightness(bp));
        r.addProperty("game_time", level.getGameTime());
        long dayTime = level.getDayTime() % 24000L;
        r.addProperty("day_time", dayTime);
        r.addProperty("day", level.getDayTime() / 24000L);
        r.addProperty("phase", phase(dayTime));
        r.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        r.add("mainhand", Game.item(p.getMainHandItem()));
        r.add("offhand", Game.item(p.getOffhandItem()));
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject eo = new JsonObject();
            e.getEffect().unwrapKey().ifPresent(k -> eo.addProperty("id", k.location().toString()));
            eo.addProperty("amplifier", e.getAmplifier());
            eo.addProperty("duration_ticks", e.getDuration());
            effects.add(eo);
        }
        r.add("effects", effects);
        TaskManager tasks = TaskManager.get();
        if (tasks != null && tasks.currentSummary() != null) r.add("task", tasks.currentSummary());
        return r;
    }

    private static String phase(long t) {
        if (t < 1000) return "dawn";
        if (t < 12000) return "day";
        if (t < 13000) return "dusk";
        if (t < 23000) return "night";
        return "dawn";
    }
}
