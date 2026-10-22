package buildcraft.lib.item;

import java.util.EnumMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import buildcraft.lib.engine.TileEngineBase_BC8;

public class MultiBlockItem<E extends Enum<E> & StringRepresentable> extends BlockItem{

	protected final E type;
	
	public MultiBlockItem(Block block, Properties p_40566_, E type, @Nullable EnumMap<E, MultiBlockItem<E>> map) {
		super(block, p_40566_);
		this.type = type;
		if(map != null&&type!=null)
			map.put(type, this);
	}

	@Override
	public void fillItemCategory(CreativeModeTab tab, NonNullList<ItemStack> list) {
		if(this.allowedIn(tab)) {
			list.add(new ItemStack(this));}
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		BlockPos placementPos = context.getClickedPos();
		Direction preferredDirection = context.getClickedFace().getOpposite();
		InteractionResult result = super.place(context);

		if (result.consumesAction() && !context.getLevel().isClientSide) {
			BlockEntity blockEntity = context.getLevel().getBlockEntity(placementPos);
			if (blockEntity instanceof TileEngineBase_BC8 engine) {
				engine.preferDirectionOnPlacement(preferredDirection);
			}
		}
		return result;
	}

	@Override
	public Component getName(ItemStack p_41458_) {
		return Component.translatable(getDescriptionId()+"_" + type.getSerializedName());
	}
	
	public E getType() {
		return type;
	}
	
}
