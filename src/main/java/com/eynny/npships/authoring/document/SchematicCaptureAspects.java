package com.eynny.npships.authoring.document;

import com.eynny.npships.api.patches.PatchAuthoring.CaptureAspect;
import com.eynny.npships.api.patches.PatchAuthoring.CaptureField;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host capture units, not a mechanism schema. No world or callback survives this operation. */
public final class SchematicCaptureAspects {
    private SchematicCaptureAspects() {}

    private static final class Fields
            implements com.eynny.npships.authoring.patches.PatchCaptureAspect.Fields,
                    AutoCloseable {
        private SchematicDocument document;

        Fields(SchematicDocument document) {
            this.document = document;
        }

        private SchematicDocument source() {
            return Objects.requireNonNull(document, "Capture field window is closed");
        }

        @Override
        public boolean inEntry(BlockPos pos, String key) {
            var source = source();
            return source.blocks().containsKey(pos)
                    && source.extensionBlockEntryReadOnly(pos).contains(key);
        }

        @Override
        public boolean inData(BlockPos pos, String key) {
            var source = source();
            return source.blocks().containsKey(pos)
                    && source.extensionBlockEntityReadOnly(pos).contains(key);
        }

        @Override
        public void close() {
            document = null;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("npships", path);
    }

    /**
     * One discovery pass only when external author capabilities exist; no live state is changed.
     */
    public static List<CaptureAspect> discover(
            ServerLevel level, BlockPos origin, SchematicDocument doc) {
        var result = new ArrayList<CaptureAspect>();
        var seen = new HashSet<String>();
        var operation = UUID.randomUUID();
        var code =
                new ArrayList<>(
                        com.eynny.npships.api.patches.PatchTypes.capabilities(
                                        com.eynny.npships.authoring.patches.PatchCaptureAspect
                                                .class)
                                .entrySet());
        code.sort(Comparator.comparingInt(entry -> entry.getValue().defaultRank()));
        var palette = doc.paletteCopy();
        try (var fields = new Fields(doc)) {
            for (var entry : doc.blocks().entrySet()) {
                var at = entry.getKey();
                var name = palette.getCompound(entry.getValue()).getString("Name");
                var data = doc.extensionBlockEntityReadOnly(at);
                for (var entryCode : code) {
                    for (var offer :
                            entryCode
                                    .getValue()
                                    .inspect(
                                            level,
                                            origin,
                                            doc,
                                            at,
                                            name,
                                            palette.getCompound(entry.getValue()),
                                            data,
                                            fields))
                        offer(
                                result,
                                seen,
                                operation,
                                entryCode.getKey(),
                                offer.aspect(),
                                offer.positions(),
                                offer.fields(),
                                offer.delegable());
                }
            }
        }
        return List.copyOf(result);
    }

    private static void offer(
            List<CaptureAspect> result,
            Set<String> seen,
            UUID operation,
            ResourceLocation owner,
            ResourceLocation aspect,
            List<BlockPos> positions,
            List<CaptureField> fields,
            boolean delegable) {
        var key = owner + "/" + aspect + "/" + positions;
        if (!seen.add(key)) return;
        result.add(
                new CaptureAspect(
                        UUID.nameUUIDFromBytes(
                                (operation + "/" + key).getBytes(StandardCharsets.UTF_8)),
                        owner,
                        aspect,
                        positions,
                        fields,
                        delegable));
    }
}
