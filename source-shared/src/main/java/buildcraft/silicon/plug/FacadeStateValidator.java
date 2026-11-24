/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.silicon.plug;

import buildcraft.lib.world.SingleBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
//? if >=1.21.5 {
import net.minecraft.world.level.block.VegetationBlock;
//?} else {
import net.minecraft.world.level.block.BushBlock;
//?}
//? if <1.21 {
import net.minecraft.world.level.block.GlassBlock;
//?}
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Common, server-safe facade geometry policy. Never consult client models during registry discovery. */
final class FacadeStateValidator {
    private FacadeStateValidator() {}

    /** Reject whole block families before enumerating their potentially very large state spaces. */
    static String blockProblem(Block block) {
        // Plants can report a full selection shape even when their model is a cross or has no side quads.
        //? if >=1.21.5 {
        if (block instanceof VegetationBlock) {
        //?} else {
        if (block instanceof BushBlock) {
        //?}
            return "it is a vegetation block";
        }
        int directions = 0;
        int nonBooleanDirections = 0;
        boolean straight = false;
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            switch (property.getName()) {
                case "north", "south", "east", "west", "up", "down" -> {
                    directions++;
                    if (property.getValueClass() != Boolean.class) {
                        nonBooleanDirections++;
                    }
                }
                case "half" -> {
                    // Covers custom two-block plants/decorations even if they do not extend vanilla vegetation.
                    if (property.getValue("upper").isPresent() && property.getValue("lower").isPresent()) {
                        return "it is part of a two-block structure";
                    }
                }
                case "straight" -> straight = true;
                default -> { }
            }
        }
        // Connection-dependent pipe/cable geometry (e.g. Oritech) may expose a full outline in some
        // states. Do not confuse six boolean face-texture switches on mushroom blocks with connections.
        if ((straight && directions >= 2) || nonBooleanDirections >= 2) {
            return "it has connection-dependent geometry";
        }
        return null;
    }

    /** A null result means the state is suitable for the facade baker's six full-face projections. */
    static String geometryProblem(BlockState state, String blockPath) {
        SingleBlockAccess access = new SingleBlockAccess(state);
        BlockPos pos = BlockPos.ZERO;
        if (!Block.isShapeFullBlock(state.getShape(access, pos))) {
            return "its outline is not a full cube";
        }
        if (!Block.isShapeFullBlock(state.getCollisionShape(access, pos))) {
            return "its collision shape is not a full cube";
        }
        // A full outline/collision is not enough: decorative blocks can still have no actual cube faces.
        // Keep intentional full-block glass and foliage facades. This exception never bypasses geometry.
        if (isSupportedNonOccluding(state, blockPath)) {
            return null;
        }
        //? if >=1.21.11 {
        if (!state.isSolidRender()) {
        //?} else {
        if (!state.isSolidRender(access, pos)) {
        //?}
            return "it is not a solid-rendering full block";
        }
        for (Direction direction : Direction.values()) {
            if (!state.isFaceSturdy(access, pos, direction)) {
                return "it has a non-solid face: " + direction;
            }
        }
        return null;
    }

    private static boolean isSupportedNonOccluding(BlockState state, String path) {
        Block block = state.getBlock();
        // GlassBlock exists only in our legacy Forge targets. Modern targets use the
        // explicit glass checks below, without admitting every TransparentBlock (e.g. grates).
        //? if <1.21 {
        if (block instanceof GlassBlock) {
            return true;
        }
        //?}
        return block == Blocks.GLASS || block == Blocks.TINTED_GLASS || block instanceof StainedGlassBlock
            || block instanceof LeavesBlock || state.is(BlockTags.LEAVES)
            // Matches the full-block glass names already recognized by the facade renderer, not panes.
            || path.equals("glass") || path.endsWith("_stained_glass");
    }
}
