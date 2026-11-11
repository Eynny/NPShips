package com.eynny.npships.integration;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.*;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.eynny.npships.integration.contraptions.MountedStorageSnapshot;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Function;

/** Static native visuals exported by actual providers; no native machine is ticked. */
public final class NativeScenePreviews {
    private NativeScenePreviews() {}

    public static <T> T capability(String type, Class<T> contract) {
        if (contract != PatchPreviewScene.class
                || !Set.of(
                                "create_devices",
                                "simulated_devices",
                                "simulated_spring",
                                "simulated_rope",
                                "computercraft_computer",
                                "computercraft_peripheral",
                                "radars_network",
                                "cbc_default_connections")
                        .contains(type)) return null;
        return contract.cast(
                new PatchPreviewScene() {
                    @Override
                    public boolean applies(BlockEntity entity, String name) {
                        boolean computer =
                                name.startsWith("computercraft:computer_")
                                        || name.startsWith("computercraft:turtle_");
                        return switch (type) {
                            case "create_devices" ->
                                    entity instanceof CopycatBlockEntity
                                            || entity instanceof IMultiBlockEntityContainer;
                            case "simulated_devices" ->
                                    entity
                                            instanceof
                                            dev.simulated_team.simulated.content.blocks.lasers
                                                    .AbstractLaserBlockEntity;
                            case "simulated_spring" -> entity instanceof SpringBlockEntity;
                            case "simulated_rope" -> entity instanceof RopeStrandHolderBlockEntity;
                            case "computercraft_computer" -> computer;
                            case "computercraft_peripheral" ->
                                    name.startsWith("computercraft:") && !computer;
                            case "radars_network" -> name.startsWith("create_radar:");
                            case "cbc_default_connections" ->
                                    com.eynny.npships.integration.cannons.CannonResources.breech(
                                            entity);
                            default -> false;
                        };
                    }

                    @Override
                    public Session open(SchematicDocument document) {
                        return new NativeSession(type, document);
                    }
                });
    }

    private static final class NativeSession implements PatchPreviewScene.Session {
        private final String type;
        private SchematicDocument document;
        private Map<BlockPos, CompoundTag> springs = Map.of();
        private Set<BlockPos> ropes = Set.of();

        NativeSession(String type, SchematicDocument document) {
            this.type = type;
            refresh(document);
        }

        @Override
        public Set<BlockPos> refresh(SchematicDocument next) {
            var changed = new HashSet<BlockPos>();
            if (type.equals("simulated_spring") || type.equals("simulated_rope")) {
                var nextSprings = new HashMap<BlockPos, CompoundTag>();
                var nextRopes = new HashSet<BlockPos>();
                for (var patch :
                        next.hasConfig()
                                ? SchematicPatches.read(next.configCopy())
                                : next.templatePatches()) {
                    String kind = patch.get("kind").getAsString();
                    if (!patch.has("target")) continue;
                    var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                    if (type.equals("simulated_spring") && kind.equals("spring")) {
                        nextSprings.put(owner, SpringPatch.visual(patch, owner));
                        for (var target : PatchDevices.targets(patch))
                            nextSprings.put(target, SpringPatch.visual(patch, target));
                    }
                    if (type.equals("simulated_rope") && kind.equals("rope")) {
                        nextRopes.add(owner);
                        nextRopes.addAll(PatchDevices.targets(patch));
                    }
                }
                var ends = new HashSet<>(springs.keySet());
                ends.addAll(nextSprings.keySet());
                for (var pos : ends)
                    if (!Objects.equals(springs.get(pos), nextSprings.get(pos))) changed.add(pos);
                changed.addAll(
                        com.eynny.npships.authoring.geometry.PreviewInvalidation.changed(
                                Set.of(), ropes, nextRopes));
                springs = nextSprings;
                ropes = nextRopes;
            }
            document = next;
            return changed;
        }

