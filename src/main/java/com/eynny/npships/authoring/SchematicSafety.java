package com.eynny.npships.authoring;

import com.eynny.npships.authoring.document.BlockAreaLayers;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicExtensions;

import net.minecraft.nbt.*;

/**
 * Reject known transient machinery before export or any world clearing. No claim of universal addon
 * validation.
 */
public final class SchematicSafety {
    private SchematicSafety() {}

    /** Simulated's contraption diagram is a static HangingEntity, not moving machinery. */
    public static boolean movingEntity(String id) {
        return id.contains("contraption") && !id.equals("simulated:contraption_diagram");
    }

    /** Keep moving payloads even when ordinary entity export is disabled, including passengers. */
    public static boolean movingSnapshot(CompoundTag data) {
        return movingSnapshot(data, 0);
    }

    private static boolean movingSnapshot(CompoundTag data, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Excessively nested entity passengers");
        if (movingEntity(data.getString("id")) || data.contains("Contraption", Tag.TAG_COMPOUND))
            return true;
        for (var passenger : data.getList("Passengers", Tag.TAG_COMPOUND))
            if (movingSnapshot((CompoundTag) passenger, depth + 1)) return true;
        return false;
    }

    /**
     * Sublevels are separate worlds, not Create entity block payloads. Never show a partial hull.
     */
    public static void requirePortableSubLevels(CompoundTag template) {
        var payload = template.get("sub_levels");
        if (payload != null && (!(payload instanceof ListTag list) || !list.isEmpty()))
            throw new IllegalArgumentException(
                    "Sable/Aeronautics: use Import for this sub_levels schematic before editing or"
                            + " spawning. Import converts complete physical payloads into editable"
                            + " areas. Original file is unchanged.");
    }

