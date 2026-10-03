package dev.smartwhale.bridge.compat.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.smartwhale.bridge.action.Actions;
import dev.smartwhale.bridge.action.InputControl;
import dev.smartwhale.bridge.observe.Json;
import dev.smartwhale.bridge.observe.Perception;
import dev.smartwhale.bridge.rpc.RpcDispatcher;
import dev.smartwhale.bridge.rpc.RpcException;
import dev.smartwhale.bridge.task.Task;
import dev.smartwhale.bridge.task.TaskManager;
import dev.smartwhale.bridge.util.Game;
import dev.smartwhale.bridge.util.Matchers;
import dev.smartwhale.bridge.util.P;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** task.* backed by Baritone for movement (docs/DESIGN.md §6.3). */
public final class BaritoneTasks {
    private BaritoneTasks() {
    }

    public static void register(RpcDispatcher d, TaskManager tasks) {
        d.onMainThread("task.goto", p -> tasks.start(new GotoTask(p)));
        d.onMainThread("task.goto_block", p -> tasks.start(GotoTask.toBlock(p)));
        d.onMainThread("task.mine", p -> tasks.start(new MineTask(p)));
        d.onMainThread("task.follow", p -> tasks.start(new FollowTask(p)));
        d.onMainThread("task.explore", p -> tasks.start(new ExploreTask(p)));
        d.onMainThread("task.farm", p -> tasks.start(new FarmTask(p)));
        d.onMainThread("task.collect_items", p -> tasks.start(new CollectItemsTask(p)));
    }

    static int timeout(JsonObject p) {
        return P.clampInt(p, "timeout_s", 300, 5, 3600) * 20;
    }

    static RpcException target(String message, String hint) {
        return new RpcException(RpcException.INVALID_TARGET, message, hint);
    }

    static String lastLog() {
        List<String> log = Bari.drainLog();
        return log.isEmpty() ? null : String.join(" | ", log.subList(Math.max(0, log.size() - 3), log.size()));
    }

    /** Base for tasks that hand control to Baritone and wait until it goes idle. */
    abstract static class BaritoneTask extends Task {
        int calcFailuresAtStart;
        int idleTicks;

        BaritoneTask(String kind, int timeoutTicks) {
            super(kind, timeoutTicks);
        }

        @Override
        protected void start() {
            Bari.cancel();
            Bari.drainLog();
            calcFailuresAtStart = Bari.calcFailures();
            begin();
        }

        abstract void begin();

        boolean calcFailed() {
            return Bari.calcFailures() > calcFailuresAtStart;
        }

        /** True once Baritone has been idle for a few ticks (it briefly idles between path segments). */
        boolean baritoneDone() {
            if (ticks() < 10) return false;
            if (Bari.busy()) {
                idleTicks = 0;
                return false;
            }
            return ++idleTicks >= 5;
        }

        Outcome failNoPath(String fallbackReason, String hint) {
            String log = lastLog();
            String h = log != null ? (hint != null ? hint + "; " : "") + "baritone: " + log : hint;
            return Outcome.fail(calcFailed() ? "no_path" : fallbackReason, h);
        }

        @Override
        protected void stop() {
            Bari.cancel();
        }
    }

    static final class GotoTask extends BaritoneTask {
        final Goal goal;
        final BlockPos target;
        JsonObject info;

        GotoTask(JsonObject p) {
            super("goto", timeout(p));
            Game.player();
            if (P.has(p, "pos")) {
                target = P.blockPos(p, "pos");
                int range = P.clampInt(p, "range", 0, 0, 64);
                goal = range > 0 ? new GoalNear(target, range) : new GoalBlock(target);
            } else if (P.has(p, "xz")) {
                JsonObject xz = P.obj(p, "xz");
                int x = (int) Math.floor(P.num(xz, "x"));
                int z = (int) Math.floor(P.num(xz, "z"));
                target = new BlockPos(x, 0, z);
                goal = new GoalXZ(x, z);
            } else if (P.has(p, "y")) {
                target = null;
                goal = new GoalYLevel(P.integer(p, "y"));
            } else {
                throw P.invalid("Give pos, xz or y");
            }
        }

