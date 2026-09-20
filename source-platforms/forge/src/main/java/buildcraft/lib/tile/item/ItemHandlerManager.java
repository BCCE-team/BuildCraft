/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.tile.item;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.inventory.ItemHandlerWrapper;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.StorageAdapters;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

public class ItemHandlerManager implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public enum EnumAccess {
        /** An {@link IItemHandler} that shouldn't be accessible by external sources. */
        NONE,
        /** Same as {@link #NONE}, but the contents of this inventory won't be dropped when the block is removed.
         * Additionally the items will be considered "free", and so items can be duplicated into these slots */
        PHANTOM,
        INSERT,
        EXTRACT,
        /** Full interaction is allowed. */
        BOTH
    }

    public final StackChangeCallback callback;
    private final List<IItemHandlerModifiable> handlersToDrop = new ArrayList<>();
    private final Map<EnumPipePart, Wrapper> wrappers = new EnumMap<>(EnumPipePart.class);
    private final Map<String, INBTSerializable<CompoundTag>> handlers = new HashMap<>();

    public ItemHandlerManager(StackChangeCallback defaultCallback) {
        this.callback = defaultCallback;
        for (EnumPipePart part : EnumPipePart.VALUES) {
            wrappers.put(part, new Wrapper());
        }
    }

    public <T extends INBTSerializable<CompoundTag> & IItemHandlerModifiable> T addInvHandler(String key, T handler,
        EnumAccess access, EnumPipePart... parts) {
        if (parts == null) {
            parts = new EnumPipePart[0];
        }
        IItemHandlerModifiable external = handler;
        if (access == EnumAccess.NONE || access == EnumAccess.PHANTOM) {
            // Internal/phantom inventories are never exposed through sided item capabilities.
            // Ignore accidental side arguments instead of crashing block-entity construction.
            external = null;
        } else if (access == EnumAccess.EXTRACT) {
            external = new WrappedItemHandlerExtract(handler);
        } else if (access == EnumAccess.INSERT) {
            external = new WrappedItemHandlerInsert(handler);
        }

        if (external != null) {
            Set<EnumPipePart> visited = EnumSet.noneOf(EnumPipePart.class);
            for (EnumPipePart part : parts) {
                if (part == null) part = EnumPipePart.CENTER;
                if (visited.add(part)) {
                    Wrapper wrapper = wrappers.get(part);
                    wrapper.handlers.add(external);
                    wrapper.genWrapper();
                }
            }
        }
        if (access != EnumAccess.PHANTOM) {
            handlersToDrop.add(handler);
        }
        handlers.put(key, handler);
        return handler;
    }

    public ItemHandlerSimple addInvHandler(String key, int size, EnumAccess access, EnumPipePart... parts) {
        ItemHandlerSimple handler = new ItemHandlerSimple(size, callback);
        return addInvHandler(key, handler, access, parts);
    }

    public ItemHandlerSimple addInvHandler(String key, int size, StackInsertionChecker checker, EnumAccess access,
        EnumPipePart... parts) {
        ItemHandlerSimple handler = new ItemHandlerSimple(size, callback);
        handler.setChecker(checker);
        return addInvHandler(key, handler, access, parts);
    }

    public ItemHandlerSimple addInvHandler(String key, int size, StackInsertionFunction insertionFunction,
        EnumAccess access, EnumPipePart... parts) {
        ItemHandlerSimple handler = new ItemHandlerSimple(size, callback);
        handler.setInsertor(insertionFunction);
        return addInvHandler(key, handler, access, parts);
    }

    public ItemHandlerSimple addInvHandler(String key, int size, StackInsertionChecker checker,
        StackInsertionFunction insertionFunction, EnumAccess access, EnumPipePart... parts) {
        ItemHandlerSimple handler = new ItemHandlerSimple(size, checker, insertionFunction, callback);
        return addInvHandler(key, handler, access, parts);
    }

    public void addDrops(NonNullList<ItemStack> toDrop) {
        for (IItemHandlerModifiable itemHandler : handlersToDrop) {
            InventoryUtil.addAll(itemHandler, toDrop);
        }
    }

    public ItemStorage getItemStorage(Direction facing) {
        Wrapper wrapper = wrappers.get(EnumPipePart.fromFacing(facing));
        return wrapper == null || wrapper.combined == null ? null : StorageAdapters.fromNativeItems(wrapper.combined);
    }


    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        Wrapper wrapper = wrappers.get(EnumPipePart.fromFacing(facing));
        if (capability == CapUtil.CAP_ITEM_TRANSACTOR) {
            return wrapper.transactor == null ? LazyOptional.empty() : LazyOptional.of(() -> wrapper.transactor).cast();
        }
        return LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        for (Entry<String, INBTSerializable<CompoundTag>> entry : handlers.entrySet()) {
            String key = entry.getKey();
            nbt.put(key, entry.getValue().serializeNBT());
        }
        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        for (Entry<String, INBTSerializable<CompoundTag>> entry : handlers.entrySet()) {
            String key = entry.getKey();
            entry.getValue().deserializeNBT(nbt.getCompound(key));
        }
    }

    private static class Wrapper {
        private final List<IItemHandlerModifiable> handlers = new ArrayList<>();
        private IItemHandlerModifiable combined = null;
        private IItemTransactor transactor = null;

        public void genWrapper() {
            if (handlers.isEmpty()) {
                combined = null;
                transactor = null;
                return;
            }
            if (handlers.size() == 1) {
                combined = handlers.get(0);
            } else {
                IItemHandlerModifiable[] arr = handlers.toArray(new IItemHandlerModifiable[0]);
                combined = new CombinedItemHandlerWrapper(arr);
            }
            transactor = combined instanceof IItemTransactor direct ? direct : new ItemHandlerWrapper(combined);
        }
    }
}
