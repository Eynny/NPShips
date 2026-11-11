package com.eynny.npships.authoring.document;

import com.eynny.npships.authoring.patches.EntityIdentity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Detached native entity defaults, never added to or ticked in the editor world. */
public final class EntitySettings {
    private EntitySettings() {}

    public static CompoundTag defaults(CompoundTag source, Level level) {
        return defaults(source, level, null);
    }

    public static CompoundTag editable(CompoundTag source, Level level) {
        var result = source.copy();
        result.remove(EntityIdentity.KEY);
        var entityKey = ResourceLocation.tryParse(source.getString("id"));
        var type =
                entityKey == null
                        ? null
                        : BuiltInRegistries.ENTITY_TYPE.getOptional(entityKey).orElse(null);
        if (type == null) return result;
        var rotation = result.getList("Rotation", Tag.TAG_FLOAT).copy();
        while (rotation.size() < 2) rotation.add(FloatTag.valueOf(0));
        result.put("Rotation", rotation);
        if (type.create(level) instanceof net.minecraft.world.entity.LivingEntity) {
            var data = result.getCompound("NeoForgeData");
            if (!data.contains(com.eynny.npships.ship.EntityHeadPose.KEY))
                data.putFloat(com.eynny.npships.ship.EntityHeadPose.KEY, 0);
            result.put("NeoForgeData", data);
        }
        return result;
    }

    /**
     * Display projection only; hidden fields stay in the accepted document and textEdit baseline.
     */
    public static CompoundTag textView(CompoundTag source, Level level, boolean compact) {
        var shown = editable(source, level);
        return compact ? projectText(shown, source) : shown;
    }

    private static CompoundTag projectText(CompoundTag shown, CompoundTag source) {
        return projectText(
                shown,
                source,
                source.getCompound("NeoForgeData")
                        .contains(com.eynny.npships.ship.EntityHeadPose.KEY));
    }

    private static CompoundTag projectText(
            CompoundTag shown, CompoundTag source, boolean explicitHead) {
        // Native initial-spawn reapplication merges arbitrary entity fields, not an equipment
        // allowlist. Group the current native fields consistently, even before a field has been
        // explicitly copied into the pending record. This detached union is never saved as-is.
        if (plainSpawnCopy(source)) {
            var grouped = new CompoundTag();
            for (String key : shown.getAllKeys())
                if (!key.equals("NeoForgeData") && !hostMetadata(key))
                    grouped.put(key, shown.get(key).copy());
            grouped.merge(shown.getCompound("NeoForgeData").getCompound("npships_initial_spawn"));
            hideOverridden(shown, grouped, 0);
            var data = shown.getCompound("NeoForgeData");
            data.put("npships_initial_spawn", grouped);
            shown.put("NeoForgeData", data);
        }
        if (!explicitHead) {
            var data = shown.getCompound("NeoForgeData");
            data.remove(com.eynny.npships.ship.EntityHeadPose.KEY);
            if (data.isEmpty()) shown.remove("NeoForgeData");
        }
        return shown;
    }

    private static boolean plainSpawnCopy(CompoundTag source) {
        var data = source.getCompound("NeoForgeData");
        return data.contains("npships_initial_spawn", Tag.TAG_COMPOUND)
                && !data.getCompound("npships_initial_spawn").contains("npships:format");
    }

    /** Transform only an explicitly pending native pose, without widening its override set. */
    public static void transformPendingRotation(
            CompoundTag entity, java.util.function.UnaryOperator<ListTag> transform) {
        var data = entity.getCompound("NeoForgeData");
        if (!data.contains("npships_initial_spawn", Tag.TAG_COMPOUND)) return;
        var pending = data.getCompound("npships_initial_spawn");
        // Match the existing native reapplication contract, including generated equipment plans.
        var overrides =
                pending.getInt("npships:format") == 2
                                && pending.contains("overrides", Tag.TAG_COMPOUND)
                                && pending.contains("equipment", Tag.TAG_COMPOUND)
                        ? pending.getCompound("overrides")
                        : pending;
        if (overrides.contains("Rotation", Tag.TAG_LIST))
            overrides.put(
                    "Rotation", transform.apply(overrides.getList("Rotation", Tag.TAG_FLOAT)));
    }

