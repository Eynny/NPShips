package com.eynny.npships.client.editor;

import com.eynny.npships.api.client.editor.EditorExtension;
import com.eynny.npships.api.client.editor.EditorSceneFactory;
import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.patches.PatchDevices;
import com.eynny.npships.authoring.patches.SchematicPatches;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Native authored scene modules. Factories contain code only; owners retain detached geometry. */
public final class BuiltinSceneContributions {
    private BuiltinSceneContributions() {}

    public static <T> T capability(String provider, Class<T> contract) {
        return contract == EditorSceneFactory.class && provider.equals("simulated_rope")
                ? contract.cast((EditorSceneFactory) Ropes::new)
                : null;
    }

    private record Rope(
            BlockPos owner,
            BlockPos target,
            dev.simulated_team.simulated.ponder.elements.rope.RopeStrandElement element) {}

    private static final class Ropes implements EditorExtension {
        private SchematicDocument source;
        private List<Rope> ropes = List.of();

        private void refresh(Session session) {
            var document =
                    session.findCapability(NativeEditorDocument.class)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Native authored source view is unavailable"))
                            .document();
            if (source == document) return;
            net.minecraft.nbt.ListTag palette = null;
            var result = new ArrayList<Rope>();
            for (var patch :
                    document.hasConfig()
                            ? SchematicPatches.read(document.configCopy())
                            : document.templatePatches()) {
                if (!patch.get("kind").getAsString().equals("rope")) continue;
                try {
                    PatchDevices.validateLinks(patch, document);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                var owner = SchematicPatches.position(patch.getAsJsonArray("pos"));
                for (var target : PatchDevices.targets(patch)) {
                    if (palette == null) palette = document.paletteCopy();
                    var start = attachment(document, palette, owner);
                    var end = attachment(document, palette, target);
                    double distance = start.distanceTo(end);
                    double length =
                            Math.max(
                                    distance,
                                    patch.has("extension")
                                            ? patch.get("extension").getAsDouble()
                                            : distance);
                    double sag =
                            Math.min(
                                    length * .25,
                                    Math.sqrt(Math.max(0, length * length - distance * distance))
                                            * .5);
                    var element =
                            new dev.simulated_team.simulated.ponder.elements.rope.RopeStrandElement(
                                    start, end, Math.max(.01, length), sag, 0);
                    element.forceApplyFade(1);
                    result.add(new Rope(owner, target, element));
                }
            }
            ropes = List.copyOf(result);
            source = document;
        }

        @Override
        public void opened(Session session) {
            refresh(session);
        }

        @Override
        public void changed(Session session, Change change) {
            if (change == Change.DOCUMENT) refresh(session);
        }

        @Override
        public void resourcesChanged(Session session, long generation) {
            source = null;
            refresh(session);
        }

        @Override
        public boolean hasPreview() {
            return !ropes.isEmpty();
        }

        @Override
        public void preview(Session session, Preview preview) {
            var graphics = preview.graphics();
            for (var rope : ropes)
                if (preview.visible(rope.owner()) && preview.visible(rope.target()))
                    rope.element().renderLast(null, preview.buffers(), graphics, 1);
        }

        @Override
        public void close() {
            ropes = List.of();
            source = null;
        }
    }

    private static Vec3 attachment(
            SchematicDocument document, net.minecraft.nbt.ListTag palette, BlockPos position) {
        var index = document.blocks().get(position);
        var state =
                index == null
                        ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                        : NbtUtils.readBlockState(
                                BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(index));
        if (state.getBlock() instanceof EntityBlock block
                && block.newBlockEntity(position, state)
                        instanceof
                        dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBlockEntity
                                        holder)
            return holder.getVisualAttachmentPoint(position, state);
        return Vec3.atCenterOf(position);
    }
}
