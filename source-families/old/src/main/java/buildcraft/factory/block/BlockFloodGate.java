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
	public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (WrenchUtil.isWrench(heldItem)) {
            if (!world.isClientSide) {
            	Direction side = hit.getDirection();
                if (side != Direction.UP) {
                    BlockEntity tile = world.getBlockEntity(pos);
                    if (tile instanceof TileFloodGate) {
                        if (CONNECTED_MAP.containsKey(side)) {
                            TileFloodGate floodGate = (TileFloodGate) tile;
                            if (!floodGate.openSides.remove(side)) {
                                floodGate.openSides.add(side);
                            }
                            floodGate.onOpenSidesChanged();
                            floodGate.sendNetworkUpdate(TileBC_Neptune.NET_RENDER_DATA);
                            return InteractionResult.SUCCESS;
                        }
                    }
                }
            }
            // A wrench interaction belongs to the Flood Gate even when the
            // clicked face cannot be toggled. This matches the modern backend
            // and prevents the wrench from receiving a second useOn call.
            return InteractionResult.SUCCESS;
        }
        return super.use(state, world, pos, player, hand, hit);
	}
	


}
