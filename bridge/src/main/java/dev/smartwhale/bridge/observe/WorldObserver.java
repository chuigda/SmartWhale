package dev.smartwhale.bridge.observe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.rpc.RpcException;
import dev.smartwhale.bridge.util.Game;
import dev.smartwhale.bridge.util.Matchers;
import dev.smartwhale.bridge.util.P;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** observe.blocks / find_blocks / block / entities / players. */
public final class WorldObserver {
    private WorldObserver() {
    }

    private static final class Summary {
        int count;
        BlockPos nearest;
        double nearestSqr = Double.MAX_VALUE;
    }

    public static JsonObject blocks(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        int radius = P.clampInt(params, "radius", 8, 1, 16);
        String mode = P.optStr(params, "mode", "summary");
        Predicate<BlockState> filter = P.has(params, "blocks") ? Matchers.blocks(P.strList(params, "blocks")) : null;
        Vec3 eye = p.getEyePosition();
        JsonObject r = new JsonObject();
        r.addProperty("radius", radius);
        r.addProperty("perception", Perception.MODE);

        if ("list".equals(mode)) {
            int limit = P.clampInt(params, "limit", 100, 1, 500);
            List<Perception.Found> found = Perception.find(p, level, filter != null ? filter : s -> true, radius, limit, null);
            JsonArray list = new JsonArray();
            for (Perception.Found f : found) list.add(found(f));
            r.add("blocks", list);
            r.addProperty("truncated", found.size() >= limit);
            return r;
        }
        if (!"summary".equals(mode)) throw P.invalid("mode must be summary or list");

        Map<Block, Summary> summary = new HashMap<>();
        BlockScan.scan(level, p.blockPosition(), radius, filter, (pos, state) -> {
            if (!Perception.perceivable(p, level, pos)) return;
            Summary s = summary.computeIfAbsent(state.getBlock(), b -> new Summary());
            s.count++;
            double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < s.nearestSqr) {
                s.nearestSqr = d;
                s.nearest = pos.immutable();
            }
        });
        List<Map.Entry<Block, Summary>> entries = new ArrayList<>(summary.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<Block, Summary> e) -> -e.getValue().count));
        int limit = P.clampInt(params, "limit", 40, 1, 200);
        JsonArray list = new JsonArray();
        for (Map.Entry<Block, Summary> e : entries.subList(0, Math.min(limit, entries.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("id", Game.id(e.getKey()));
            o.addProperty("count", e.getValue().count);
            o.add("nearest", Json.blockPos(e.getValue().nearest));
            o.addProperty("distance", Json.round(Math.sqrt(e.getValue().nearestSqr)));
            list.add(o);
        }
        r.add("blocks", list);
        r.addProperty("kinds", entries.size());
        return r;
    }

    public static JsonObject findBlocks(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        Matchers.Blocks filter = Matchers.blocks(P.strList(params, "blocks"));
        int radius = P.clampInt(params, "radius", 32, 1, 64);
        int limit = P.clampInt(params, "limit", 10, 1, 64);
        List<Perception.Found> found = Perception.find(p, level, filter, radius, limit, null);
        JsonObject r = new JsonObject();
        JsonArray list = new JsonArray();
        for (Perception.Found f : found) list.add(found(f));
        r.add("blocks", list);
        r.addProperty("perception", Perception.MODE);
        if (found.isEmpty() && !Perception.omniscient()) {
            r.addProperty("hint", "Only blocks you can currently see are listed; move around, explore or dig to find more");
        }
        return r;
    }

    private static JsonObject found(Perception.Found f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", Game.id(f.state().getBlock()));
        o.add("pos", Json.blockPos(f.pos()));
        o.addProperty("distance", Json.round(Math.sqrt(f.distSqr())));
        return o;
    }

    public static JsonObject block(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos pos = P.blockPos(params, "pos");
        if (!level.isLoaded(pos)) throw new RpcException(RpcException.INVALID_TARGET, "Chunk not loaded");
        boolean visible = Perception.visible(p, level, pos);
        if (!Perception.omniscient() && !visible) {
            throw new RpcException(RpcException.INVALID_TARGET, "You can't see that block",
                    "Only visible blocks can be inspected; get closer or clear the line of sight");
        }
        BlockState state = level.getBlockState(pos);
        JsonObject r = new JsonObject();
        r.addProperty("id", Game.id(state.getBlock()));
        r.addProperty("name", state.getBlock().getName().getString());
        r.add("pos", Json.blockPos(pos));
        JsonObject props = new JsonObject();
        for (Property<?> prop : state.getProperties()) {
            props.addProperty(prop.getName(), String.valueOf(state.getValue(prop)).toLowerCase(Locale.ROOT));
        }
        r.add("properties", props);
        r.addProperty("has_block_entity", state.hasBlockEntity());
        r.addProperty("distance", Game.distance(p, pos));
        r.addProperty("reachable", p.canInteractWithBlock(pos, 0));
        r.addProperty("visible", visible);
        float hardness = state.getDestroySpeed(level, pos);
        r.addProperty("hardness", hardness);
        r.addProperty("needs_tool", state.requiresCorrectToolForDrops());
        return r;
    }

    public static JsonObject entities(JsonObject params) {
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        int radius = P.clampInt(params, "radius", 16, 1, 64);
        int limit = P.clampInt(params, "limit", 50, 1, 200);
        Predicate<Entity> filter = entityFilter(P.optStr(params, "filter", null));
        List<Entity> list = level.getEntities(p, p.getBoundingBox().inflate(radius),
                e -> e.isAlive() && e.distanceToSqr(p) <= (double) radius * radius && filter.test(e));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        JsonArray out = new JsonArray();
        for (Entity e : list.subList(0, Math.min(limit, list.size()))) out.add(entity(p, e));
        JsonObject r = new JsonObject();
        r.add("entities", out);
        r.addProperty("total", list.size());
        return r;
    }

    private static Predicate<Entity> entityFilter(String filter) {
        if (filter == null || filter.isEmpty()) return e -> true;
        return switch (filter) {
            case "hostile" -> e -> e instanceof Enemy;
            case "player" -> e -> e instanceof Player;
            case "item" -> e -> e instanceof ItemEntity;
            case "living" -> e -> e instanceof LivingEntity;
            default -> e -> Game.id(e).equals(filter);
        };
    }

    public static JsonObject entity(LocalPlayer p, Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("type", Game.id(e));
        o.addProperty("name", e.getName().getString());
        o.addProperty("distance", Json.round(e.distanceTo(p)));
        o.add("pos", Json.vec(e.getX(), e.getY(), e.getZ()));
        if (e instanceof LivingEntity le) {
            o.addProperty("health", Json.round(le.getHealth()));
            o.addProperty("max_health", Json.round(le.getMaxHealth()));
        }
        if (e instanceof Enemy) o.addProperty("hostile", true);
        if (e instanceof Player) o.addProperty("player", true);
        if (e instanceof ItemEntity ie) o.add("item", Game.item(ie.getItem()));
        o.addProperty("visible", p.hasLineOfSight(e));
        o.addProperty("reachable", p.canInteractWithEntity(e, 0));
        return o;
    }

    public static JsonObject players(JsonObject params) {
        LocalPlayer self = Game.player();
        ClientLevel level = Game.level();
        ClientPacketListener conn = self.connection;
        JsonArray out = new JsonArray();
        for (PlayerInfo info : conn.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", info.getProfile().getName());
            o.addProperty("uuid", info.getProfile().getId().toString());
            o.addProperty("latency_ms", info.getLatency());
            if (info.getGameMode() != null) o.addProperty("game_mode", info.getGameMode().getName());
            if (info.getProfile().getId().equals(self.getUUID())) o.addProperty("self", true);
            Player near = level.getPlayerByUUID(info.getProfile().getId());
            if (near != null && near != self) {
                o.addProperty("distance", Json.round(near.distanceTo(self)));
                o.add("pos", Json.vec(near.getX(), near.getY(), near.getZ()));
                o.addProperty("entity_id", near.getId());
            }
            out.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("players", out);
        return r;
    }
}
