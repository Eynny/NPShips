package com.eynny.npships.client.editor;

import static com.eynny.npships.authoring.validation.ValidationProblem.invalid;

import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;
import com.eynny.npships.client.editor.render.SchematicPreviewLevel;
import com.eynny.npships.integration.aeronautics.SpringPatch;
import com.google.gson.*;

import net.minecraft.client.gui.components.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.*;

import java.util.*;

/** Native link algorithms behind their registered provider's bounded editor session. */
public final class BuiltinDeviceLinks {
    private BuiltinDeviceLinks() {}

    public static <T> T capability(String provider, Class<T> contract) {
        if (contract != PatchLinkContribution.Factory.class) return null;
        Set<String> roles =
                switch (provider) {
                    case "create_arm" -> Set.of("arm_take", "arm_deposit");
                    case "computercraft_computer" -> Set.of("reference", "reference_owner");
                    case "simulated_spring" -> Set.of("spring");
                    case "simulated_rope" -> Set.of("rope");
                    case "simulated_docking" -> Set.of("docking");
                    case "radars_network" ->
                            Set.of(
                                    "device",
                                    "radar",
                                    "monitor",
                                    "controller",
                                    "network_link",
                                    "datalink",
                                    "yaw",
                                    "pitch",
                                    "firing");
                    case "radars_guided_fuzes" -> Set.of("fuzes");
                    default -> Set.of();
                };
        if (roles.isEmpty()) return null;
        return contract.cast(
                new PatchLinkContribution.Factory() {
                    public boolean incomingApplies(JsonObject record) {
                        return (provider.equals("simulated_rope")
                                        && record.get("kind").getAsString().equals("rope"))
                                || (provider.equals("simulated_spring")
                                        && record.get("kind").getAsString().equals("spring"));
                    }

                    public void incoming(
                            EditorToolsPanel panel, JsonObject expected, BlockPos target) {
                        panel.action(
                                "link_clear",
                                () -> {
                                    var current = panel.currentPatch(expected);
                                    if (!Objects.equals(target, panel.pos())
                                            || !PatchDevices.targets(current).contains(target))
                                        throw new IllegalArgumentException(
                                                "Incoming link selection changed");
                                    var next = current.deepCopy();
                                    next.remove("target");
                                    next.remove("extension");
                                    panel.commit(PatchDevices.put(panel.doc(), next));
                                });
                    }

                    public Set<String> roles() {
                        return roles;
                    }

                    public String section(String role) {
                        if (role.startsWith("reference")) return "computer.references";
                        if (role.startsWith("arm_")) return "arm";
                        return switch (role) {
                            case "spring", "rope", "docking" -> role;
                            default -> "radar";
                        };
                    }

                    public PatchLinkContribution open(EditorToolsPanel panel) {
                        return new Session(panel);
                    }
                });
    }

    private static final class Session implements PatchLinkContribution {
        private final EditorToolsPanel panel;
        private com.eynny.npships.client.editor.render.SchematicPreviewLevel armLinkView;
        private Set<BlockPos> computerReferenceTargets = Set.of();

        Session(EditorToolsPanel panel) {
            this.panel = panel;
        }

