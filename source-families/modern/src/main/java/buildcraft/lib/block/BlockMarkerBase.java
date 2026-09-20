//? source if >=1.21.11
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib.block;

import java.util.EnumMap;
import java.util.Map;

import buildcraft.lib.internal.block.ICustomRotationHandler;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.tile.TileMarker;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import buildcraft.lib.compat.RenderCompat;

public abstract class BlockMarkerBase extends BlockBCTile_Neptune implements ICustomRotationHandler, IBlockWithFacing{
    private static final Map<Direction, VoxelShape> BOUNDING_BOXES = new EnumMap<>(Direction.class);
    private static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    private static final BooleanProperty ACTIVE = BuildCraftProperties.ACTIVE;
    static {
        double halfWidth = 0.1;
        double h = 0.65;
        // Little variables to make reading a *bit* more sane
        final double nw = 0.5 - halfWidth;
        final double pw = 0.5 + halfWidth;
        final double ih = 1 - h;
        BOUNDING_BOXES.put(Direction.DOWN, Shapes.box(nw, ih, nw, pw, 1, pw));
        BOUNDING_BOXES.put(Direction.UP, Shapes.box(nw, 0, nw, pw, h, pw));
        BOUNDING_BOXES.put(Direction.SOUTH, Shapes.box(nw, nw, 0, pw, pw, h));
        BOUNDING_BOXES.put(Direction.NORTH, Shapes.box(nw, nw, ih, pw, pw, 1));
        BOUNDING_BOXES.put(Direction.EAST, Shapes.box(0, nw, nw, h, pw, pw));
        BOUNDING_BOXES.put(Direction.WEST, Shapes.box(ih, nw, nw, 1, pw, pw));
    }

    public BlockMarkerBase(Properties material) {
        super(material.strength(0.25f).sound(SoundType.WOOD));
        this.registerDefaultState(this.stateDefinition.any()
//                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false));
    }

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> bs) {
//        bs.add(FACING);
        bs.add(ACTIVE);
        super.createBlockStateDefinition(bs);
    }


    public RenderType getBlockLayer() {
        return RenderCompat.solid();
    }

    public boolean isCollisionShapeFullBlock(BlockState p_181242_, BlockGetter p_181243_, BlockPos p_181244_) {
        return false;
    }
    public VoxelShape getCollisionShape(BlockState p_60572_, BlockGetter p_60573_, BlockPos p_60574_,
            CollisionContext p_60575_) {
        return Shapes.empty();
    }
    public boolean isOcclusionShapeFullBlock(BlockState p_222959_, BlockGetter p_222960_, BlockPos p_222961_) {
        return false;
    }



    @SuppressWarnings("deprecation")
    public VoxelShape getOcclusionShape(BlockState p_60578_, BlockGetter p_60579_, BlockPos p_60580_) {
        return super.getOcclusionShape(p_60578_);
    }
    public VoxelShape getShape(BlockState state, BlockGetter p_60556_, BlockPos p_60557_,
            CollisionContext p_60558_) {
        return BOUNDING_BOXES.get(state.getValue(FACING));
    }
    public VoxelShape getVisualShape(BlockState state, BlockGetter p_60480_, BlockPos p_60481_,
            CollisionContext p_60482_) {
        return BOUNDING_BOXES.get(state.getValue(FACING));
    }


    public VoxelShape getInteractionShape(BlockState p_60547_, BlockGetter p_60548_, BlockPos p_60549_) {
        return super.getInteractionShape(p_60547_, p_60548_, p_60549_);
    }

    public BlockState getStateForPlacement(BlockPlaceContext bpc) {
        return super.getStateForPlacement(bpc).setValue(FACING, bpc.getClickedFace());
    }




    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickAccess,
        BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, tickAccess, pos, direction, neighborPos, neighborState, random);
    }



    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction d = state.getValue(FACING);
        return super.canSupportCenter(level, pos.offset(d.getOpposite().getUnitVec3i()), d);
    }


    public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        if (state.getBlock() != this) {
            return;
        }
        Direction sideOn = state.getValue(FACING);
        if (!canSupportCenter(level, pos, sideOn)) {
//            level.s(pos, true);
        }

    }



    public boolean canFaceVertically() {
        return true;
    }

    public InteractionResult attemptRotation(Level world, BlockPos pos, BlockState state, Direction sideWrenched) {
        if (state.getBlock() instanceof BlockMarkerBase) {// Just check to make sure we have the right block...
            return VanillaRotationHandlers.rotateDirection(world, pos, state, FACING, VanillaRotationHandlers.ROTATE_FACING);
        } else {
            return InteractionResult.PASS;
        }
    }
}
