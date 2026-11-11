package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.document.SchematicBlocks;
import com.eynny.npships.authoring.patches.ExternalPatches;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;

import java.util.*;

/**
 * Native device forms. Stateless factories share the frozen provider owner; a form borrows the
 * panel only during rebuild.
 */
public final class BuiltinPatchInspectors {
    private BuiltinPatchInspectors() {}

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract != PatchInspectorContribution.class
                || !Set.of(
                                "computercraft_computer",
                                "computercraft_peripheral",
                                "computercraft_media",
                                "simulated_swivel",
                                "simulated_docking",
                                "radars_network",
                                "radars_guided_fuzes",
                                "create_arm",
                                "simulated_spring",
                                "simulated_rope",
                                "aeronautics_lifting_gas")
                        .contains(provider)) return null;
        return contract.cast(
                new PatchInspectorContribution() {
                    public boolean headerApplies(EditorToolsPanel panel, JsonObject selected) {
                        String kind = PatchDevices.kind(panel.cached, panel.pos());
                        return provider.equals("computercraft_computer") && kind.equals("computer")
                                || provider.equals("computercraft_peripheral")
                                        && (panel.cached
                                                        .blockName(panel.pos())
                                                        .equals("computercraft:wired_modem_full")
                                                || com.eynny.npships.integration.computercraft
                                                        .ComputerCables.modem(
                                                        panel.cached, panel.pos()));
                    }

                    public boolean fieldsApply(EditorToolsPanel panel, JsonObject patch) {
                        String kind = patch.get("kind").getAsString();
                        return switch (provider) {
                            case "computercraft_computer" -> kind.equals("computer");
                            case "computercraft_media" ->
                                    patch.has("media") && !patch.getAsJsonObject("media").isEmpty();
                            case "simulated_swivel" -> kind.equals("mechanism");
                            case "radars_network" -> kind.equals("radar");
                            case "radars_guided_fuzes" ->
                                    !kind.equals("radar")
                                            && !com.eynny.npships.integration.radars.RadarFuzes
                                                    .stacks(
                                                            panel.cached.blockEntityData(
                                                                    panel.pos()))
                                                    .isEmpty();
                            case "create_arm" -> kind.equals("arm");
                            case "aeronautics_lifting_gas" -> kind.equals("balloon");
                            default -> provider.equals("simulated_" + kind);
                        };
                    }

                    public void header(EditorToolsPanel panel, JsonObject patch) {
                        new Form(panel, provider).header(Optional.ofNullable(patch));
                    }

                    public void fields(EditorToolsPanel panel, JsonObject patch) {
                        new Form(panel, provider).fields(patch);
                    }
                });
    }

    private static final class Form {
        private final EditorToolsPanel panel;
        private final String provider;

        Form(EditorToolsPanel panel, String provider) {
            this.panel = panel;
            this.provider = provider;
        }

        void header(Optional<JsonObject> found) {
            String id = panel.cached.blockName(panel.pos());
            if (provider.equals("computercraft_computer")
                    && PatchDevices.kind(panel.cached, panel.pos()).equals("computer")
                    && found.isPresent()
                    && !found.get().get("kind").getAsString().equals("computer")) computerPower();
            if (provider.equals("computercraft_computer")
                    && PatchDevices.kind(panel.cached, panel.pos()).equals("computer")
                    && found.isEmpty()) {
                panel.sections.group(
                        "computer.files",
                        "section_computer_files",
                        true,
                        () -> computerFiles(null));
                panel.sections.group(
                        "computer.references",
                        "section_device_references",
                        true,
                        this::networkDevices);
            }
            if (provider.equals("computercraft_peripheral")
                    && (id.equals("computercraft:wired_modem_full")
                            || com.eynny.npships.integration.computercraft.ComputerCables.modem(
                                    panel.cached, panel.pos()))) {
                var network =
                        new com.eynny.npships.integration.computercraft.ComputerNetwork(
                                panel.cached);
                boolean shared = network.shared(panel.pos());
                panel.button(
                                panel.tr("cc_share").getString(),
                                () ->
                                        toggleSharing(
                                                !new com.eynny.npships.integration.computercraft
                                                                .ComputerNetwork(panel.doc())
                                                        .shared(panel.pos())))
                        .selected(() -> shared);
                boolean available = net.neoforged.fml.ModList.get().isLoaded("computercraft");
                panel.widgets.getLast().active = available;
                panel.widgets
                        .getLast()
                        .setTooltip(
                                Tooltip.create(
                                        panel.tr(available ? "cc_share_hint" : "patch_data_only")));
            }
        }

        void fields(JsonObject patch) {
            String kind = patch.get("kind").getAsString();
            String id = panel.cached.blockName(panel.pos());
            if (provider.equals("computercraft_computer") && kind.equals("computer")) {
                panel.sections.group(
                        "computer.files",
                        "section_computer_files",
                        true,
                        () -> computerFiles(patch));
            }
            if (provider.equals("computercraft_media")
                    && patch.has("media")
                    && !patch.getAsJsonObject("media").isEmpty()) {
                panel.sections.begin("computer.media", "section_computer_media", true);
                for (var disk : patch.getAsJsonObject("media").entrySet()) {
                    String diskId = disk.getKey();
                    String[] path = {"startup.lua"};
                    panel.field("media_file", path[0], v -> path[0] = v);
                    panel.button(
                            Component.translatable("npships.tools.media_new_file", diskId)
                                    .getString(),
                            () ->
                                    BuiltinComputerWorkspaces.openMedia(
                                            panel, patch, diskId, path[0], ""));
                    for (var file : disk.getValue().getAsJsonObject().entrySet())
                        panel.button(
                                Component.translatable(
                                                "npships.tools.media_file_entry",
                                                diskId,
                                                file.getKey())
                                        .getString(),
                                () ->
                                        BuiltinComputerWorkspaces.openMedia(
                                                panel, patch, diskId, file.getKey(), ""));
                }
                panel.sections.end();
            }
            if (provider.equals("simulated_swivel") && kind.equals("mechanism"))
                panel.button(
                                panel.tr("assemble_after_spawn").getString()
                                        + ": "
                                        + (patch.has("assemble")
                                                && patch.get("assemble").getAsBoolean()),
                                () -> {
                                    var next = panel.currentPatch(patch);
                                    next.addProperty(
                                            "assemble",
                                            !next.has("assemble")
                                                    || !next.get("assemble").getAsBoolean());
                                    panel.putPatch(next);
                                })
                        .selected(
                                () ->
                                        patch.has("assemble")
                                                && patch.get("assemble").getAsBoolean());
            if (provider.equals("simulated_docking") && kind.equals("docking")) {
                panel.sections.begin("docking", "section_docking", true);
                panel.action(
                        "link_docking",
                        () -> panel.beginLink(panel.currentPatch(patch), "docking"));
                if (patch.has("target"))
                    panel.action(
                            "link_clear",
                            () -> {
                                var next = panel.currentPatch(patch);
                                next.remove("target");
                                panel.putPatch(next);
                            });
                panel.sections.end();
            }
            if (provider.equals("computercraft_computer") && kind.equals("computer"))
                panel.sections.group(
                        "computer.references",
                        "section_device_references",
                        true,
                        this::networkDevices);
            if (provider.equals("radars_network") && kind.equals("radar")) {
                panel.sections.begin("radar", "section_radar_links", true);
                if (!com.eynny.npships.integration.radars.RadarWiring.role(id).isEmpty())
                    panel.action(
                            "link_device",
                            () -> panel.beginLink(panel.currentPatch(patch), "device"));
                if (id.equals("create_radar:identification_transponder")) {
                    var initial =
                            patch.has("identification")
                                    ? patch.getAsJsonObject("identification")
                                    : new JsonObject();
                    String[] name = {initial.has("name") ? initial.get("name").getAsString() : ""},
                            code = {initial.has("code") ? initial.get("code").getAsString() : ""};
                    panel.field("transponder_name", name[0], v -> name[0] = v);
                    panel.field("transponder_code", code[0], v -> code[0] = v);
                    panel.defer(
                            () -> {
                                var next = panel.currentPatch(patch);
                                var identity =
                                        next.has("identification")
                                                ? next.getAsJsonObject("identification").deepCopy()
                                                : new JsonObject();
                                identity.addProperty("name", name[0]);
                                identity.addProperty("code", code[0]);
                                if (identity.equals(initial)) return;
                                next.add("identification", identity);
                                panel.putPatch(next);
                            });
                }
                if (patch.has("network")
                        || patch.has("weapon")
                        || patch.has("datalink")
                        || patch.has("fuze_bindings"))
                    panel.action(
                            "link_clear",
                            () -> {
                                var next = panel.currentPatch(patch);
                                next.remove("network");
                                next.remove("weapon");
                                next.remove("datalink");
                                if (next.has("fuze_bindings"))
                                    next.add("fuze_bindings", new JsonArray());
                                panel.putPatch(next);
                            });
                if (patch.has("network")) {
                    var net = patch.getAsJsonObject("network");
                    if (net.has("radar"))
                        panel.action(
                                "unlink_radar",
                                () -> {
                                    var next = panel.currentPatch(patch);
                                    next.getAsJsonObject("network").remove("radar");
                                    next.getAsJsonObject("network").remove("radar_kind");
                                    panel.putPatch(next);
                                });
                    for (String key : List.of("monitors", "controllers", "links"))
                        if (net.has(key))
                            for (int i = 0; i < net.getAsJsonArray(key).size(); i++) {
                                int index = i;
                                var selectedRow = net.getAsJsonArray(key).get(i).deepCopy();
                                panel.button(
                                        panel.tr("remove_link").getString()
                                                + " "
                                                + key
                                                + " "
                                                + (i + 1),
                                        () -> {
                                            var next = panel.currentPatch(patch);
                                            next.getAsJsonObject("network")
                                                    .getAsJsonArray(key)
                                                    .remove(
                                                            panel.currentRow(
                                                                    next.getAsJsonObject("network")
                                                                            .getAsJsonArray(key),
                                                                    selectedRow,
                                                                    index,
                                                                    net.getAsJsonArray(key)));
                                            panel.putPatch(next);
                                        });
                            }
                }
                if (!com.eynny.npships.integration.radars.RadarFuzes.stacks(
                                panel.cached.blockEntityData(panel.pos()))
                        .isEmpty())
                    panel.action(
                            "link_fuzes",
                            () -> panel.beginLink(panel.currentPatch(patch), "fuzes"));
                panel.sections.end();
            }
            if (provider.equals("radars_guided_fuzes")
                    && !kind.equals("radar")
                    && !com.eynny.npships.integration.radars.RadarFuzes.stacks(
                                    panel.cached.blockEntityData(panel.pos()))
                            .isEmpty())
                panel.action(
                        "link_fuzes", () -> panel.beginLink(panel.currentPatch(patch), "fuzes"));
            if (provider.equals("create_arm") && kind.equals("arm")) {
                panel.sections.begin("arm", "section_arm_points", true);
                panel.action(
                        "arm_take", () -> panel.beginLink(panel.currentPatch(patch), "arm_take"));
                panel.action(
                        "arm_deposit",
                        () -> panel.beginLink(panel.currentPatch(patch), "arm_deposit"));
                String mode = patch.get("selection_mode").getAsString();
                panel.button(
                        panel.tr("arm_mode").getString()
                                + ": "
                                + Component.translatable(
                                                "create.mechanical_arm.selection_mode."
                                                        + mode.toLowerCase(Locale.ROOT))
                                        .getString(),
                        () -> {
                            var next = panel.currentPatch(patch);
                            var modes =
                                    com.simibubi.create.content.kinetics.mechanicalArm
                                            .ArmBlockEntity.SelectionMode.values();
                            next.addProperty(
                                    "selection_mode",
                                    modes[
                                            (com.simibubi
                                                                    .create
                                                                    .content
                                                                    .kinetics
                                                                    .mechanicalArm
                                                                    .ArmBlockEntity
                                                                    .SelectionMode
                                                                    .valueOf(
                                                                            next.get(
                                                                                            "selection_mode")
                                                                                    .getAsString())
                                                                    .ordinal()
                                                            + 1)
                                                    % modes.length]
                                            .name());
                            panel.putPatch(next);
                        });
                panel.widgets.getLast().setTooltip(Tooltip.create(panel.tr("arm_mode_hint")));
                var points = patch.getAsJsonArray("points");
                for (int i = 0; i < points.size(); i++) {
                    int index = i;
                    var point = points.get(i).getAsJsonObject();
                    String direction;
                    try {
                        direction =
                                com.eynny.npships.authoring.document.SnbtText.nativeCompound(
                                                point.get("data").getAsString())
                                        .getString("Mode");
                    } catch (Exception e) {
                        direction = "?";
                    }
                    var target =
                            SchematicPatches.position(patch.getAsJsonArray("pos"))
                                    .offset(
                                            SchematicPatches.position(
                                                    point.getAsJsonArray("offset")));
                    panel.button(
                            panel.tr(direction.equals("TAKE") ? "arm_take" : "arm_deposit")
                                            .getString()
                                    + " "
                                    + target.toShortString()
                                    + " [x]",
                            () -> {
                                var next = panel.currentPatch(patch);
                                next.getAsJsonArray("points")
                                        .remove(
                                                panel.currentRow(
                                                        next.getAsJsonArray("points"),
                                                        point,
                                                        index,
                                                        points));
                                panel.putPatch(next);
                            });
                    if (i > 0)
                        panel.button(
                                panel.tr("arm_earlier").getString() + " #" + (i + 1),
                                () -> {
                                    var next = panel.currentPatch(patch);
                                    var list = next.getAsJsonArray("points");
                                    int selectedIndex =
                                            panel.currentRow(list, point, index, points);
                                    if (selectedIndex == 0) return;
                                    var previous = list.get(selectedIndex - 1);
                                    list.set(selectedIndex - 1, list.get(selectedIndex));
                                    list.set(selectedIndex, previous);
                                    panel.putPatch(next);
                                });
                }
                panel.sections.end();
            }
            if (provider.equals("simulated_spring") && kind.equals("spring")) {
                panel.sections.begin("spring", "section_spring", true);
                panel.action(
                        "link_spring", () -> panel.beginLink(panel.currentPatch(patch), "spring"));
                panel.action(
                        "link_clear",
                        () -> {
                            var next = panel.currentPatch(patch);
                            next.remove("target");
                            panel.putPatch(next);
                        });
                String[] length = {"" + SpringPatch.length(patch)};
                String initialLength = length[0];
                panel.field("spring_length", length[0], v -> length[0] = v);
                for (double step : new double[] {-.25, .25})
                    panel.button(
                            step < 0 ? "-0.25" : "+0.25",
                            () -> {
                                var next = panel.currentPatch(patch);
                                next.addProperty(
                                        "length",
                                        Math.clamp(SpringPatch.length(next) + step, 1, 9));
                                panel.putPatch(next);
                            });
                panel.defer(
                        () -> {
                            if (initialLength.equals(length[0])) return;
                            var next = panel.currentPatch(patch);
                            next.addProperty("length", Double.parseDouble(length[0]));
                            panel.putPatch(next);
                        });
                var springState = panel.selectedBlockState();
                panel.button(
                                panel.tr("spring_stiffness").getString()
                                        + ": "
                                        + springState.getValue(
                                                dev.simulated_team.simulated.content.blocks.spring
                                                        .SpringBlock.SIZE),
                                () -> {
                                    var acceptedPatch = panel.currentPatch(patch);
                                    var anchor =
                                            SchematicPatches.position(
                                                    acceptedPatch.getAsJsonArray("pos"));
                                    var current =
                                            panel.currentBlock(anchor, springState.getBlock());
                                    var size =
                                            current.cycle(
                                                            dev.simulated_team.simulated.content
                                                                    .blocks.spring.SpringBlock.SIZE)
                                                    .getValue(
                                                            dev.simulated_team.simulated.content
                                                                    .blocks.spring.SpringBlock
                                                                    .SIZE);
                                    var next = panel.doc();
                                    var endpoints =
                                            new java.util.ArrayList<net.minecraft.core.BlockPos>(
                                                    PatchDevices.targets(acceptedPatch));
                                    endpoints.add(anchor);
                                    for (var at : endpoints) {
                                        if (!next.blocks().containsKey(at)
                                                || !SpringPatch.matches(next.blockName(at)))
                                            throw invalid(
                                                    "action_target_changed",
                                                    panel.tr("action_target_changed").getString());
                                        var state =
                                                net.minecraft.nbt.NbtUtils.readBlockState(
                                                        panel.mc.level.holderLookup(
                                                                net.minecraft.core.registries
                                                                        .Registries.BLOCK),
                                                        next.paletteCopy()
                                                                .getCompound(
                                                                        next.blocks().get(at)));
                                        next =
                                                SchematicBlocks.put(
                                                        next,
                                                        at,
                                                        at,
                                                        state.setValue(
                                                                dev.simulated_team.simulated.content
                                                                        .blocks.spring.SpringBlock
                                                                        .SIZE,
                                                                size),
                                                        null);
                                    }
                                    panel.commit(next);
                                })
                        .setTooltip(
                                net.minecraft.client.gui.components.Tooltip.create(
                                        panel.tr("spring_stiffness_hint")));
                panel.sections.end();
            }
            if (provider.equals("simulated_rope") && kind.equals("rope")) {
                panel.sections.begin("rope", "section_rope", true);
                panel.action("link_rope", () -> panel.beginLink(panel.currentPatch(patch), "rope"));
                panel.action(
                        "link_clear",
                        () -> {
                            var next = panel.currentPatch(patch);
                            next.remove("target");
                            next.remove("extension");
                            panel.putPatch(next);
                        });
                panel.sections.end();
            }
            if (provider.equals("aeronautics_lifting_gas") && kind.equals("balloon")) {
                panel.sections.begin("gas", "section_gas", true);
                panel.patchEditors.label();
                String[] volume = {
                    patch.has("fill_volume") ? patch.get("fill_volume").getAsString() : ""
                };
                var volumeField =
                        panel.field(
                                "fill_volume",
                                volume[0],
                                v -> {
                                    volume[0] = v;
                                    panel.settingsDirty = true;
                                });
                String initialVolume = volume[0];
                panel.pendingSettings =
                        () ->
                                panel.validateField(
                                        volumeField,
                                        () -> {
                                            if (volume[0].equals(initialVolume)) return;
                                            var next = panel.currentPatch(patch);
                                            double n = Double.parseDouble(volume[0]);
                                            next.remove("gas");
                                            next.addProperty("fill_volume", n);
                                            panel.putPatch(next);
                                        });
                if (patch.has("gas")) panel.button(panel.tr("gas_captured").getString(), () -> {});
                panel.sections.end();
            }
        }

        private void note(String key) {
            panel.wrappedNote(key);
        }

        private void networkDevices() {
            note("cc_network_hint");
            var network =
                    new com.eynny.npships.integration.computercraft.ComputerNetwork(panel.doc());
            var devices = network.devices(panel.pos());
            for (var patch : ExternalPatches.authoringEntries(panel.doc())) {
                if (ExternalPatches.external(patch)
                        || !patch.get("kind").getAsString().equals("computer")
                        || !SchematicPatches.position(patch.getAsJsonArray("pos"))
                                .equals(panel.pos())
                        || !patch.has("references")) continue;
                for (var entry : patch.getAsJsonObject("references").entrySet()) {
                    var target =
                            panel.pos()
                                    .offset(
                                            SchematicPatches.position(
                                                    entry.getValue()
                                                            .getAsJsonObject()
                                                            .getAsJsonArray("offset")));
                    if (network.referenceDevice(panel.pos(), target).isEmpty()) {
                        var missing =
                                panel.button(
                                        panel.tr("cc_unavailable").getString()
                                                + ": "
                                                + entry.getKey(),
                                        () -> {});
                        missing.active = false;
                        missing.setTooltip(Tooltip.create(panel.tr("cc_unavailable_hint")));
                    }
                }
            }
            if (devices.isEmpty() && network.builtins(panel.pos()).isEmpty())
                note("cc_network_empty");
            network.builtins(panel.pos())
                    .forEach(
                            (side, type) ->
                                    panel.button(
                                                            type
                                                                    + " / "
                                                                    + panel.tr("cc_route_" + side)
                                                                            .getString(),
                                                            () -> {})
                                                    .active =
                                            false);
            for (var device : devices) {
                panel.button(
                        device.type()
                                + " / "
                                + panel.tr("cc_route_" + device.route()).getString()
                                + " / "
                                + device.pos().toShortString(),
                        () -> {
                            panel.host.select(device.pos());
                            panel.scroll = 0;
                            panel.build();
                        });
            }
        }

        private void toggleSharing(boolean enable) {
            var p = panel.pos();
            var doc = panel.doc();
            String key =
                    doc.blockName(p).equals("computercraft:wired_modem_full")
                            ? "peripheral"
                            : "modem";
            var encoded = doc.paletteCopy().getCompound(doc.blocks().get(p));
            var props = encoded.getCompound("Properties");
            String value = props.getString(key);
            if (key.equals("peripheral")) value = Boolean.toString(enable);
            else value = value.replace("_peripheral", "") + (enable ? "_peripheral" : "");
            props.putString(key, value);
            encoded.put("Properties", props);
            var state =
                    net.minecraft.nbt.NbtUtils.readBlockState(
                            panel.mc.level.holderLookup(
                                    net.minecraft.core.registries.Registries.BLOCK),
                            encoded);
            panel.commit(SchematicBlocks.put(doc, p, p, state, null));
        }

        private void computerPower() {
            var target = panel.pos().immutable();
            var block = panel.selectedBlockState().getBlock();
            String id = panel.doc().blockName(target);
            panel.button(
                            panel.tr("cc_power_on").getString(),
                            () -> {
                                panel.currentBlock(target, block);
                                var data = panel.doc().blockEntityData(target);
                                data.putBoolean("On", !data.getBoolean("On"));
                                panel.commit(
                                        com.eynny.npships.authoring.document.SchematicEdits
                                                .blockData(panel.doc(), target, data));
                            })
                    .selected(
                            () ->
                                    target.equals(panel.pos())
                                            && panel.doc().blockName(target).equals(id)
                                            && panel.doc().blockEntityBoolean(target, "", "On"))
                    .setTooltip(Tooltip.create(panel.tr("cc_power_on_hint")));
        }

        private void computerFiles(JsonObject patch) {
            computerPower();
            panel.action(
                    "cc_edit_startup",
                    () -> {
                        var target =
                                patch == null
                                        ? PatchDevices.create(panel.doc(), panel.pos())
                                        : panel.currentPatch(patch);
                        BuiltinComputerWorkspaces.openComputer(
                                panel,
                                target,
                                "startup.lua",
                                target.getAsJsonObject("files").has("startup.lua")
                                        ? BuiltinComputerWorkspaces.decodeText(
                                                target.getAsJsonObject("files")
                                                        .get("startup.lua")
                                                        .getAsString())
                                        : "");
                    });
            if (patch == null) return;
            String[] path = {"startup.lua"};
            var pathField = panel.field("file", path[0], v -> path[0] = v);
            panel.action(
                    "new_file",
                    () -> {
                        panel.validateField(
                                pathField,
                                () -> {
                                    if (panel.currentPatch(patch)
                                            .getAsJsonObject("files")
                                            .has(path[0]))
                                        throw invalid(
                                                "file_exists", "File already exists.", path[0]);
                                });
                        BuiltinComputerWorkspaces.openComputer(
                                panel, panel.currentPatch(patch), path[0], "");
                    });
            panel.widgets.getLast().setTooltip(Tooltip.create(panel.tr("new_file_hint")));
            for (var file : patch.getAsJsonObject("files").entrySet())
                panel.button(
                        file.getKey(),
                        () ->
                                BuiltinComputerWorkspaces.openComputer(
                                        panel,
                                        panel.currentPatch(patch),
                                        file.getKey(),
                                        BuiltinComputerWorkspaces.decodeText(
                                                panel.currentPatch(patch)
                                                        .getAsJsonObject("files")
                                                        .get(file.getKey())
                                                        .getAsString())));
        }
    }
}
