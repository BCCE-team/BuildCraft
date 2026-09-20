//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.tile.item;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.inventory.AbstractInvItemTransactor;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.tile.item.StackInsertionFunction.InsertionResult;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.compat.neoforge121111.common.util.INBTSerializable;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import buildcraft.lib.compat.NbtCompat;

public class ItemHandlerSimple extends AbstractInvItemTransactor
    implements IItemHandlerModifiable, IItemHandlerAdv, INBTSerializable<CompoundTag> {
    // Function-called stuff (helpers etc)
    private StackInsertionChecker checker;
    private StackInsertionFunction inserter;

    @Nullable
    private StackChangeCallback callback;


    // Actual item stacks used
    public final NonNullList<ItemStack> stacks;

    // Transactor speedup (small)
    private int firstUsed = Integer.MAX_VALUE;

    private final buildcraft.lib.compat.transfer.TransferJournal<TransferState> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(this::captureTransferState, this::restoreTransferState,
            this::commitTransferState);

    private record TransferState(java.util.List<ItemStack> stacks, int firstUsed) {}

    private TransferState captureTransferState() {
        return new TransferState(stacks.stream().map(ItemStack::copy).toList(), firstUsed);
    }

    private void restoreTransferState(TransferState state) {
        for (int i = 0; i < stacks.size(); i++) stacks.set(i, state.stacks().get(i).copy());
        firstUsed = state.firstUsed();
    }

    private void commitTransferState(TransferState state) {
        if (callback == null) return;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack before = state.stacks().get(i);
            if (!ItemStack.matches(before, stacks.get(i))) {
                callback.onStackChange(this, i, before, stacks.get(i));
            }
        }
    }

    public ItemHandlerSimple(int size) {
        this(size, (slot, stack) -> true, StackInsertionFunction.getDefaultInserter(), null);
    }

    public ItemHandlerSimple(int size, int maxStackSize) {
        this(size);
        setLimitedInsertor(maxStackSize);
    }

    public ItemHandlerSimple(int size, @Nullable StackChangeCallback callback) {
        this(size, (slot, stack) -> true, StackInsertionFunction.getDefaultInserter(), callback);
    }

    public ItemHandlerSimple(int size, StackInsertionChecker checker, StackInsertionFunction insertionFunction,
        @Nullable StackChangeCallback callback) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
        this.checker = checker;
        this.inserter = insertionFunction;
        this.callback = callback;
    }

    public ItemHandlerSimple setChecker(StackInsertionChecker checker) {
        this.checker = checker;
        return this;
    }

    public ItemHandlerSimple setInsertor(StackInsertionFunction insertor) {
        this.inserter = insertor;
        return this;
    }


    public ItemHandlerSimple setLimitedInsertor(int maxStackSize) {
        setInsertor(StackInsertionFunction.getInsertionFunction(maxStackSize));
        return this;
    }

    public void setCallback(StackChangeCallback callback) {
        this.callback = callback;
    }

    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            list.add(ItemStackUtil.saveOptional(stack, registries));
        }
        nbt.put("items", list);
        return nbt;
    }

    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        ListTag list = NbtCompat.getList(nbt, "items");
        for (int i = 0; i < list.size() && i < getSlots(); i++) {
            setStackInternal(i, ItemStackUtil.parseOptional(registries, NbtCompat.getCompound(list, i)));
        }
        for (int i = list.size(); i < getSlots(); i++) {
            setStackInternal(i, ItemStack.EMPTY);
        }
    }

    public int getSlots() {
        return stacks.size();
    }

    private boolean badSlotIndex(int slot) {
        return slot < 0 || slot >= stacks.size();
    }

    protected boolean isEmpty(int slot) {
        if (badSlotIndex(slot)) return true;
        return stacks.get(slot).isEmpty();
    }

    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        if (badSlotIndex(slot)) return ItemStack.EMPTY;
        return asValid(stacks.get(slot));
    }

    @Nonnull
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (badSlotIndex(slot)) {
            return stack;
        }
        if (canSet(slot, stack)) {
            ItemStack current = stacks.get(slot);
            if (!canSet(slot, current)) {
                // A bit odd, but can happen if the filter changed
                return stack;
            }
            InsertionResult result = inserter.modifyForInsertion(slot, asValid(current.copy()), asValid(stack.copy()));
            if (!canSet(slot, result.toSet)) {
                // We have a bad inserter or checker, as they should not be conflicting
                CrashReport report = new CrashReport("Inserting an item (buildcraft:ItemHandlerSimple)",
                    new IllegalStateException("Conflicting Insertion!"));
                CrashReportCategory cat = report.addCategory("Inventory details");
                cat.setDetail("Existing Item", current);
                cat.setDetail("Inserting Item", stack);
                cat.setDetail("To Set", result.toSet);
                cat.setDetail("To Return", result.toReturn);
                cat.setDetail("Slot", slot);
                cat.setDetail("Checker", checker.getClass());
                cat.setDetail("Inserter", inserter.getClass());
                throw new ReportedException(report);
            } else if (!simulate) {
                setStackInternal(slot, result.toSet);
                if (callback != null && !buildcraft.lib.compat.transfer.TransferJournal.active()) {
                    callback.onStackChange(this, slot, current, result.toSet);
                }
            }
            return asValid(result.toReturn);
        } else {
            return stack;
        }
    }

    @Nonnull
    protected ItemStack insert(int slot, @Nonnull ItemStack stack, boolean simulate) {
        return insertItem(slot, stack, simulate);
    }

    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (badSlotIndex(slot)) return StackUtil.EMPTY;
        // You can ALWAYS extract. if you couldn't then you could never take out items from anywhere
        ItemStack current = stacks.get(slot);
        if (current.isEmpty()) return StackUtil.EMPTY;
        if (current.getCount() < amount) {
            if (simulate) {
                return asValid(current.copy());
            }
            setStackInternal(slot, StackUtil.EMPTY);
            if (callback != null && !buildcraft.lib.compat.transfer.TransferJournal.active()) {
                callback.onStackChange(this, slot, current, StackUtil.EMPTY);
            }
            // no need to copy as we no longer have it
            return current;
        } else {
            ItemStack before = current;
            current = current.copy();
            ItemStack split = current.split(amount);
            if (!simulate) {
                if (current.getCount() <= 0) current = StackUtil.EMPTY;
                setStackInternal(slot, current);
                if (callback != null && !buildcraft.lib.compat.transfer.TransferJournal.active()) {
                    callback.onStackChange(this, slot, before, current);
                }
            }
            return split;
        }
    }

    @Nonnull
    protected ItemStack extract(int slot, IStackFilter filter, int min, int max, boolean simulate) {
        if (badSlotIndex(slot)) return StackUtil.EMPTY;
        if (min <= 0) min = 1;
        if (max < min) return StackUtil.EMPTY;
        ItemStack current = stacks.get(slot);
        ItemStack before = current.copy();
        if (current.getCount() < min) return StackUtil.EMPTY;
        if (filter.matches(asValid(current))) {
            if (simulate) {
                ItemStack copy = current.copy();
                return copy.split(max);
            }
            transferJournal.record();
            ItemStack split = current.split(max);
            if (current.getCount() <= 0) {
                stacks.set(slot, StackUtil.EMPTY);
            }
            if (callback != null && !buildcraft.lib.compat.transfer.TransferJournal.active()) {
                callback.onStackChange(this, slot, before, stacks.get(slot));
            }
            return split;
        }
        return StackUtil.EMPTY;
    }

    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (badSlotIndex(slot)) {
            // Its safe to throw here
            throw new IndexOutOfBoundsException("Slot index out of range: " + slot);
        }
        ItemStack before = stacks.get(slot);
        setStackInternal(slot, stack);
        if (callback != null && !buildcraft.lib.compat.transfer.TransferJournal.active()) {
            callback.onStackChange(this, slot, before, asValid(stack));
        }
    }

    public final boolean canSet(int slot, @Nonnull ItemStack stack) {
        ItemStack copied = asValid(stack);
        if (copied.isEmpty()) {
            return true;
        }
        return checker.canSet(slot, copied);
    }

    private void setStackInternal(int slot, @Nonnull ItemStack stack) {
        transferJournal.record();
        stacks.set(slot, asValid(stack));
        // Transactor calc
        if (stack.isEmpty() && firstUsed == slot) {
            for (int s = firstUsed; s < getSlots(); s++) {
                if (!stacks.get(s).isEmpty()) {
                    firstUsed = s;
                    break;
                }
            }
            if (firstUsed == slot) {
                firstUsed = Integer.MAX_VALUE;
            }
        } else if (!stack.isEmpty() && firstUsed > slot) {
            firstUsed = slot;
        }
    }

    public int getSlotLimit(int slot) {
        return 64;
    }

    public String toString() {
        return "ItemHandlerSimple " + stacks;
    }

    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return this.checker.canSet(slot, stack);
    }

    public boolean isEmpty() {
        for(ItemStack item : stacks)
            if(!item.isEmpty())
                return false;
        return true;
    }

}
