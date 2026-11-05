//? source if >=1.21.10
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.client.render.pip;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;

/**
 * Pure, no-GL geometry helper for the {@link Template} ghost preview drawn by
 * {@link BlueprintPipRenderer}. Decides which faces of a filled template
 * cell are <i>exposed</i> — i.e. whose neighbouring cell in that direction is not also filled — so
 * a solid template collapses to its outer shell rather than rendering a fog of stacked translucent
 * quads with heavy interior overdraw.
 * <p>
 * Kept separate from the renderer (which extends a client-only PiP base and holds GL-bound static
 * state) so the culling logic can be exercised under plain JUnit without a client/GL context —
 * see {@code TemplateGhostGeometryTester}, mirroring the pattern of the pipe-flow geometry testers.
 */
public final class TemplateGhostGeometry {
    private TemplateGhostGeometry() {}

    /**
     * True when {@code (x, y, z)} is in-bounds for {@code size} and its template fill bit is set.
     * Out-of-bounds coordinates return false so the bounding-box exterior faces always render, and
     * a null {@code data} (a fresh, never-populated template) is treated as entirely unfilled.
     */
    static boolean cellFilled(Template template, BlockPos size, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return false;
        }
        return template.data != null && template.data.get(Snapshot.posToIndex(size, x, y, z));
    }

    /**
     * The faces of the filled cell at {@code (x, y, z)} that are exposed — those whose neighbour in
     * that direction is not also a filled cell. Faces shared with a filled neighbour are interior
     * to the shell and omitted, so a fully-enclosed cell yields an empty set.
     */
    public static EnumSet<Direction> visibleFaces(Template template, BlockPos size, int x, int y, int z) {
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        for (Direction face : Direction.values()) {
            if (!cellFilled(template, size,
                    x + face.getStepX(), y + face.getStepY(), z + face.getStepZ())) {
                faces.add(face);
            }
        }
        return faces;
    }
}
