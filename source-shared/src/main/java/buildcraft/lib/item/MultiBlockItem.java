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

import buildcraft.lib.engine.TileEngineBase_BC8;

public class MultiBlockItem<E extends Enum<E> & StringRepresentable> extends BlockItem implements ICreativeTabItemProvider {

	protected final E type;
	
	public MultiBlockItem(Block block, Properties p_40566_, E type, @Nullable EnumMap<E, MultiBlockItem<E>> map) {
		super(block, p_40566_);
		this.type = type;
		if(map != null&&type!=null)
			map.put(type, this);
	}

	@Override
	public void addCreativeTabItems(Consumer<ItemStack> output) {
		output.accept(getDefaultInstance());
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		BlockPos placementPos = context.getClickedPos();
		Direction preferredDirection = context.getClickedFace().getOpposite();
		InteractionResult result = super.place(context);

		// The placement face is the player's strongest intent: when an engine is placed directly onto a
		// compatible receiver, prefer that receiver over the enum-order fallback used by onPlacedBy().
		//? if >=1.21.11 {
		boolean serverSide = !context.getLevel().isClientSide();
		//?} else {
		boolean serverSide = !context.getLevel().isClientSide;
		//?}
		if (result.consumesAction() && serverSide) {
			BlockEntity blockEntity = context.getLevel().getBlockEntity(placementPos);
			if (blockEntity instanceof TileEngineBase_BC8 engine) {
				engine.preferDirectionOnPlacement(preferredDirection);
			}
		}
		return result;
	}


	@Override
	public Component getName(ItemStack p_41458_) {
		//? if >=1.21.11 {
        // The registry ID already includes the variant. Its canonical ITEM_NAME was set by RegistryCompat.
        return super.getName(p_41458_);
        //?} else {
        return Component.translatable(getDescriptionId()+"_" + type.getSerializedName());
        //?}
	}
	
	public E getType() {
		return type;
	}
	
}
