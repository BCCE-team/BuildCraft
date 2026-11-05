/*
 * Copyright (c) 2026 the BuildCraftUnofficial contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.filler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import buildcraft.lib.misc.BlockUtil;

import buildcraft.builders.snapshot.Template;

/**
 * Which cells a Filler's pattern still has to fill, for the preview its block entity renderer draws. Common-side on
 * purpose (positions only, nothing to render), so the cell computation is unit-testable; the drawing is
 * {@code FillPreviewRenderer} and the keeping between frames is {@link FillPreviewCache}.
 */
public final class FillPreview {
    private FillPreview() {}

    /** Receives each set bit of a template: its index in the template's data and the cell's world position. */
    @FunctionalInterface
    public interface CellVisitor {
        void visit(int index, BlockPos pos);
    }

    /**
     * A walk over a template's set bits that can be stopped and resumed, so a big box is spread over many frames
     * instead of read in one. It goes over the template's set bits rather than every cell of the box, so a hollow
     * pattern over a big box costs what the pattern holds.
     */
    public static final class Walk {
        private final Template.BuildingInfo info;
        private int next;

        public Walk(Template.BuildingInfo info) {
            this.info = info;
        }

        /** The building info this walk goes over. */
        public Template.BuildingInfo info() {
            return info;
        }

        /**
         * Visits at most {@code budget} (at least one) more set bits.
         *
         * @return true once every set bit has been visited
         */
        public boolean advance(int budget, CellVisitor visitor) {
            Template template = info.getSnapshot();
            BitSet data = template.data;
            int left = Math.max(1, budget);
            for (int i = data.nextSetBit(next); i >= 0; i = data.nextSetBit(i + 1)) {
                if (left-- == 0) {
                    next = i;
                    return false;
                }
                visitor.visit(i, info.toWorld(template.indexToPos(i)));
            }
            next = data.length();
            return true;
        }
    }

    /** Every cell the template marks, as a world position, that {@code wanted} accepts, in one go. */
    public static List<BlockPos> collect(Template.BuildingInfo info, Predicate<BlockPos> wanted) {
        List<BlockPos> cells = new ArrayList<>();
        new Walk(info).advance(Integer.MAX_VALUE, (index, pos) -> {
            if (wanted.test(pos)) {
                cells.add(pos);
            }
        });
        return cells;
    }

    /**
     * Whether the Filler could place a block into {@code pos} right now: the same test the Filler's own placement uses
     * (air or replaceable material), so replaceable grass still shows and a cell already built
     * does not. A cell in a chunk this client has not loaded is not fillable, or all of them would read as empty air.
     */
    public static boolean isFillable(Level level, BlockPos pos) {
        return level != null && level.hasChunkAt(pos)
            && (level.isEmptyBlock(pos) || BlockUtil.isReplaceable(level, pos));
    }
}