        private GotoTask(String kind, Goal goal, BlockPos target, JsonObject info, int timeout) {
            super(kind, timeout);
            this.goal = goal;
            this.target = target;
            this.info = info;
        }

        /** task.goto_block: walk next to the nearest perceivable matching block (up to 8 candidates). */
        static GotoTask toBlock(JsonObject p) {
            LocalPlayer player = Game.player();
            Matchers.Blocks match = Matchers.blocks(P.strList(p, "blocks"));
            int radius = P.clampInt(p, "radius", 64, 1, 64);
            List<Perception.Found> found = Perception.find(player, Game.level(), match, radius, 8, null);
            if (found.isEmpty()) {
                throw target("No " + String.join("/", match.spec()) + " found within " + radius + " blocks",
                        Perception.omniscient() ? "Explore further with task.explore"
                                : "Only blocks you can see count; explore with task.explore or look around");
            }
            Goal[] goals = found.stream().map(f -> new GoalGetToBlock(f.pos())).toArray(Goal[]::new);
            JsonObject info = new JsonObject();
            info.add("target", Json.blockPos(found.get(0).pos()));
            info.addProperty("target_id", Game.id(found.get(0).state().getBlock()));
            return new GotoTask("goto_block", new GoalComposite(goals), found.get(0).pos(), info, timeout(p));
        }

        @Override
        protected JsonObject startInfo() {
            return info;
        }

        @Override
        void begin() {
            Bari.get().getCustomGoalProcess().setGoalAndPath(goal);
        }

        @Override
        protected Outcome tick() {
            if (!baritoneDone()) return null;
            LocalPlayer p = Minecraft.getInstance().player;
            if (goal.isInGoal(p.blockPosition())) return Outcome.success();
            return failNoPath("not_reached", "Baritone stopped before reaching the goal");
        }

        @Override
        protected JsonObject progress() {
            if (target == null || goal instanceof GoalXZ) return null;
            JsonObject o = new JsonObject();
            o.addProperty("distance", Game.distance(Minecraft.getInstance().player, target));
            return o;
        }
    }

    /**
     * task.mine: our own loop with Baritone only for walking, so the perception mode applies:
     * pick the nearest perceivable block, walk until it is in reach and visible, break it, collect drops.
     * {@code count} is the number of blocks to break.
     */
    static final class MineTask extends BaritoneTask {
        enum State { SELECT, APPROACH, BREAK, COLLECT }

        final Matchers.Blocks match;
        final int count;
        final int radius;
        final Set<BlockPos> blacklist = new HashSet<>();
        State state = State.SELECT;
        BlockPos target;
        BlockPos lastBroken;
        InputControl.Breaking breaking;
        ItemEntity drop;
        int stateTicks;
        int mined;
        boolean pathing;

        MineTask(JsonObject p) {
            super("mine", timeout(p));
            LocalPlayer player = Game.player();
            match = Matchers.blocks(P.strList(p, "blocks"));
            count = P.clampInt(p, "count", 1, 1, 256);
            radius = P.clampInt(p, "radius", 32, 4, 64);
            if (Perception.find(player, Game.level(), match, radius, 1, null).isEmpty()) {
                throw target("No " + String.join("/", match.spec()) + " found within " + radius + " blocks",
                        Perception.omniscient() ? "Explore further with task.explore"
                                : "Only blocks you can see can be mined; explore, look around or dig");
            }
        }

        @Override
        void begin() {
        }

        private void enter(State s) {
            state = s;
            stateTicks = 0;
            pathing = false;
            idleTicks = 0;
        }

