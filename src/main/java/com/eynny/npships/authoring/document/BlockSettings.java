package com.eynny.npships.authoring.document;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

import java.util.Set;

/** Detached native defaults plus stored values; applying a form writes only changed root fields. */
public final class BlockSettings {
    private BlockSettings() {}

    public static final Set<String> IDENTITY = Set.of("id", "x", "y", "z", "keepPacked");

    public static CompoundTag defaults(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries) {
        return defaults(doc, pos, registries, true);
    }

    public static CompoundTag typeDefaults(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries) {
        return defaults(doc, pos, registries, false);
    }

    private static CompoundTag defaults(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries, boolean stored) {
        var state =
                net.minecraft.nbt.NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        doc.paletteCopy().getCompound(doc.blocks().get(pos)));
        if (!stored) state = state.getBlock().defaultBlockState();
        var be =
                state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block
                        ? block.newBlockEntity(stored ? pos : BlockPos.ZERO, state)
                        : null;
        var result = be == null ? new CompoundTag() : be.saveWithoutMetadata(registries);
        // Native serializers omit optional settings when they have not been configured yet.
        String id = doc.blockName(pos);
        if (id.equals("minecraft:beacon")) {
            missing(result, "primary_effect", StringTag.valueOf(""));
            missing(result, "secondary_effect", StringTag.valueOf(""));
        }
        if (id.equals("minecraft:spawner")) {
            var entity = new CompoundTag();
            entity.putString("id", "minecraft:pig");
            var spawn = new CompoundTag();
            spawn.put("entity", entity);
            missing(result, "SpawnData", spawn);
        }
        if (id.equals("minecraft:end_gateway")) {
            var exit = new CompoundTag();
            exit.putInt("X", 0);
            exit.putInt("Y", 64);
            exit.putInt("Z", 0);
            missing(result, "ExitPortal", exit);
            missing(result, "ExactTeleport", ByteTag.ZERO);
        }
        if (id.equals("minecraft:trial_spawner")) {
            missing(result, "normal_config", trialConfig());
            missing(result, "ominous_config", trialConfig());
            missing(result, "target_cooldown_length", IntTag.valueOf(36000));
            missing(result, "required_player_range", IntTag.valueOf(14));
        }
        if (be instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity vault) {
            var config = result.getCompound("config");
            var nativeConfig = vault.getConfig();
            missing(
                    config,
                    "loot_table",
                    StringTag.valueOf(nativeConfig.lootTable().location().toString()));
            missing(config, "activation_range", DoubleTag.valueOf(nativeConfig.activationRange()));
            missing(
                    config,
                    "deactivation_range",
                    DoubleTag.valueOf(nativeConfig.deactivationRange()));
            result.put("config", config);
        }
        for (var patch :
                com.eynny.npships.authoring.patches.PatchCode.applicable(
                        com.eynny.npships.authoring.patches.PatchBlockDefaults.class,
                        code -> code.applies(id, be),
                        com.eynny.npships.api.patches.PatchStage.AUTHOR_PREPARATION))
            patch.getValue().fill(doc, pos, stored, id, be, result, registries);
        if (be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity
                || be instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity) {
            missing(result, "CustomName", StringTag.valueOf("\"\""));
            missing(result, "Lock", StringTag.valueOf(""));
        }
        if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity
                || be instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity) {
            missing(result, "LootTable", StringTag.valueOf(""));
            missing(result, "LootTableSeed", LongTag.valueOf(0));
        }
        if (be instanceof net.minecraft.world.level.block.entity.SkullBlockEntity) {
            var profile = new CompoundTag();
            profile.putString("name", "");
            missing(result, "profile", profile);
            missing(result, "note_block_sound", StringTag.valueOf(""));
        }
        if (be instanceof net.minecraft.world.level.block.entity.BannerBlockEntity)
            missing(result, "patterns", new ListTag());
        if (be instanceof net.minecraft.world.level.block.entity.DecoratedPotBlockEntity) {
            var sherds = new ListTag();
            for (int i = 0; i < 4; i++) sherds.add(StringTag.valueOf("minecraft:brick"));
            missing(result, "sherds", sherds);
            missing(result, "item", new CompoundTag());
        }
        if (be instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity)
            missing(result, "RecordItem", new CompoundTag());
        if (be instanceof net.minecraft.world.level.block.entity.LecternBlockEntity) {
            missing(result, "Book", new CompoundTag());
            missing(result, "Page", IntTag.valueOf(0));
        }
        if (stored) result.merge(doc.blockEntityData(pos));
        BlockSettingTemplates.expand(
                result, com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.block(id));
        IDENTITY.forEach(result::remove);
        if (!stored && be != null) {
            var typeId =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(
                            be.getType());
            if (typeId != null) result.putString("id", typeId.toString());
        }
        return result;
    }

    private static void missing(CompoundTag tag, String key, Tag value) {
        if (!tag.contains(key)) tag.put(key, value);
    }

    private static CompoundTag trialConfig() {
        var defaults =
                net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig.DEFAULT;
        var tag =
                (CompoundTag)
                        net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig.CODEC
                                .encodeStart(NbtOps.INSTANCE, defaults)
                                .getOrThrow();
        tag.putInt("spawn_range", defaults.spawnRange());
        tag.putFloat("total_mobs", defaults.totalMobs());
        tag.putFloat("simultaneous_mobs", defaults.simultaneousMobs());
        tag.putFloat("total_mobs_added_per_player", defaults.totalMobsAddedPerPlayer());
        tag.putFloat(
                "simultaneous_mobs_added_per_player", defaults.simultaneousMobsAddedPerPlayer());
        tag.putInt("ticks_between_spawn", defaults.ticksBetweenSpawn());
        tag.put("spawn_potentials", new ListTag());
        tag.putString(
                "items_to_drop_when_ominous",
                defaults.itemsToDropWhenOminous().location().toString());
        tag.put(
                "loot_tables_to_eject",
                net.minecraft.util.random.SimpleWeightedRandomList.wrappedCodecAllowingEmpty(
                                net.minecraft.resources.ResourceKey.codec(
                                        net.minecraft.core.registries.Registries.LOOT_TABLE))
                        .encodeStart(NbtOps.INSTANCE, defaults.lootTablesToEject())
                        .getOrThrow());
        return tag;
    }

    public static SchematicDocument apply(
            SchematicDocument doc,
            BlockPos pos,
            CompoundTag before,
            CompoundTag after,
            HolderLookup.Provider registries) {
        var data = nativeData(doc, pos, registries);
        // Changed roots are replaced by detached copies; retain old roots without another deep
        // copy.
        var original = new CompoundTag();
        boolean changed = false;
        var keys = new java.util.HashSet<>(before.getAllKeys());
        keys.addAll(after.getAllKeys());
        for (var key : keys) {
            if (IDENTITY.contains(key)) continue;
            if (java.util.Objects.equals(before.get(key), after.get(key))) continue;
            changed = true;
            if (data.contains(key)) original.put(key, data.get(key));
            if (after.contains(key)) data.put(key, after.get(key).copy());
            else data.remove(key);
        }
        if (!changed) return doc;
        BlockModes.validateData(doc, pos, data, registries);
        BlockSettingTemplates.normalizeChangedItems(
                data,
                before,
                after,
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.block(
                        doc.blockName(pos)),
                original);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.blockSettings(doc.blockName(pos), before, after, data);
        return SchematicEdits.blockData(doc, pos, data);
    }

    public static CompoundTag nativeData(
            SchematicDocument doc, BlockPos pos, HolderLookup.Provider registries) {
        var state =
                NbtUtils.readBlockState(
                        registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                        doc.paletteCopy().getCompound(doc.blocks().get(pos)));
        var be =
                state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block
                        ? block.newBlockEntity(pos, state)
                        : null;
        var data = be == null ? new CompoundTag() : be.saveWithoutMetadata(registries);
        data.merge(doc.blockEntityData(pos));
        return data;
    }

    public static Tag scalar(Tag previous, String input) {
        try {
            return switch (previous.getId()) {
                case Tag.TAG_BYTE -> ByteTag.valueOf(Byte.parseByte(input));
                case Tag.TAG_SHORT -> ShortTag.valueOf(Short.parseShort(input));
                case Tag.TAG_INT -> IntTag.valueOf(Integer.parseInt(input));
                case Tag.TAG_LONG -> LongTag.valueOf(Long.parseLong(input));
                case Tag.TAG_FLOAT -> {
                    float n = Float.parseFloat(input);
                    if (!Float.isFinite(n)) throw new NumberFormatException();
                    yield FloatTag.valueOf(n);
                }
                case Tag.TAG_DOUBLE -> {
                    double n = Double.parseDouble(input);
                    if (!Double.isFinite(n)) throw new NumberFormatException();
                    yield DoubleTag.valueOf(n);
                }
                case Tag.TAG_STRING -> StringTag.valueOf(input);
                default ->
                        throw new IllegalArgumentException("Open the group to edit its contents");
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + previous.getType().getPrettyName());
        }
    }
}
