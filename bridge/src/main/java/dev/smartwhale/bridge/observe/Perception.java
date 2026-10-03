package dev.smartwhale.bridge.observe;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Perception mode (docs/DESIGN.md §6.3): {@code visible} only reports blocks with an exposed face that
 * the bot could actually see; {@code omniscient} reports everything in loaded chunks.
 */
public final class Perception {
    public static final String MODE =
            System.getProperty("smartwhale.bridge.perception", "visible").toLowerCase(Locale.ROOT);
    private static final double NEAR_SQR = 4.0 * 4.0;

    private Perception() {
    }

    public static boolean omniscient() {
        return "omniscient".equals(MODE);
    }

    public static boolean exposed(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getBlockState(n).isSolidRender(level, n)) return true;
        }
        return false;
    }

    /** Exposed and either within 4 blocks or reachable by an unobstructed ray from the eyes. */
    public static boolean visible(LocalPlayer p, Level level, BlockPos pos) {
        if (!exposed(level, pos)) return false;
        Vec3 eye = p.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(center) <= NEAR_SQR) return true;
        if (rayHits(p, level, eye, center, pos)) return true;
        for (Direction d : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(d.getNormal());
            if (normal.dot(eye.subtract(center)) <= 0) continue;
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).isSolidRender(level, n)) continue;
            if (rayHits(p, level, eye, center.add(normal.scale(0.49)), pos)) return true;
        }
        return false;
    }

    private static boolean rayHits(LocalPlayer p, Level level, Vec3 from, Vec3 to, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    public static boolean perceivable(LocalPlayer p, Level level, BlockPos pos) {
        return omniscient() || visible(p, level, pos);
    }

    public record Found(BlockPos pos, BlockState state, double distSqr) {
    }

    /**
     * Nearest perceivable blocks matching {@code filter}, sorted by distance from the eyes.
     * Perception is evaluated lazily in distance order, so a small limit stays cheap.
     */
    public static List<Found> find(LocalPlayer p, Level level, Predicate<BlockState> filter, int radius, int limit,
                                   Predicate<BlockPos> exclude) {
        // Expanding passes keep common blocks (stone, dirt) from producing millions of candidates.
        int r = Math.min(radius, 16);
        while (true) {
            List<Found> out = findWithin(p, level, filter, r, limit, exclude);
            if (out.size() >= limit || r >= radius) return out;
            r = Math.min(radius, r * 2);
        }
    }

    private static List<Found> findWithin(LocalPlayer p, Level level, Predicate<BlockState> filter, int radius,
                                          int limit, Predicate<BlockPos> exclude) {
        List<Found> candidates = new ArrayList<>();
        Vec3 eye = p.getEyePosition();
        BlockScan.scan(level, p.blockPosition(), radius, filter, (pos, state) -> {
            if (exclude != null && exclude.test(pos)) return;
            candidates.add(new Found(pos.immutable(), state, eye.distanceToSqr(Vec3.atCenterOf(pos))));
        });
        candidates.sort(Comparator.comparingDouble(Found::distSqr));
        List<Found> out = new ArrayList<>();
        for (Found f : candidates) {
            if (out.size() >= limit) break;
            if (perceivable(p, level, f.pos())) out.add(f);
        }
        return out;
    }
}
