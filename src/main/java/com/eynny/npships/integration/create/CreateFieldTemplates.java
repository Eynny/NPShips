package com.eynny.npships.integration.create;

import com.eynny.npships.authoring.patches.PatchFieldTemplates;

import net.minecraft.nbt.*;

import java.util.*;

/** Create serializer reference shapes, owned by the registered Create document provider. */
public final class CreateFieldTemplates implements PatchFieldTemplates {
    @Override
    public boolean applies(Subject subject) {
        return subject.id().startsWith("create:");
    }

    @Override
    public java.util.Set<String> validationFields() {
        return java.util.Set.of("Filter", "HeldItem");
    }

    @Override
    public boolean itemField(Subject subject, List<String> path) {
        if (path.isEmpty()) return false;
        String key = path.getLast();
        if (key.equals("icon"))
            return path.size() > 2
                    && path.getFirst().equals("components")
                    && path.get(1).equals("create:clipboard_content");
        if (path.size() != 1) return false;
        if (key.equals("FrequencyFirst") || key.equals("FrequencyLast"))
            return subject.id().equals("create:redstone_link");
        if (key.equals("HeldItem"))
            return subject.id().equals("create:mechanical_arm")
                    || subject.id().endsWith("deployer");
        return key.equals("Filter") && !path.contains("Source");
    }

    @Override
    public Tag entry(Subject subject, String path) {
        if (subject.id().equals("create:belt") && path.equals("/Inventory/Items")) {
            var wrapper = new CompoundTag();
            var item = new CompoundTag();
            item.putString("id", "minecraft:stone");
            item.putInt("count", 1);
            wrapper.put("Item", item);
            for (String key : List.of("Pos", "PrevPos", "Offset", "PrevOffset"))
                wrapper.putFloat(key, 0);
            for (String key : List.of("InSegment", "Angle", "InDirection")) wrapper.putInt(key, 0);
            wrapper.putBoolean("Locked", false);
            return wrapper;
        }
        return entry(path);
    }

    @Override
    public void expand(CompoundTag tag) {
        if (tag.contains("Width", Tag.TAG_FLOAT)
                && tag.contains("Cycle", Tag.TAG_STRING)
                && !tag.contains("Text")) tag.putString("Text", "\"\"");
    }

    @Override
    public Tag entry(String path) {
        if (path.endsWith("/create:clipboard_content/pages")) return new ListTag();
        if (path.matches(".*/create:clipboard_content/pages/[0-9]+")) {
            var line = new CompoundTag();
            line.putBoolean("checked", false);
            line.putString("text", "");
            line.put("icon", new CompoundTag());
            line.putInt("item_amount", 0);
            return line;
        }
        return null;
    }

    @Override
    public List<String> choices(String path, String key) {
        if (key.equals("PreferredSpoutput"))
            return List.of("DOWN", "NORTH", "EAST", "SOUTH", "WEST");
        if (key.equals("type") && path.endsWith("/create:clipboard_content"))
            return List.of("empty", "written", "editing");
        return switch (key) {
            case "Casing" -> List.of("NONE", "ANDESITE", "BRASS");
            case "Mode" -> List.of("USE", "PUNCH");
            case "DoorControl" -> List.of("ALL", "NORTH", "EAST", "SOUTH", "WEST", "NONE");
            case "Modifier" ->
                    Arrays.stream(
                                    com.simibubi.create.content.kinetics.transmission.sequencer
                                            .InstructionSpeedModifiers.values())
                            .map(Enum::name)
                            .toList();
            case "Type" ->
                    path.startsWith("/Instructions")
                            ? List.of("TURN_ANGLE", "TURN_DISTANCE", "DELAY", "AWAIT", "END")
                            : List.of();
            default -> List.of();
        };
    }
}
