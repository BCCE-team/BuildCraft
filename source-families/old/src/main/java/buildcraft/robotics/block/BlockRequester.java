/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.block;

import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.block.IBlockWithFacing;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.robotics.tile.TileRequester;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
//? if <1.20 {
import net.minecraft.world.level.material.Material;
//?} else {
/*?
import net.minecraft.world.level.material.MapColor;
?*/
//?}

public class BlockRequester extends BlockBCTile_Neptune implements IBlockWithFacing {
    public BlockRequester() {
        //? if <1.20 {
        super(BlockBehaviour.Properties.of(Material.METAL)
        //?} else {
        /*?
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
        ?*/
        //?}
                .strength(5.0F, 10.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public TileBC_Neptune newBlockEntity(BlockPos pos, BlockState state) {
        return new TileRequester(pos, state);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof TileRequester requester) {
            return requester.getComparatorSignal();
        }
        return 0;
    }
}
