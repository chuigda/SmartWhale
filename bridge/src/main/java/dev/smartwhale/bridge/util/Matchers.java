package dev.smartwhale.bridge.util;

import dev.smartwhale.bridge.rpc.RpcException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Parses {@code ["minecraft:oak_log", "#minecraft:logs"]} style id/tag lists (docs/DESIGN.md §6.1). */
public final class Matchers {
    private Matchers() {
    }

    public record Blocks(Set<Block> set, List<String> spec) implements Predicate<BlockState> {
        @Override
        public boolean test(BlockState state) {
            return set.contains(state.getBlock());
        }
    }

    public record Items(Set<Item> set, List<String> spec) implements Predicate<ItemStack> {
        @Override
        public boolean test(ItemStack stack) {
            return !stack.isEmpty() && set.contains(stack.getItem());
        }
    }

    public static Blocks blocks(List<String> spec) {
        return new Blocks(resolve(spec, BuiltInRegistries.BLOCK, Registries.BLOCK, "block"), spec);
    }

    public static Items items(List<String> spec) {
        return new Items(resolve(spec, BuiltInRegistries.ITEM, Registries.ITEM, "item"), spec);
    }

    private static <T> Set<T> resolve(List<String> spec, Registry<T> registry,
                                      ResourceKey<? extends Registry<T>> key, String kind) {
        Set<T> out = new LinkedHashSet<>();
        for (String s : spec) {
            boolean tag = s.startsWith("#");
            ResourceLocation rl = ResourceLocation.tryParse(tag ? s.substring(1) : s);
            if (rl == null) throw P.invalid("Malformed " + kind + " id: " + s);
            if (tag) {
                var holders = registry.getTag(TagKey.create(key, rl));
                if (holders.isEmpty()) throw unknown(kind + " tag", s);
                for (Holder<T> h : holders.get()) out.add(h.value());
            } else {
                if (!registry.containsKey(rl)) throw unknown(kind, s);
                out.add(registry.get(rl));
            }
        }
        return out;
    }

    private static RpcException unknown(String what, String s) {
        return new RpcException(RpcException.INVALID_PARAMS, "Unknown " + what + ": " + s,
                "Use a full registry id like minecraft:oak_log or a tag like #minecraft:logs");
    }
}