        @Override
        protected Outcome tick() {
            LocalPlayer p = Minecraft.getInstance().player;
            ClientLevel level = Minecraft.getInstance().level;
            stateTicks++;
            switch (state) {
                case SELECT -> {
                    if (mined >= count) return done();
                    List<Perception.Found> found = Perception.find(p, level, match, radius, 1, blacklist::contains);
                    if (found.isEmpty()) {
                        JsonObject extra = summary();
                        return Outcome.fail(mined > 0 ? "not_enough" : "not_found",
                                "No more reachable " + String.join("/", match.spec()) + " in sight", extra);
                    }
                    target = found.get(0).pos();
                    enter(State.APPROACH);
                }
                case APPROACH -> {
                    if (!match.test(level.getBlockState(target))) {
                        enter(State.SELECT);
                        return null;
                    }
                    if (p.canInteractWithBlock(target, 0) && Perception.visible(p, level, target)) {
                        Bari.cancel();
                        try {
                            breaking = Actions.startBreak(p, target, true);
                            enter(State.BREAK);
                        } catch (RpcException e) {
                            giveUp();
                        }
                        return null;
                    }
                    if (!pathing) {
                        Bari.get().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
                        pathing = true;
                    }
                    if (stateTicks > 1200 || stateTicks > 10 && baritoneDone()) giveUp();
                }
                case BREAK -> {
                    TaskManager.get().touch();
                    if (!breaking.future().isDone()) return null;
                    if (breaking.future().getNow(false)) {
                        mined++;
                        lastBroken = target;
                        enter(State.COLLECT);
                    } else {
                        giveUp();
                    }
                }
                case COLLECT -> {
                    if (drop == null || !drop.isAlive()) {
                        drop = nearestDrop(p, level);
                        pathing = false;
                    }
                    if (drop == null) {
                        // Drops appear a tick or two after the block breaks.
                        if (stateTicks > 10) enter(State.SELECT);
                        return null;
                    }
                    if (stateTicks > 100) {
                        drop = null;
                        enter(State.SELECT);
                        return null;
                    }
                    if (p.distanceTo(drop) > 1.0 && (!pathing || !Bari.busy())) {
                        Bari.get().getCustomGoalProcess().setGoalAndPath(new GoalBlock(drop.blockPosition()));
                        pathing = true;
                    }
                }
            }
            return null;
        }

        private void giveUp() {
            blacklist.add(target);
            Bari.cancel();
            enter(State.SELECT);
        }

        private ItemEntity nearestDrop(LocalPlayer p, ClientLevel level) {
            if (lastBroken == null) return null;
            Vec3 c = Vec3.atCenterOf(lastBroken);
            return level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(lastBroken).inflate(4),
                            e -> e.isAlive() && e.position().distanceToSqr(c) <= 25)
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
        }

        private JsonObject summary() {
            JsonObject o = new JsonObject();
            o.addProperty("mined", mined);
            o.addProperty("requested", count);
            return o;
        }

        private Outcome done() {
            return Outcome.success(summary());
        }

        @Override
        protected void stop() {
            super.stop();
            InputControl.cancelBreaking("task ended");
        }

