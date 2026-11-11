package com.eynny.npships.client.editor;

import com.eynny.npships.api.patches.*;
import com.eynny.npships.authoring.patches.PatchCode;
import com.eynny.npships.config.NPShipsRuntimeConfig;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import java.util.*;

/** One bounded active link operation in the existing editor, not a registry or input manager. */
final class PatchLinks implements AutoCloseable {
    private final EditorToolsPanel panel;
    private final PatchPolicy policy = NPShipsRuntimeConfig.patchPolicy();
    private PatchLinkContribution current;
    private PatchLinkContribution.Factory factory;
    private String role;

    PatchLinks(EditorToolsPanel panel) {
        this.panel = panel;
    }

    void incoming(JsonObject patch) {
        for (var entry :
                PatchCode.applicable(
                        PatchLinkContribution.Factory.class,
                        code -> code.incomingApplies(patch),
                        PatchStage.AUTHOR_PREPARATION,
                        policy)) entry.getValue().incoming(panel, patch, panel.pos());
    }

    void begin(JsonObject patch, String nextRole) {
        var code =
                PatchCode.applicable(
                        PatchLinkContribution.Factory.class,
                        entry -> entry.roles().contains(nextRole),
                        PatchStage.AUTHOR_PREPARATION,
                        policy);
        if (code.size() != 1)
            throw new IllegalArgumentException(
                    code.isEmpty()
                            ? "Link operation unavailable: " + nextRole
                            : "Conflicting link providers: " + nextRole);
        var nextFactory = code.getFirst().getValue();
        var next = Objects.requireNonNull(nextFactory.open(panel));
        var previous = current;
        var previousFactory = factory;
        var previousRole = role;
        var sourceRole = panel.linkRole;
        var sourcePatch = panel.linkPatch;
        var sourceFirst = panel.firstLink;
        current = next;
        factory = nextFactory;
        role = nextRole;
        try {
            next.begin(patch, nextRole);
        } catch (RuntimeException | LinkageError failure) {
            current = previous;
            factory = previousFactory;
            role = previousRole;
            panel.linkRole = sourceRole;
            panel.linkPatch = sourcePatch;
            panel.firstLink = sourceFirst;
            next.close();
            throw failure;
        }
        if (previous != null) previous.close();
    }

    private boolean active() {
        return current != null && Objects.equals(role, panel.linkRole);
    }

    Collection<BlockPos> targets() {
        return active() ? current.targets() : List.of();
    }

    String section() {
        return active() ? factory.section(role) : "devices";
    }

    void choices(List<BlockPos> targets) {
        if (active()) current.choices(targets);
    }

    boolean compatible(BlockPos target) {
        return active() && current.compatible(target);
    }

    boolean pick(BlockPos target) {
        if (!active()) return false;
        try {
            return current.pick(target);
        } finally {
            if (!Objects.equals(role, panel.linkRole)) close();
        }
    }

    void resourcesChanged() {
        if (active()) current.resourcesChanged();
    }

    @Override
    public void close() {
        var previous = current;
        current = null;
        factory = null;
        role = null;
        if (previous != null) previous.close();
    }
}