        @Override
        public void begin(JsonObject patch, String role) {
            if (role.startsWith("arm_"))
                armLinkView = new SchematicPreviewLevel(panel.doc(), panel.mc.level);
            if (role.startsWith("reference")
                    && !panel.referenceName.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
                throw new IllegalArgumentException(panel.tr("invalid_reference").getString());
            if (role.equals("reference"))
                computerReferenceTargets =
                        com.eynny.npships.integration.computercraft.ComputerReferences.available(
                                panel.doc(), panel.pos());
            if (role.equals("reference_owner"))
                computerReferenceTargets =
                        com.eynny.npships.integration.computercraft.ComputerReferences.owners(
                                panel.doc(), panel.pos());
            panel.linkPatch = patch.deepCopy();
            panel.linkRole = role;
            panel.firstLink = null;
            panel.build();
        }

        @Override
        public boolean pick(BlockPos p) {
            if (panel.tool != EditorToolsPanel.Tool.PATCHES || panel.linkRole == null) return false;
            if (p == null || !panel.compatibleLink(p)) {
                panel.host.status(panel.tr("choose_compatible").getString());
                return true;
            }
            panel.run(
                    () -> {
                        if (panel.linkRole.startsWith("reference")) {
                            var source =
                                    SchematicPatches.position(
                                            panel.linkPatch.getAsJsonArray("pos"));
                            boolean reverse = panel.linkRole.equals("reference_owner");
                            var owner = reverse ? p : source;
                            panel.commit(
                                    com.eynny.npships.integration.computercraft.ComputerReferences
                                            .assign(
                                                    panel.doc(),
                                                    owner,
                                                    reverse ? source : p,
                                                    panel.referenceName));
                            panel.linkRole = null;
                            panel.host.select(owner);
                            panel.scroll = 0;
                            panel.build();
                            panel.host.status(panel.tr("cc_reference_created").getString());
                            return;
                        }
                        if (panel.linkRole.startsWith("arm_")) {
                            panel.putPatch(
                                    com.eynny.npships.integration.create.MechanicalArmPatch.connect(
                                            panel.doc(),
                                            panel.linkPatch,
                                            armLinkView,
                                            p,
                                            panel.linkRole.equals("arm_take")));
                            panel.linkRole = null;
                            armLinkView = null;
                            panel.build();
                            return;
                        }
                        if (panel.linkRole.equals("device")) {
                            panel.commit(
                                    com.eynny.npships.integration.radars.RadarWiring.connect(
                                            panel.doc(),
                                            SchematicPatches.position(
                                                    panel.linkPatch.getAsJsonArray("pos")),
                                            p));
                            panel.linkRole = null;
                            panel.build();
                            return;
                        }
                        if ((panel.linkRole.equals("controller")
                                        || panel.linkRole.equals("network_link"))
                                && panel.firstLink == null) {
                            panel.firstLink = p;
                            panel.build();
                            panel.host.status(panel.tr("choose_second_device").getString());
                            return;
                        }
                        panel.host.select(
                                SchematicPatches.position(panel.linkPatch.getAsJsonArray("pos")));
                        var next = panel.linkPatch.deepCopy();
                        var target = endpoint(p);
                        switch (panel.linkRole) {
                            case "rope", "docking", "spring" -> {
                                next.add("target", target);
                                next.remove("extension");
                            }
                            case "fuzes" ->
                                    next =
                                            com.eynny.npships.integration.radars.RadarFuzes.bindAll(
                                                    next,
                                                    panel.doc(),
                                                    com.eynny.npships.integration.radars.RadarWiring
                                                            .filterFor(panel.doc(), p));
                            case "datalink" -> next.add("datalink", target);
                            case "yaw", "pitch", "firing" -> {
                                var weapon =
                                        next.has("weapon")
                                                ? next.getAsJsonObject("weapon")
                                                : new JsonObject();
                                if (!weapon.has("links")) weapon.add("links", new JsonArray());
                                weapon.add(
                                        panel.linkRole.equals("yaw")
                                                ? "yawPos"
                                                : panel.linkRole.equals("pitch")
                                                        ? "pitchPos"
                                                        : "firingPos",
                                        target);
                                next.add("weapon", weapon);
                            }
                            default -> {
                                var net = network(next);
                                if (panel.linkRole.equals("radar")) {
                                    net.add("radar", target);
                                    net.addProperty(
                                            "radar_kind",
                                            target.get("block").getAsString().contains("bearing")
                                                    ? "BEARING"
                                                    : "STATIONARY");
                                } else if (panel.linkRole.equals("monitor")) {
                                    if (!net.getAsJsonArray("monitors").asList().contains(target))
                                        net.getAsJsonArray("monitors").add(target);
                                } else {
                                    var pair = new JsonObject();
                                    pair.add(
                                            panel.linkRole.equals("controller")
                                                    ? "controller"
                                                    : "link",
                                            endpoint(panel.firstLink));
                                    pair.add(
                                            panel.linkRole.equals("controller")
                                                    ? "mount"
                                                    : "target",
                                            target);
                                    net.getAsJsonArray(
                                                    panel.linkRole.equals("controller")
                                                            ? "controllers"
                                                            : "links")
                                            .add(pair);
                                }
                                next.add("network", net);
                            }
                        }
                        panel.putPatch(next);
                        panel.linkRole = null;
                        panel.build();
                    });
            return true;
        }

        @Override
        public boolean compatible(BlockPos p) {
            if (panel.linkRole == null
                    || p.equals(SchematicPatches.position(panel.linkPatch.getAsJsonArray("pos"))))
                return false;
            String id = PatchDevices.block(panel.doc(), p),
                    name = id.substring(id.indexOf(':') + 1);
            return switch (panel.linkRole) {
                case "arm_take", "arm_deposit" ->
                        com.eynny.npships.integration.create.MechanicalArmPatch.compatible(
                                armLinkView,
                                SchematicPatches.position(panel.linkPatch.getAsJsonArray("pos")),
                                p);
                case "device" ->
                        com.eynny.npships.integration.radars.RadarWiring.compatible(
                                panel.doc(),
                                SchematicPatches.position(panel.linkPatch.getAsJsonArray("pos")),
                                p);
                case "fuzes" ->
                        com.eynny.npships.integration.radars.RadarWiring.filterFor(panel.doc(), p)
                                != null;
                case "reference", "reference_owner" -> computerReferenceTargets.contains(p);
                case "spring" -> SpringPatch.matches(id);
                case "docking" ->
                        com.eynny.npships.integration.aeronautics.DockingPatch.matches(id);
                case "rope" ->
                        com.eynny.npships.integration.aeronautics.AeronauticsPatch.rope(id)
                                && SchematicPatches.read(panel.doc().configCopy()).stream()
                                        .noneMatch(
                                                patch ->
                                                        "rope"
                                                                        .equals(
                                                                                patch.get("kind")
                                                                                        .getAsString())
                                                                && patch.has("target")
                                                                && (p.equals(
                                                                                SchematicPatches
                                                                                        .position(
                                                                                                patch
                                                                                                        .getAsJsonArray(
                                                                                                                "pos")))
                                                                        || PatchDevices.targets(
                                                                                        patch)
                                                                                .contains(p)));
                case "radar" ->
                        Set.of(
                                        "create_radar:radar_bearing",
                                        "create_radar:sky_radar",
                                        "create_radar:plane_radar",
                                        "create_radar:sonar_bearing")
                                .contains(id);
                case "monitor" -> id.startsWith("create_radar:") && name.contains("monitor");
                case "controller" ->
                        panel.firstLink == null
                                ? id.startsWith("create_radar:") && name.contains("controller")
                                : name.contains("mount");
                case "network_link" ->
                        panel.firstLink == null
                                ? id.startsWith("create_radar:") && name.contains("link")
                                : id.startsWith("create_radar:");
                case "yaw" -> id.equals("create_radar:auto_yaw_controller");
                case "pitch" -> id.equals("create_radar:auto_pitch_controller");
                case "firing" -> id.equals("create_radar:fire_controller");
                default -> id.startsWith("create_radar:") || name.contains("mount");
            };
        }

        JsonObject network(JsonObject patch) {
            if (patch.has("network")) return patch.getAsJsonObject("network");
            var n = new JsonObject();
            for (String key : List.of("monitors", "controllers", "links"))
                n.add(key, new JsonArray());
            return n;
        }

        JsonObject endpoint(BlockPos target) {
            var index = panel.doc().blocks().get(target);
            if (index == null)
                throw invalid(
                        "link_target", "Select a target block inside this schematic.", target);
            var e = new JsonObject();
            e.add("offset", SchematicPatches.vector(target.subtract(panel.pos())));
            e.addProperty("block", panel.doc().paletteCopy().getCompound(index).getString("Name"));
            return e;
        }

        @Override
        public void choices(java.util.List<BlockPos> targets) {
            if (panel.linkRole.startsWith("reference")) {
                panel.wrappedNote(
                        targets.isEmpty() ? "cc_no_reference_targets" : "cc_pick_reference_target");
                for (var target : targets) {
                    panel.button(
                            panel.doc().blockName(target) + " " + target.toShortString(),
                            () -> panel.pickDeviceLink(target));
                }
            }
        }

        @Override
        public Collection<BlockPos> targets() {
            return panel.linkRole.startsWith("reference")
                    ? computerReferenceTargets
                    : panel.doc().blocks().keySet();
        }

        @Override
        public void resourcesChanged() {
            if (armLinkView != null) armLinkView.resetVisualInstances();
        }

        @Override
        public void close() {
            armLinkView = null;
            computerReferenceTargets = Set.of();
        }
    }
}