        @Override
        protected JsonObject progress() {
            JsonObject o = summary();
            o.addProperty("state", state.name().toLowerCase());
            if (target != null) o.add("target", Json.blockPos(target));
            return o;
        }
    }

    static final class FollowTask extends BaritoneTask {
        final Integer entityId;
        final String playerName;
        int lostTicks;

        FollowTask(JsonObject p) {
            super("follow", 0);
            Game.player();
            if (P.has(p, "entity_id")) {
                entityId = P.integer(p, "entity_id");
                playerName = null;
                if (Game.level().getEntity(entityId) == null) throw target("No such entity nearby", "Check observe.entities");
            } else if (P.has(p, "player")) {
                entityId = null;
                playerName = P.str(p, "player");
                if (findPlayer() == null) throw target("Player " + playerName + " is not nearby", "Check observe.players");
            } else {
                throw P.invalid("Give entity_id or player");
            }
            openEnded(P.clampInt(p, "duration_s", 300, 5, 3600) * 20);
            noStuckCheck();
        }

        private Entity findPlayer() {
            for (Player pl : Game.level().players()) {
                if (pl.getGameProfile().getName().equalsIgnoreCase(playerName)) return pl;
            }
            return null;
        }

        private Entity current() {
            if (entityId != null) {
                Entity e = Minecraft.getInstance().level.getEntity(entityId);
                return e != null && e.isAlive() ? e : null;
            }
            return findPlayer();
        }

        @Override
        void begin() {
            Predicate<Entity> filter = entityId != null
                    ? e -> e.getId() == entityId
                    : e -> e instanceof Player pl && pl.getGameProfile().getName().equalsIgnoreCase(playerName);
            Bari.get().getFollowProcess().follow(filter);
        }

        @Override
        protected Outcome tick() {
            if (current() != null) {
                lostTicks = 0;
                return null;
            }
            return ++lostTicks > 100 ? Outcome.fail("target_lost", "The target is gone or out of range") : null;
        }

        @Override
        protected JsonObject progress() {
            Entity e = current();
            if (e == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("distance", Json.round(e.distanceTo(Minecraft.getInstance().player)));
            return o;
        }
    }

    static final class ExploreTask extends BaritoneTask {
        final int x;
        final int z;

        ExploreTask(JsonObject p) {
            super("explore", 0);
            LocalPlayer player = Game.player();
            if (P.has(p, "origin")) {
                JsonObject o = P.obj(p, "origin");
                x = (int) Math.floor(P.num(o, "x"));
                z = (int) Math.floor(P.num(o, "z"));
            } else {
                x = player.getBlockX();
                z = player.getBlockZ();
            }
            openEnded(P.clampInt(p, "duration_s", 60, 5, 1800) * 20);
        }

        @Override
        void begin() {
            Bari.get().getExploreProcess().explore(x, z);
        }

        @Override
        protected Outcome tick() {
            return baritoneDone() ? failNoPath("stopped", "Exploration stopped early") : null;
        }
    }

    static final class FarmTask extends BaritoneTask {
        final int range;

        FarmTask(JsonObject p) {
            super("farm", 0);
            Game.player();
            range = P.clampInt(p, "range", 16, 4, 64);
            openEnded(P.clampInt(p, "duration_s", 120, 5, 1800) * 20);
        }

        @Override
        void begin() {
            Bari.get().getFarmProcess().farm(range);
        }

        @Override
        protected Outcome tick() {
            // Baritone goes idle when there's nothing left to harvest or replant.
            return baritoneDone() ? Outcome.success() : null;
        }
    }

    static final class CollectItemsTask extends BaritoneTask {
        final int radius;
        final Vec3 origin;
        final Set<Integer> blacklist = new HashSet<>();
        ItemEntity target;
        int targetTicks;
        int noneTicks;
        int collected;

        CollectItemsTask(JsonObject p) {
            super("collect_items", timeout(p));
            LocalPlayer player = Game.player();
            radius = P.clampInt(p, "radius", 8, 1, 32);
            origin = player.position();
            if (nearest(player) == null) throw target("No dropped items within " + radius + " blocks", null);
        }

        private ItemEntity nearest(LocalPlayer p) {
            return p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(radius + 8),
                            e -> e.isAlive() && !blacklist.contains(e.getId())
                                    && e.position().distanceToSqr(origin) <= (double) radius * radius)
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
        }

        @Override
        void begin() {
        }

        @Override
        protected Outcome tick() {
            LocalPlayer p = Minecraft.getInstance().player;
            if (target != null && !target.isAlive()) {
                collected++;
                target = null;
            }
            if (target == null) {
                target = nearest(p);
                targetTicks = 0;
                if (target == null) {
                    if (++noneTicks > 20) {
                        JsonObject o = new JsonObject();
                        o.addProperty("collected_stacks", collected);
                        JsonArray skipped = new JsonArray();
                        blacklist.forEach(skipped::add);
                        if (!skipped.isEmpty()) o.add("unreachable_entity_ids", skipped);
                        return Outcome.success(o);
                    }
                    return null;
                }
                noneTicks = 0;
                Bari.get().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target.blockPosition()));
            }
            if (++targetTicks > 200) {
                blacklist.add(target.getId());
                target = null;
                Bari.cancel();
            } else if (targetTicks % 40 == 0 && !Bari.busy() && p.distanceTo(target) > 1.0) {
                Bari.get().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target.blockPosition()));
            }
            return null;
        }
    }
}
