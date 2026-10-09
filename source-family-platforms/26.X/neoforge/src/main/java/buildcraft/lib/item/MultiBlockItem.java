package buildcraft.lib.item;

import java.util.EnumMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.compat.RegistryCompat;
import buildcraft.lib.engine.TileEngineBase_BC8;

public class MultiBlockItem<E extends Enum<E> & StringRepresentable> extends BlockItem implements ICreativeTabItemProvider {

	protected final E type;
	
	public MultiBlockItem(Block block, Properties p_40566_, E type, @Nullable EnumMap<E, MultiBlockItem<E>> map) {
		super(block, p_40566_);
		this.type = type;
		if(map != null&&type!=null)
			map.put(type, this);
	}

	public void addCreativeTabItems(Consumer<ItemStack> output) {
		output.accept(getDefaultInstance());
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		BlockPos placementPos = context.getClickedPos();
		Direction preferredDirection = context.getClickedFace().getOpposite();
		InteractionResult result = super.place(context);

		// The clicked face identifies the receiver the player intentionally placed the engine against.
		// Apply it after vanilla placement, on the authoritative server, and fall back to the normal scan
		// when that side is not a valid receiver.
		if (result.consumesAction() && !context.getLevel().isClientSide()) {
			BlockEntity blockEntity = context.getLevel().getBlockEntity(placementPos);
			if (blockEntity instanceof TileEngineBase_BC8 engine) {
				engine.preferDirectionOnPlacement(preferredDirection);
			}
		}
		return result;
	}


	public Component getName(ItemStack p_41458_) {
        // The registry ID already includes the variant. Its canonical ITEM_NAME was set by RegistryCompat.
        return super.getName(p_41458_);
	}
	
	public E getType() {
		return type;
	}
	
}