        @Override
        public boolean loadNative(BlockEntity entity) {
            return !type.startsWith("computercraft_")
                    && !type.equals("radars_network")
                    && !(type.equals("create_devices") && entity instanceof CopycatBlockEntity);
        }

        @Override
        public void prepare(
                BlockEntity entity,
                BlockPos pos,
                CompoundTag saved,
                Function<BlockPos, BlockState> states) {
            if (type.equals("simulated_spring")) {
                SpringPatch.strip(saved);
                if (springs.containsKey(pos)) saved.merge(springs.get(pos));
            }
            if (type.equals("create_devices") && entity instanceof IMultiBlockEntityContainer) {
                saved.put("LastKnownPos", NbtUtils.writeBlockPos(pos));
                var entry = document.blockEntryCopy(pos);
                String key = MountedStorageSnapshot.CONTROLLER_OFFSET;
                if (!entry.contains(key)) return;
                var offset =
                        NbtUtils.readBlockPos(entry, key)
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Malformed saved multiblock controller offset"));
                var controller = pos.offset(offset);
                if (!document.blocks().containsKey(controller)
                        || !entity.getType().isValid(states.apply(controller)))
                    throw new IllegalArgumentException(
                            "Saved multiblock controller is missing or incompatible at "
                                    + controller.toShortString());
                saved.put("Controller", NbtUtils.writeBlockPos(controller));
            }
        }

        @Override
        public void afterNativeLoad(
                BlockEntity entity, BlockPos pos, HolderLookup.Provider registry) {
            if (type.equals("simulated_devices"))
                entity.getPersistentData()
                        .merge(document.blockEntityData(pos).getCompound("NeoForgeData"));
        }

        @Override
        public void afterVirtual(
                BlockEntity entity,
                BlockPos pos,
                CompoundTag saved,
                HolderLookup.Provider registry) {
            if (type.equals("cbc_default_connections")) {
                var magazine =
                        net.minecraft.world.item.ItemStack.parseOptional(
                                registry, document.blockEntityData(pos).getCompound("Magazine"));
                SchematicResources.invoke(entity, "setMagazine", magazine);
            }
            if (type.equals("simulated_rope"))
                ((RopeStrandHolderBlockEntity) entity).getBehavior().renderAttached =
                        ropes.contains(pos);
            if (type.equals("computercraft_peripheral")
                    && document.blockName(pos).startsWith("computercraft:monitor_")) {
                var visual = new CompoundTag();
                int width = Math.clamp(saved.getInt("Width"), 1, 32),
                        height = Math.clamp(saved.getInt("Height"), 1, 32);
                visual.putInt("Width", width);
                visual.putInt("Height", height);
                visual.putInt("XIndex", Math.clamp(saved.getInt("XIndex"), 0, width - 1));
                visual.putInt("YIndex", Math.clamp(saved.getInt("YIndex"), 0, height - 1));
                entity.handleUpdateTag(visual, registry);
            }
            if (type.equals("radars_network")
                    && document.blockName(pos).equals("create_radar:monitor")
                    && saved.contains("Controller")) {
                var visual = new CompoundTag();
                visual.put("Controller", saved.get("Controller").copy());
                visual.putInt("Size", Math.max(1, saved.getInt("Size")));
                entity.handleUpdateTag(visual, registry);
            }
            if (type.equals("create_devices")
                    && entity instanceof CopycatBlockEntity
                    && saved.contains("Material", Tag.TAG_COMPOUND)) {
                var visual = new CompoundTag();
                visual.put("Material", saved.getCompound("Material").copy());
                entity.handleUpdateTag(visual, registry);
            }
        }

        @Override
        public Component describe(BlockEntity entity, String name) {
            return type.equals("create_devices") && entity instanceof CopycatBlockEntity copycat
                    ? Component.translatable(
                            "npships.editor.copycat", name, copycat.getMaterial().toString())
                    : null;
        }
    }
}