    private static boolean hostMetadata(String key) {
        return key.equals("npships_initial_spawn")
                || key.equals(EntityIdentity.KEY)
                || key.equals(com.eynny.npships.authoring.patches.EntityPatches.EMBEDDED)
                || key.equals(SchematicExtensions.KEY);
    }

    private static void hideOverridden(CompoundTag shown, CompoundTag overrides, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Entity data is nested too deeply");
        for (String key : overrides.getAllKeys()) {
            if (hostMetadata(key)) continue;
            if (shown.get(key) instanceof CompoundTag data
                    && overrides.get(key) instanceof CompoundTag nested) {
                hideOverridden(data, nested, depth + 1);
                if (data.isEmpty()) shown.remove(key);
            } else shown.remove(key);
        }
    }

    /** Rebase only text changes onto the accepted record, retaining host-derived normalization. */
    public static CompoundTag textEdit(CompoundTag saved, CompoundTag before, CompoundTag after) {
        return textEdit(saved, before, after, false);
    }

    /** Unproject changed compact fields without promoting unchanged display-only values. */
    public static CompoundTag textEdit(
            CompoundTag saved, CompoundTag before, CompoundTag after, boolean compact) {
        var result = saved.copy();
        textDelta(result, before, after, 0);
        if (compact && plainSpawnCopy(before) && plainSpawnCopy(after))
            mergeSpawnEdits(
                    saved,
                    result,
                    before.getCompound("NeoForgeData").getCompound("npships_initial_spawn"),
                    after.getCompound("NeoForgeData").getCompound("npships_initial_spawn"),
                    "",
                    0);
        return result;
    }

    private static void textDelta(
            CompoundTag result, CompoundTag before, CompoundTag after, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Entity data is nested too deeply");
        var keys = new java.util.TreeSet<>(before.getAllKeys());
        keys.addAll(after.getAllKeys());
        for (var key : keys) {
            var old = before.get(key);
            var next = after.get(key);
            if (java.util.Objects.equals(old, next)) continue;
            if (old instanceof CompoundTag b
                    && next instanceof CompoundTag a
                    && result.get(key) instanceof CompoundTag current)
                textDelta(current, b, a, depth + 1);
            else if (next == null) result.remove(key);
            else result.put(key, next.copy());
        }
    }

