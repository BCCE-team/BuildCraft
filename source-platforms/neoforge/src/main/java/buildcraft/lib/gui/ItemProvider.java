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
	
//? if mc_26_x {
//? } else {
	@Override
//?}
	public int getSlots() {
		return size;
	}

//? if mc_26_x {
//? } else {
	@Override
//?}
	public @NotNull ItemStack getStackInSlot(int slot) {
		ItemStack item = provider.apply(slot);
		return item == null ? ItemStack.EMPTY : item.copy();
	}

//? if mc_26_x {
//? } else {
	@Override
//?}
	public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		return stack;
	}

//? if mc_26_x {
//? } else {
	@Override
//?}
	public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
		// Display-only provider: extraction must never manufacture an item.
		return ItemStack.EMPTY;
	}

//? if mc_26_x {
//? } else {
	@Override
//?}
	public int getSlotLimit(int slot) {
		return 0;
	}

//? if mc_26_x {
//? } else {
	@Override
//?}
	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return false;
	}

}
