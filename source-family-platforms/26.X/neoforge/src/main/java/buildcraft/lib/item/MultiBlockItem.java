package buildcraft.lib.item;

import java.util.EnumMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import buildcraft.lib.compat.RegistryCompat;

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


	public Component getName(ItemStack p_41458_) {
        // The registry ID already includes the variant. Its canonical ITEM_NAME was set by RegistryCompat.
        return super.getName(p_41458_);
	}
	
	public E getType() {
		return type;
	}
	
}
