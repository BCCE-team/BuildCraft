package buildcraft.factory.block;

import java.util.HashMap;
import java.util.Map;

import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.misc.WrenchUtil;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileFloodGate;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public class BlockFloodGate extends BlockBCTile_Neptune{
    public static final Map<Direction, BooleanProperty> CONNECTED_MAP;

    static {
        CONNECTED_MAP = new HashMap<>(BuildCraftProperties.CONNECTED_MAP);
        CONNECTED_MAP.remove(Direction.UP);
    }
	
	@Override
	public BlockEntity newBlockEntity(BlockPos p_153215_, BlockState p_153216_) {
		return BCFactoryBlocks.ENTITYBLOCKFLOODGATE.get().create(p_153215_, p_153216_);
	}
	

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!WrenchUtil.isWrench(heldItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        Direction side = hit.getDirection();
        if (side == Direction.UP || !CONNECTED_MAP.containsKey(side)) {
            return ItemInteractionResult.SUCCESS;
        }

        if (!world.isClientSide && world.getBlockEntity(pos) instanceof TileFloodGate floodGate) {
            if (!floodGate.openSides.remove(side)) {
                floodGate.openSides.add(side);
            }
            floodGate.onOpenSidesChanged();
            floodGate.sendNetworkUpdate(TileBC_Neptune.NET_RENDER_DATA);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune tileBC) {
            return tileBC.onActivated(player, InteractionHand.MAIN_HAND, hit);
        }
        return InteractionResult.PASS;
    }
	


}