    /**
     * Resolve edits made through either visible representation before rebuilding the spawn copy.
     */
    static CompoundTag synchronizeSpawnOverrides(CompoundTag saved, CompoundTag edited) {
        var result = edited.copy();
        var previous = saved.getCompound("NeoForgeData");
        var current = result.getCompound("NeoForgeData");
        if (!previous.contains("npships_initial_spawn", Tag.TAG_COMPOUND)
                || !current.contains("npships_initial_spawn")) return result;
        if (!current.contains("npships_initial_spawn", Tag.TAG_COMPOUND))
            throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                    "entity_spawn_copy", "Initial spawn data must be a compound.");
        var before = previous.getCompound("npships_initial_spawn");
        var after = current.getCompound("npships_initial_spawn");
        if (after.getCompound("NeoForgeData").contains("npships_initial_spawn"))
            throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                    "entity_spawn_nested", "Initial spawn data cannot contain another spawn copy.");
        mergeSpawnEdits(saved, result, before, after, "", 0);
        return result;
    }

    private static void mergeSpawnEdits(
            CompoundTag original,
            CompoundTag result,
            CompoundTag before,
            CompoundTag after,
            String path,
            int depth) {
        if (depth > 64) throw new IllegalArgumentException("Entity data is nested too deeply");
        var keys = new java.util.TreeSet<>(before.getAllKeys());
        keys.addAll(after.getAllKeys());
        for (var key : keys) {
            var oldValue = before.get(key);
            var newValue = after.get(key);
            if (java.util.Objects.equals(oldValue, newValue)) continue;
            String at = path.isEmpty() ? key : path + "." + key;
            var rootBefore = original.get(key);
            var rootAfter = result.get(key);
            if (oldValue instanceof CompoundTag oldObject
                    && newValue instanceof CompoundTag newObject
                    && rootBefore instanceof CompoundTag originalObject
                    && rootAfter instanceof CompoundTag resultObject) {
                mergeSpawnEdits(originalObject, resultObject, oldObject, newObject, at, depth + 1);
            } else {
                if (!java.util.Objects.equals(rootBefore, rootAfter)
                        && !java.util.Objects.equals(rootAfter, newValue))
                    throw com.eynny.npships.authoring.validation.ValidationProblem.invalid(
                            "entity_spawn_conflict",
                            "Conflicting entity and initial spawn edits at " + at,
                            at);
                if (newValue == null) result.remove(key);
                else result.put(key, newValue.copy());
            }
        }
    }

    public static DataSuggestions suggestions(CompoundTag source, Level level) {
        return suggestions(source, level, false);
    }

    public static DataSuggestions suggestions(CompoundTag source, Level level, boolean compact) {
        return suggestions(source, level, compact, null);
    }

    public static DataSuggestions suggestions(
            CompoundTag source, Level level, boolean compact, DataSuggestions previous) {
        var fields = new java.util.LinkedHashMap<String, DataSuggestions.NativeField>();
        var domains = new java.util.LinkedHashMap<String, java.util.List<String>>();
        var identity = new CompoundTag();
        identity.putString("id", source.getString("id"));
        var defaults = defaults(identity, level, fields, domains);
        defaults.remove("UUID");
        var stored = editable(source, level);
        boolean explicitHead =
                source.getCompound("NeoForgeData")
                        .contains(com.eynny.npships.ship.EntityHeadPose.KEY);
        if (compact) {
            projectText(defaults, source, explicitHead);
            projectText(stored, source, explicitHead);
            if (plainSpawnCopy(source)) {
                projectDomains(fields);
                projectDomains(domains);
            }
        }
        var result =
                new DataSuggestions(
                                defaults,
                                stored,
                                domains,
                                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject
                                        .entity(source.getString("id")),
                                "")
                        .entityDocument()
                        .withNativeFields(fields)
                        .withDeclaredValues(domains)
                        .withItems(level.registryAccess(), previous);
        if (compact)
            result.withExampleProjection(
                    (example, ignored) -> projectText(example, source, explicitHead));
        return result;
    }

    private static <T> void projectDomains(java.util.Map<String, T> fields) {
        for (var entry : java.util.List.copyOf(fields.entrySet())) {
            String path = entry.getKey();
            String root = path.split("[.\\[]", 2)[0];
            if (!root.equals("NeoForgeData") && !hostMetadata(root))
                fields.put("NeoForgeData.npships_initial_spawn." + path, entry.getValue());
        }
    }

    private static void livingReference(
            CompoundTag reference,
            Level level,
            java.util.Map<String, DataSuggestions.NativeField> fields,
            java.util.Map<String, java.util.List<String>> domains) {
        // Encode optional native records without adding/ticking an entity or changing authored
        // data.
        var effect =
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,
                        200,
                        1,
                        true,
                        false,
                        true,
                        new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 40));
        var effects = new ListTag();
        effects.add(effect.save());
        reference.put("active_effects", effects);
        var attribute =
                new net.minecraft.world.entity.ai.attributes.AttributeInstance(
                        net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
                        ignored -> {});
        attribute.addPermanentModifier(
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        ResourceLocation.parse("npships:example"),
                        1,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
                                .ADD_VALUE));
        var attributes = new ListTag();
        attributes.add(attribute.save());
        reference.put("attributes", attributes);
        var effectIds = registryIds(level, net.minecraft.core.registries.Registries.MOB_EFFECT);
        var attributeIds = registryIds(level, net.minecraft.core.registries.Registries.ATTRIBUTE);
        for (String prefix : java.util.List.of("", "NeoForgeData.npships_initial_spawn.")) {
            domains.put(prefix + "active_effects[].id", effectIds);
            domains.put(prefix + "attributes[].id", attributeIds);
            domains.put(
                    prefix + "attributes[].modifiers[].operation",
                    java.util.Arrays.stream(
                                    net.minecraft.world.entity.ai.attributes.AttributeModifier
                                            .Operation.values())
                            .map(v -> StringTag.valueOf(v.getSerializedName()).toString())
                            .toList());
            for (String key : java.util.List.of("ambient", "show_particles", "show_icon"))
                domains.put(prefix + "active_effects[]." + key, java.util.List.of("0b", "1b"));
            fields.put(
                    prefix + "active_effects[].amplifier",
                    new DataSuggestions.NativeField("EffectAmplifier", 0, 255, java.util.Map.of()));
            fields.put(
                    prefix + "active_effects[].duration",
                    new DataSuggestions.NativeField(
                            "EffectDuration", -1, Integer.MAX_VALUE, java.util.Map.of()));
            fields.put(
                    prefix + "PersistenceRequired",
                    new DataSuggestions.NativeField(
                            "PersistenceRequired", 0, 1, java.util.Map.of()));
        }
    }

    private static <T> java.util.List<String> registryIds(
            Level level,
            net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key) {
        return level.holderLookup(key)
                .listElementIds()
                .map(k -> k.location().toString())
                .sorted()
                .limit(4096)
                .map(id -> StringTag.valueOf(id).toString())
                .toList();
    }

    /** Native attachment encodings differ: frames use 3D, paintings use horizontal 2D. */
    public static boolean attachmentRotation(String id) {
        var key = ResourceLocation.tryParse(id);
        var type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        return type == net.minecraft.world.entity.EntityType.ITEM_FRAME
                || type == net.minecraft.world.entity.EntityType.GLOW_ITEM_FRAME
                || type == net.minecraft.world.entity.EntityType.PAINTING;
    }

    /** Detached brush/selection turn: native attachment faces, otherwise one 15-degree yaw step. */
    public static CompoundTag turn(CompoundTag source, Level level, int direction) {
        if (attachmentRotation(source.getString("id")))
            return turnAttachment(source, level, direction);
        var result = source.copy();
        if (direction == 0) return result;
        var rotation = source.getList("Rotation", Tag.TAG_FLOAT);
        var turned = new ListTag();
        turned.add(FloatTag.valueOf(rotation.getFloat(0) + Integer.signum(direction) * 15));
        turned.add(FloatTag.valueOf(rotation.getFloat(1)));
        result.put("Rotation", turned);
        return result;
    }

    public static CompoundTag turnAttachment(CompoundTag source, Level level, int turn) {
        if (turn == 0) return source.copy();
        turn = Integer.signum(turn);
        if (!attachmentRotation(source.getString("id")))
            throw new IllegalArgumentException(
                    "This native entity has no supported attachment turn");
        var type =
                BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(source.getString("id")));
        var entity = type.create(level);
        PreviewEntityData.loadAt(entity, source, net.minecraft.core.BlockPos.ZERO);
        var direction = entity.getDirection();
        var result = source.copy();
        boolean frame = entity instanceof net.minecraft.world.entity.decoration.ItemFrame;
        // Q/E selects the entity's representable attachment, not its displayed item's rotation.
        // Vertical empty frames have no item to rotate and must not become an invisible dead end.
        var faces =
                java.util.List.of(
                        net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                        net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST,
                        net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN);
        var next =
                frame
                        ? faces.get(Math.floorMod(faces.indexOf(direction) + turn, faces.size()))
                        : turn > 0 ? direction.getClockWise() : direction.getCounterClockWise();
        result.putByte(
                frame ? "Facing" : "facing",
                (byte) (frame ? next.get3DDataValue() : next.get2DDataValue()));
        return result;
    }

    private static CompoundTag defaults(
            CompoundTag source,
            Level level,
            java.util.Map<String, DataSuggestions.NativeField> fields) {
        return defaults(source, level, fields, null);
    }

    private static CompoundTag defaults(
            CompoundTag source,
            Level level,
            java.util.Map<String, DataSuggestions.NativeField> fields,
            java.util.Map<String, java.util.List<String>> domains) {
        var entityKey = ResourceLocation.tryParse(source.getString("id"));
        var type =
                entityKey == null
                        ? null
                        : BuiltInRegistries.ENTITY_TYPE.getOptional(entityKey).orElse(null);
        if (type == null) return source.copy();
        var entity = type.create(level);
        for (var code : com.eynny.npships.authoring.patches.PatchSourceOperationsDispatch.all())
            code.entityDefaults(entity);
        var result = new CompoundTag();
        if (entity != null) {
            try {
                entity.saveWithoutId(result);
            } catch (RuntimeException incompleteNativeEntity) {
                // A Create carriage, for example, has no TrainId before assembly. Keep the
                // fields already written and the complete stored source; never invent a link.
                if (level.isClientSide
                        ? com.eynny.npships.network.DevelopmentModeNetworking.clientEnabled()
                        : com.eynny.npships.config.NPShipsRuntimeConfig.developerModeEnabled())
                    com.mojang.logging.LogUtils.getLogger()
                            .debug(
                                    "[NPShips editor] Native defaults incomplete for {}; using saved parameters",
                                    source.getString("id"));
            }
        }
        if (fields != null && entity != null)
            NativeSerializationFields.of(entity.getClass()).expand(result);
        if (fields != null && entity != null) fields.putAll(NativeDataFields.enums(entity, result));
        if (fields != null
                && (entity instanceof net.minecraft.world.entity.decoration.ItemFrame
                        || entity instanceof net.minecraft.world.entity.decoration.Painting)) {
            boolean frame = entity instanceof net.minecraft.world.entity.decoration.ItemFrame;
            var directions = new java.util.LinkedHashMap<Integer, String>();
            for (var direction : net.minecraft.core.Direction.values()) {
                if (!frame && direction.getAxis().isVertical()) continue;
                directions.put(
                        frame ? direction.get3DDataValue() : direction.get2DDataValue(),
                        direction.getSerializedName());
            }
            fields.put(
                    frame ? "Facing" : "facing",
                    new DataSuggestions.NativeField(
                            "Direction", 0, frame ? 5 : 3, java.util.Map.copyOf(directions)));
        }
        if (domains != null && entity instanceof net.minecraft.world.entity.LivingEntity)
            livingReference(result, level, fields, domains);
        result.merge(source.copy());
        if (entity instanceof net.minecraft.world.entity.vehicle.MinecartCommandBlock) {
            if (!result.contains("Command")) result.putString("Command", "");
            if (!result.contains("TrackOutput")) result.putBoolean("TrackOutput", true);
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
            var pose = result.getCompound("Pose");
            for (var key :
                    java.util.List.of("Head", "Body", "LeftArm", "RightArm", "LeftLeg", "RightLeg"))
                if (!pose.contains(key)) {
                    var rotation =
                            switch (key) {
                                case "Head" -> stand.getHeadPose();
                                case "Body" -> stand.getBodyPose();
                                case "LeftArm" -> stand.getLeftArmPose();
                                case "RightArm" -> stand.getRightArmPose();
                                case "LeftLeg" -> stand.getLeftLegPose();
                                default -> stand.getRightLegPose();
                            };
                    pose.put(key, rotation.save());
                }
            result.put("Pose", pose);
        }
        if (entity instanceof net.minecraft.world.entity.LivingEntity) {
            var persistent = result.getCompound("NeoForgeData");
            if (!persistent.contains(com.eynny.npships.ship.EntityHeadPose.KEY))
                persistent.putFloat(com.eynny.npships.ship.EntityHeadPose.KEY, 0f);
            result.put("NeoForgeData", persistent);
        }
        BlockSettingTemplates.expand(
                result,
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.entity(
                        source.getString("id")));
        return result;
    }

    public static CompoundTag apply(CompoundTag saved, CompoundTag before, CompoundTag after) {
        var result = saved.copy();
        var keys = new java.util.HashSet<>(before.getAllKeys());
        keys.addAll(after.getAllKeys());
        for (var key : keys) {
            if (java.util.Set.of("id", "UUID", "Pos", "Motion").contains(key)) continue;
            if (java.util.Objects.equals(before.get(key), after.get(key))) continue;
            if (after.contains(key)) result.put(key, after.get(key).copy());
            else result.remove(key);
        }
        BlockSettingTemplates.normalizeChangedItems(
                result,
                before,
                after,
                com.eynny.npships.authoring.patches.PatchFieldTemplates.Subject.entity(
                        saved.getString("id")),
                saved);
        return result;
    }
}
