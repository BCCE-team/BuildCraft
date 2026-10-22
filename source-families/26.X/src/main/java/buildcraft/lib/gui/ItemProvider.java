package buildcraft.lib.gui;

import java.util.function.IntFunction;

import org.jetbrains.annotations.NotNull;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

public class ItemProvider implements IItemHandler{

	protected final IntFunction<ItemStack> provider ;
	protected final int size;
	
	public ItemProvider(IntFunction<ItemStack> provider, int size) {
		this.provider = provider;
		this.size = size;
		
	}
	
	public int getSlots() {
		return size;
	}

	public @NotNull ItemStack getStackInSlot(int slot) {
		ItemStack item = provider.apply(slot);
		return item == null ? ItemStack.EMPTY : item;
	}

	public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		return stack;
	}

	public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
		ItemStack item = provider.apply(slot);
		return item == null ? ItemStack.EMPTY : item;
	}

	public int getSlotLimit(int slot) {
		return 0;
	}

	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return false;
	}

}
