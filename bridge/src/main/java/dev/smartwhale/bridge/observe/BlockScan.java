package dev.smartwhale.bridge.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Section-wise scan of loaded chunks in a sphere; skips air-only sections and sections whose palette can't match. */
public final class BlockScan {
    private BlockScan() {
    }

    /** Calls {@code out} with a mutable position for every non-air block (matching {@code filter}, if given). */
    public static void scan(Level level, BlockPos center, int radius, Predicate<BlockState> filter,
                            BiConsumer<BlockPos, BlockState> out) {
        int r2 = radius * radius;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    if (section == null || section.hasOnlyAir()) continue;
                    if (filter != null && !section.maybeHas(filter)) continue;
                    for (int y = 0; y < 16; y++) {
                        int wy = baseY + y;
                        if (wy < minY || wy > maxY) continue;
                        int dy = wy - center.getY();
                        for (int z = 0; z < 16; z++) {
                            int wz = (cz << 4) + z;
                            int dz = wz - center.getZ();
                            for (int x = 0; x < 16; x++) {
                                int wx = (cx << 4) + x;
                                int dx = wx - center.getX();
                                if (dx * dx + dy * dy + dz * dz > r2) continue;
                                BlockState state = section.getBlockState(x, y, z);
                                if (state.isAir()) continue;
                                if (filter != null && !filter.test(state)) continue;
                                out.accept(pos.set(wx, wy, wz), state);
                            }
                        }
                    }
                }
            }
        }
    }
}
