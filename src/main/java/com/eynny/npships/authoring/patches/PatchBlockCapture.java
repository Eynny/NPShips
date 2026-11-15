package com.eynny.npships.authoring.patches;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Internal native serializer capability. Arguments are borrowed for this server-thread callback;
 * only detached output is returned. The host retains iteration, budgets and accepted-document
 * ownership. Code objects must be stateless; per-capture scratch belongs to the returned operation.
 */
public interface PatchBlockCapture {
    interface Operation {
        boolean applies(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos pos,
                String name,
                CompoundTag data);

        JsonObject capture(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos pos,
                String name,
                CompoundTag data,
                JsonObject previous,
                java.util.function.BiPredicate<net.minecraft.resources.ResourceLocation, BlockPos>
                        replaced)
                throws Exception;

        default boolean skipRemaining(
                SchematicDocument source, BlockPos pos, String name, CompoundTag data) {
            return false;
        }

        default boolean changesEntry() {
            return false;
        }

        default CompoundTag entry(
                ServerLevel level,
                BlockPos origin,
                SchematicDocument source,
                BlockPos pos,
                String name,
                CompoundTag data,
                CompoundTag previous,
                java.util.function.BiPredicate<net.minecraft.resources.ResourceLocation, BlockPos>
                        replaced)
                throws Exception {
            return previous;
        }

        default void complete(
                ServerLevel level, SchematicDocument source, com.google.gson.JsonArray entries)
                throws Exception {}
    }

    final class Context {
        private final java.util.Map<Class<?>, Object> scratch = new java.util.HashMap<>();

        public <T> T memoized(Class<T> type, java.util.function.Supplier<T> factory) {
            return type.cast(
                    scratch.computeIfAbsent(
                            type, ignored -> java.util.Objects.requireNonNull(factory.get())));
        }
    }

    Operation begin();

    default Operation begin(Context context) {
        return begin();
    }

    int defaultRank();
}
