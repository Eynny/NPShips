package com.eynny.npships.integration.computercraft;

import com.eynny.npships.authoring.document.SchematicDocument;

import net.minecraft.core.*;

public final class ComputerSides {
    private ComputerSides() {}

    public static BlockPos target(SchematicDocument doc, BlockPos owner, String side) {
        String facing = doc.blockProperty(owner, "facing");
        // Native CC computer/turtle palettes may omit Properties; both native defaults face north.
        // Do not turn an explicitly invalid direction into a guessed valid one.
        if (facing.isEmpty()
                && (doc.blockName(owner).startsWith("computercraft:computer_")
                        || doc.blockName(owner).startsWith("computercraft:turtle_")))
            facing = "north";
        Direction front = Direction.byName(facing);
        if (front == null || front.getAxis().isVertical()) return null;
        // Stationary CC computers mirror left/right when viewed from the screen;
        // turtles retain DirectionUtil's body-relative sides.
        boolean screenFacing = doc.blockName(owner).startsWith("computercraft:computer_");
        Direction direction =
                switch (side) {
                    case "top" -> Direction.UP;
                    case "bottom" -> Direction.DOWN;
                    case "front" -> front;
                    case "back" -> front.getOpposite();
                    case "right" ->
                            screenFacing ? front.getCounterClockWise() : front.getClockWise();
                    case "left" ->
                            screenFacing ? front.getClockWise() : front.getCounterClockWise();
                    default -> null;
                };
        return direction == null ? null : owner.relative(direction);
    }

    public static String name(SchematicDocument doc, BlockPos owner, BlockPos target) {
        if (owner.distManhattan(target) != 1) return null;
        for (String side : java.util.List.of("top", "bottom", "front", "back", "left", "right"))
            if (target.equals(target(doc, owner, side))) return side;
        return null;
    }
}
