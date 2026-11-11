package com.eynny.npships.authoring.geometry;

import com.eynny.npships.authoring.document.SchematicDocument;
import com.eynny.npships.authoring.document.SchematicReframe;
import com.eynny.npships.authoring.document.SchematicRegions;
import com.eynny.npships.authoring.document.SchematicTurn;

import net.minecraft.world.level.block.state.BlockState;

/** A generated figure is rebuilt against its original document, never against its old shell. */
public final class SchematicShape {
    private final SchematicDocument background;
    private final SchematicDocument selection;
    private final SchematicRegions.Region selected;
    private final SchematicRegions.Contents contents;
    private volatile SchematicDocument cleared;

    public SchematicShape(SchematicDocument background) {
        this(background, null, null, null);
    }

    private SchematicShape(
            SchematicDocument background,
            SchematicDocument selection,
            SchematicRegions.Region selected,
            SchematicRegions.Contents contents) {
        this.background = background;
        this.selection = selection;
        this.selected = selected;
        this.contents = contents;
    }

    public static SchematicShape capture(
            SchematicDocument source,
            SchematicRegions.Region region,
            SchematicRegions.Contents contents) {
        return new SchematicShape(source, source, region, contents);
    }

    private SchematicDocument background() {
        if (selection == null) return background;
        var result = cleared;
        if (result == null) {
            result = SchematicRegions.delete(selection, selected, contents);
            cleared = result;
        }
        return result;
    }

    public SchematicDocument renderContents(SchematicRegions.Region region) {
        return selection == null
                ? background
                : SchematicRegions.moveSelection(selection, selected, region, contents);
    }

    public SchematicShape reframe(
            net.minecraft.core.BlockPos origin, net.minecraft.core.BlockPos size) {
        if (selection == null)
            return new SchematicShape(SchematicReframe.whole(background, origin, size).result());
        // Capture the currently retained part after a whole-document resize.
        var all = SchematicReframe.whole(selection, origin, size).result();
        var p = selected.origin().subtract(origin);
        var lo =
                new net.minecraft.core.BlockPos(
                        Math.max(0, p.getX()), Math.max(0, p.getY()), Math.max(0, p.getZ()));
        var hi =
                new net.minecraft.core.BlockPos(
                        Math.min(size.getX(), p.getX() + selected.size().getX()),
                        Math.min(size.getY(), p.getY() + selected.size().getY()),
                        Math.min(size.getZ(), p.getZ() + selected.size().getZ()));
        if (hi.getX() <= lo.getX() || hi.getY() <= lo.getY() || hi.getZ() <= lo.getZ())
            return new SchematicShape(all);
        return capture(all, new SchematicRegions.Region(lo, hi.subtract(lo)), contents);
    }

    public SchematicShape turn(boolean right, net.minecraft.world.level.Level level) {
        return selection == null
                ? new SchematicShape(SchematicTurn.turn(background, right, level))
                : capture(
                        SchematicTurn.turn(selection, right, level),
                        SchematicTurn.region(selected, selection.size(), right),
                        contents);
    }

    public SchematicDocument render(
            SchematicRegions.Region region,
            SchematicRegions.Shape shape,
            boolean hollow,
            BlockState state) {
        return render(region, shape, hollow, state, false);
    }

    public SchematicDocument render(
            SchematicRegions.Region region,
            SchematicRegions.Shape shape,
            boolean hollow,
            BlockState state,
            int turns) {
        return SchematicRegions.fill(background(), region, shape, hollow, state, turns);
    }

    public SchematicDocument render(
            SchematicRegions.Region region,
            SchematicRegions.Shape shape,
            boolean hollow,
            BlockState state,
            int turns,
            ShapeDeformation deformation) {
        if (deformation.identity()) return render(region, shape, hollow, state, turns);
        var size = region.size();
        int rotation = Math.floorMod(turns, 4);
        var modelSize =
                rotation % 2 == 0
                        ? size
                        : new net.minecraft.core.BlockPos(size.getZ(), size.getY(), size.getX());
        var mask = deformation.raster(shape, modelSize);
        var transformed = new java.util.BitSet(size.getX() * size.getY() * size.getZ());
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            int a = i % modelSize.getX(),
                    c = i / modelSize.getX() % modelSize.getZ(),
                    y = i / (modelSize.getX() * modelSize.getZ());
            int x =
                    rotation == 0
                            ? a
                            : rotation == 1
                                    ? size.getX() - 1 - c
                                    : rotation == 2 ? size.getX() - 1 - a : c;
            int z =
                    rotation == 0
                            ? c
                            : rotation == 1
                                    ? a
                                    : rotation == 2 ? size.getZ() - 1 - c : size.getZ() - 1 - a;
            transformed.set((y * size.getZ() + z) * size.getX() + x);
        }
        return SchematicRegions.fillMask(background(), region, transformed, hollow, state);
    }

    public SchematicDocument render(
            SchematicRegions.Region region,
            SchematicRegions.Shape shape,
            boolean hollow,
            BlockState state,
            boolean farPlane) {
        if (farPlane) {
            var o = region.origin();
            var size = region.size();
            if (shape == SchematicRegions.Shape.PLANE_XY)
                region =
                        new SchematicRegions.Region(
                                o.offset(0, 0, size.getZ() - 1),
                                new net.minecraft.core.BlockPos(size.getX(), size.getY(), 1));
            else if (shape == SchematicRegions.Shape.PLANE_YZ)
                region =
                        new SchematicRegions.Region(
                                o.offset(size.getX() - 1, 0, 0),
                                new net.minecraft.core.BlockPos(1, size.getY(), size.getZ()));
        }
        return SchematicRegions.fill(background(), region, shape, hollow, state);
    }
}