    public static void validate(CompoundTag template) {
        requirePortableSubLevels(template);
        com.eynny.npships.integration.contraptions.PhysicalGroups.validate(template);
        inspect(template, "NBT", 0, InspectionScope.DOCUMENT);
        if (template.contains("blocks", Tag.TAG_LIST) && template.contains("palette", Tag.TAG_LIST))
            for (var value : template.getList("blocks", Tag.TAG_COMPOUND)) {
                var block = (CompoundTag) value;
                String id =
                        template.getList("palette", Tag.TAG_COMPOUND)
                                .getCompound(block.getInt("state"))
                                .getString("Name");
                var data = block.getCompound("nbt");
                for (var code :
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                    code.validateNativeBlock(id, data);
                if (data.getBoolean("Running")
                        && (id.contains("bearing")
                                || id.contains("propeller")
                                || id.contains("gantry")
                                || id.contains("piston")))
                    throw new IllegalArgumentException(
                            "Disassemble moving mechanism before export/spawn: " + id);
            }
    }

    public static void validateLive(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos origin,
            SchematicDocument doc) {
        validateLive(level, origin, doc, java.util.Set.of());
    }

    /** Only controllers whose complete block payload was copied may remain running in the world. */
    public static void validateLive(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos origin,
            SchematicDocument doc,
            java.util.Set<net.minecraft.core.BlockPos> copiedControllers) {
        for (var p : doc.blocks().keySet()) {
            if (copiedControllers.contains(p)) continue;
            var be = level.getBlockEntity(origin.offset(p));
            if (be == null) continue;
            for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
                code.validateLive(be, p);
            if (be
                            instanceof
                            com.simibubi.create.content.contraptions.bearing
                                                    .MechanicalBearingBlockEntity
                                            bearing
                    && (bearing.isRunning() || bearing.getMovedContraption() != null))
                throw new IllegalArgumentException(
                        "Disassemble running bearing before export: " + p);
            var data = be.saveWithFullMetadata(level.registryAccess());
            data.putString("id", doc.blockName(p));
            inspect(data, "block " + p, 0, InspectionScope.NATIVE);
        }
    }

    /**
     * Internal structural context shared by native semantic walkers. Only actual host document
     * frames grant opaque ownership; a namespaced key inside native BE/entity NBT grants nothing.
     * Opaque data still participates in depth/resource/schema validation.
     */
    public enum InspectionScope {
        NATIVE,
        DOCUMENT,
        VARIANTS,
        AREAS,
        LAYERS,
        LAYER,
        PACKED,
        OPAQUE;

        public InspectionScope child(CompoundTag parent, String key) {
            return switch (this) {
                case OPAQUE -> OPAQUE;
                case DOCUMENT ->
                        switch (key) {
                            case SchematicExtensions.KEY -> OPAQUE;
                            case StructureSegments.VARIANTS -> VARIANTS;
                            case BlockAreaLayers.KEY -> AREAS;
                            default -> NATIVE;
                        };
                case VARIANTS -> DOCUMENT;
                case AREAS ->
                        switch (key) {
                            case "base" -> PACKED;
                            case "layers" -> LAYERS;
                            default -> NATIVE;
                        };
                case LAYER -> key.equals("source") || key.equals("content") ? PACKED : NATIVE;
                case PACKED ->
                        parent.contains("metadata", Tag.TAG_COMPOUND)
                                ? (key.equals("metadata") ? DOCUMENT : NATIVE)
                                : DOCUMENT.child(parent, key);
                default -> NATIVE;
            };
        }

        public InspectionScope element() {
            return this == OPAQUE ? OPAQUE : this == LAYERS ? LAYER : NATIVE;
        }
    }

    private static void inspectRecord(
            CompoundTag c, java.util.function.Supplier<String> path, InspectionScope scope) {
        String id = c.getString("id");
        // Radar target filters also use "contraption", but as a boolean flag.
        // A saved moving mechanism carries a compound payload, not a flag.
        if (scope != InspectionScope.OPAQUE
                && ((movingEntity(id) && c.contains("Pos"))
                        || c.contains("Contraption", Tag.TAG_COMPOUND)
                        || c.contains("contraption", Tag.TAG_COMPOUND)))
            throw new IllegalArgumentException(
                    "Active contraption is not portable yet: "
                            + path.get()
                            + ". Stop/disassemble its moving mechanism before export.");
        if (scope != InspectionScope.OPAQUE
                && c.getBoolean("Running")
                && (id.contains("bearing")
                        || id.contains("propeller")
                        || id.contains("gantry")
                        || id.contains("piston")))
            throw new IllegalArgumentException(
                    "Running mechanism at " + path.get() + ": stop it before export.");
    }

    /** Runtime counterpart of validate; one actual record/edge/callback per permitted unit. */
    public static RuntimeValidation beginRuntimeValidation(CompoundTag owned) {
        return new RuntimeValidation(owned);
    }

    public static final class RuntimeValidation implements AutoCloseable {
        public static final long WORKING_BYTES = 65L * 12 * Long.BYTES + 256L * 4 * Long.BYTES;
        private CompoundTag source;
        private final java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
        private final java.util.Set<String> groups = new java.util.HashSet<>();
        private final java.util.List<com.eynny.npships.authoring.patches.PatchSourceOperations>
                validators =
                        com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all();
        private int phase, blockIndex, validatorIndex;
        private boolean nativeActive, closed, failed;
        private CompoundTag nativeData;
        private String nativeName;
        private long progress;

        private static final class Frame {
            final Tag tag;
            final Frame parent;
            final String key;
            final int element, depth;
            final InspectionScope scope;
            boolean entered;
            int index;
            java.util.Iterator<String> keys;

            Frame(Tag tag, Frame parent, String key, int element, InspectionScope scope) {
                this.tag = tag;
                this.parent = parent;
                this.key = key;
                this.element = element;
                this.scope = scope;
                depth = parent == null ? 0 : parent.depth + 1;
            }
        }

        private RuntimeValidation(CompoundTag owned) {
            source = java.util.Objects.requireNonNull(owned);
        }

        public boolean step(java.util.function.BooleanSupplier allowed) {
            requireOpen();
            java.util.Objects.requireNonNull(allowed);
            try {
                while (phase < 4 && allowed.getAsBoolean()) {
                    one();
                    progress++;
                }
                return phase == 4;
            } catch (RuntimeException | Error error) {
                failed = true;
                clear();
                throw error;
            }
        }

        private void one() {
            var blocks = source.getList("blocks", Tag.TAG_COMPOUND);
            if (phase == 0) {
                requirePortableSubLevels(source);
                phase = 1;
            } else if (phase == 1) {
                if (blockIndex < blocks.size())
                    com.eynny.npships.integration.contraptions.PhysicalGroups.validateBlock(
                            blocks.getCompound(blockIndex++), groups);
                else {
                    stack.push(new Frame(source, null, null, -1, InspectionScope.DOCUMENT));
                    phase = 2;
                    blockIndex = 0;
                }
            } else if (phase == 2) {
                var frame = stack.peek();
                if (!frame.entered) {
                    if (frame.depth > 64)
                        throw new IllegalArgumentException(
                                "Excessively nested schematic at " + path(frame));
                    if (frame.tag instanceof CompoundTag c) {
                        inspectRecord(c, () -> path(frame), frame.scope);
                        frame.keys = c.getAllKeys().iterator();
                    }
                    frame.entered = true;
                } else if (frame.tag instanceof CompoundTag c && frame.keys.hasNext()) {
                    String key = frame.keys.next();
                    stack.push(new Frame(c.get(key), frame, key, -1, frame.scope.child(c, key)));
                } else if (frame.tag instanceof ListTag list && frame.index < list.size()) {
                    int i = frame.index++;
                    stack.push(new Frame(list.get(i), frame, null, i, frame.scope.element()));
                } else {
                    stack.pop();
                    if (stack.isEmpty()) phase = 3;
                }
            } else {
                if (!source.contains("blocks", Tag.TAG_LIST)
                        || !source.contains("palette", Tag.TAG_LIST)
                        || blockIndex == blocks.size()) {
                    phase = 4;
                    return;
                }
                if (!nativeActive) {
                    var block = blocks.getCompound(blockIndex);
                    nativeName =
                            source.getList("palette", Tag.TAG_COMPOUND)
                                    .getCompound(block.getInt("state"))
                                    .getString("Name");
                    nativeData = block.getCompound("nbt");
                    validatorIndex = 0;
                    nativeActive = true;
                } else if (validatorIndex < validators.size())
                    validators.get(validatorIndex++).validateNativeBlock(nativeName, nativeData);
                else {
                    if (nativeData.getBoolean("Running")
                            && (nativeName.contains("bearing")
                                    || nativeName.contains("propeller")
                                    || nativeName.contains("gantry")
                                    || nativeName.contains("piston")))
                        throw new IllegalArgumentException(
                                "Disassemble moving mechanism before export/spawn: " + nativeName);
                    nativeActive = false;
                    nativeData = null;
                    nativeName = null;
                    blockIndex++;
                }
            }
        }

        private static String path(Frame frame) {
            var parts = new java.util.ArrayDeque<Frame>();
            for (var value = frame; value.parent != null; value = value.parent) parts.push(value);
            var result = new StringBuilder("NBT");
            while (!parts.isEmpty()) {
                var part = parts.pop();
                if (part.key != null) result.append('.').append(part.key);
                else result.append('[').append(part.element).append(']');
            }
            return result.toString();
        }

        public long progress() {
            return progress;
        }

        private void requireOpen() {
            if (closed || failed)
                throw new IllegalStateException("Schematic validation is closed or failed");
        }

        private void clear() {
            source = null;
            nativeData = null;
            nativeName = null;
            stack.clear();
            groups.clear();
        }

        @Override
        public void close() {
            closed = true;
            clear();
        }
    }

    private static void inspect(Tag tag, String path, int depth, InspectionScope scope) {
        if (depth > 64)
            throw new IllegalArgumentException("Excessively nested schematic at " + path);
        if (tag instanceof CompoundTag c) {
            inspectRecord(c, () -> path, scope);
            for (String key : c.getAllKeys())
                inspect(c.get(key), path + "." + key, depth + 1, scope.child(c, key));
        } else if (tag instanceof ListTag list)
            for (int i = 0; i < list.size(); i++)
                inspect(list.get(i), path + "[" + i + "]", depth + 1, scope.element());
    }
}
