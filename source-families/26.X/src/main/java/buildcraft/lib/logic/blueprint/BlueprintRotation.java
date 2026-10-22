/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.blueprint;

/** Pure quarter-turn coordinate transforms used by blueprint/entity placement. */
public final class BlueprintRotation {
    private BlueprintRotation() {}

    public record Point(double x, double y, double z) {}

    /** Rotates a point inside a unit X/Z square clockwise by {@code quarterTurns}. */
    public static Point rotateUnit(Point point, int quarterTurns) {
        if (point == null) throw new NullPointerException("point");
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> point;
            case 1 -> new Point(1.0D - point.z(), point.y(), point.x());
            case 2 -> new Point(1.0D - point.x(), point.y(), 1.0D - point.z());
            case 3 -> new Point(point.z(), point.y(), 1.0D - point.x());
            default -> throw new AssertionError();
        };
    }

    public static int invertQuarterTurns(int quarterTurns) {
        return Math.floorMod(-quarterTurns, 4);
    }
}
