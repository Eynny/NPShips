package com.eynny.npships.authoring.patches;

import com.eynny.npships.api.patches.PatchStage;
import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/** Generic bridge to the applicable registered native fluid editor; never owns history. */
public final class SchematicFluids {
    private final PatchFluidEditor.Session session;
    private final BlockPos selected;

    public SchematicFluids(SchematicDocument doc, BlockPos selected, Level context) {
        this.selected = selected;
        boolean[] loaded = {false};
        net.minecraft.world.level.block.entity.BlockEntity[] target = {null};
        java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntity>
                selectedTarget =
                        () -> {
                            if (!loaded[0]) {
                                target[0] = SchematicResources.detached(doc, selected, context);
                                loaded[0] = true;
                            }
                            return target[0];
                        };
        var code =
                PatchCode.applicable(
                        PatchFluidEditor.class,
                        provider ->
                                provider.applies(
                                        doc, selected, context.registryAccess(), selectedTarget),
                        PatchStage.AUTHOR_PREPARATION);
        if (code.size() > 1)
            throw new IllegalArgumentException("Competing fluid editors at " + selected);
        session =
                code.isEmpty()
                        ? null
                        : code.getFirst().getValue().open(doc, selected, context, selectedTarget);
    }

    private PatchFluidEditor.Session required() {
        if (session == null)
            throw new IllegalArgumentException("Native fluid editor is unavailable at " + selected);
        return session;
    }

    public int size() {
        return session == null ? 0 : session.size();
    }

    public BlockPos owner() {
        return session == null ? selected : session.owner();
    }

    public String label(int slot) {
        return required().label(slot);
    }

    public FluidStack get(int slot) {
        return required().get(slot);
    }

    public int capacity(int slot) {
        return required().capacity(slot);
    }

    public boolean accepts(int slot, FluidStack stack) {
        return required().accepts(slot, stack);
    }

    public boolean creative() {
        return session != null && session.creative();
    }

    public SchematicDocument put(int slot, FluidStack stack) {
        return required().put(slot, stack);
    }
}
