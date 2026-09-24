/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.tile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.platform.registry.PlatformMenus;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.logic.request.RequestMath;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.request.ItemRequest;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.robotics.internal.api2.RequestSupport;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.tile.item.StackInsertionFunction;
import buildcraft.lib.tile.item.StackInsertionFunction.InsertionResult;
import buildcraft.robotics.BCRoboticsBlocks;
import buildcraft.robotics.container.ContainerRequester;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class TileRequester extends TileBC_Neptune implements RequestProvider, IDebuggable, MenuProvider {
    protected static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("requester");

    public static final int NB_ITEMS = 20;

    public final ItemHandlerSimple requests = itemManager.addInvHandler(
            "requests",
            NB_ITEMS,
            (slot, stack) -> true,
            EnumAccess.PHANTOM
    );

    public final ItemHandlerSimple inv = itemManager.addInvHandler(
            "inv",
            NB_ITEMS,
            (slot, stack) -> stack.isEmpty() || canSetRealSlot(slot, stack),
            this::insertOnlyRequestedAmount,
            EnumAccess.BOTH,
            EnumPipePart.VALUES
    );

    public TileRequester(BlockPos pos, BlockState state) {
        super(BCRoboticsBlocks.REQUESTER_TILE.get(), pos, state);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public boolean canSetRealSlot(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        ItemStack template = getRequestTemplate(slot);
        return !template.isEmpty() && StackUtil.isMatchingItemOrList(template, stack);
    }

    private InsertionResult insertOnlyRequestedAmount(int slot, ItemStack existing, ItemStack offered) {
        if (offered.isEmpty()) {
            return new InsertionResult(existing, ItemStack.EMPTY);
        }
        ItemStack template = getRequestTemplate(slot);
        if (template.isEmpty() || !StackUtil.isMatchingItemOrList(template, offered)) {
            return new InsertionResult(existing, offered);
        }

        int requested = Math.min(template.getCount(), offered.getMaxStackSize());
        if (!existing.isEmpty()) {
            if (!StackUtil.isMatchingItemOrList(existing, offered) || existing.getCount() >= requested) {
                return new InsertionResult(existing, offered);
            }
        }
        return StackInsertionFunction.getInsertionFunction(requested)
                .modifyForInsertion(slot, existing, offered);
    }

    public void setRequest(int index, ItemStack stack) {
        if (!isValidSlot(index)) {
            return;
        }
        ItemStack template = sanitizeTemplate(stack);
        requests.setStackInSlot(index, template);
        setChanged();
    }

    public ItemStack getRequestTemplate(int index) {
        return isValidSlot(index) ? requests.getStackInSlot(index) : ItemStack.EMPTY;
    }

    public boolean isFulfilled(int index) {
        ItemStack template = getRequestTemplate(index);
        if (template.isEmpty()) {
            return true;
        }
        ItemStack existing = inv.getStackInSlot(index);
        return !existing.isEmpty()
                && StackUtil.isMatchingItemOrList(template, existing)
                && RequestMath.fulfilled(template.getCount(), existing.getCount());
    }

    private ItemStack getRequest(int index) {
        if (!isValidSlot(index) || isFulfilled(index)) {
            return ItemStack.EMPTY;
        }

        ItemStack request = getRequestTemplate(index).copy();
        ItemStack existing = inv.getStackInSlot(index);
        if (existing.isEmpty()) {
            return request;
        }
        if (!StackUtil.isMatchingItemOrList(request, existing)) {
            return ItemStack.EMPTY;
        }

        int missing = RequestMath.missingAmount(request.getCount(), existing.getCount());
        if (missing <= 0) {
            return ItemStack.EMPTY;
        }
        request.setCount(missing);
        return request;
    }

    private ItemStack offerItem(int index, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!isValidSlot(index)) {
            return stack;
        }

        ItemStack template = getRequestTemplate(index);
        if (template.isEmpty() || !StackUtil.isMatchingItemOrList(template, stack)) {
            return stack;
        }

        ItemStack existing = inv.getStackInSlot(index);
        if (existing.isEmpty()) {
            int accepted = RequestMath.acceptedAmount(stack.getCount(), template.getCount(), 0);
            if (!simulate) {
                ItemStack inserted = stack.copy();
                inserted.setCount(accepted);
                inv.setStackInSlot(index, inserted);
            }
            return copyRemainder(stack, accepted);
        }

        if (!StackUtil.isMatchingItemOrList(existing, stack)) {
            return stack;
        }

        int accepted = RequestMath.acceptedAmount(stack.getCount(), template.getCount(), existing.getCount());
        if (accepted <= 0) {
            return stack;
        }
        if (!simulate) {
            ItemStack updated = existing.copy();
            updated.grow(accepted);
            inv.setStackInSlot(index, updated);
        }
        return copyRemainder(stack, accepted);
    }

    @Override
    public Collection<ItemRequest> requests() {
        List<ItemRequest> result = new ArrayList<>();
        for (int slot = 0; slot < NB_ITEMS; slot++) {
            ItemStack request = getRequest(slot);
            if (!request.isEmpty()) {
                result.add(RequestSupport.request(slot, request, NB_ITEMS - slot));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public ItemTransferResult offer(ResourceLocation requestId, ItemStack offered, OperationMode mode) {
        int slot = RequestSupport.slot(requestId).orElse(-1);
        if (slot < 0 || slot >= NB_ITEMS || offered == null || offered.isEmpty()) {
            return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
        }
        ItemStack remainder = offerItem(slot, offered.copy(), mode == OperationMode.SIMULATE);
        return ItemTransferResult.ofInsertion(offered, offered.getCount() - remainder.getCount());
    }

    @Override
    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before,
            @Nonnull ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        if (level != null && !level.isClientSide) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PlatformMenus.open(serverPlayer, this, worldPosition);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerRequester(id, inventory, this, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    public int getComparatorSignal() {
        int[] requested = new int[NB_ITEMS];
        int[] existingCounts = new int[NB_ITEMS];
        boolean[] matching = new boolean[NB_ITEMS];
        for (int slot = 0; slot < NB_ITEMS; slot++) {
            ItemStack template = getRequestTemplate(slot);
            if (template.isEmpty()) continue;
            requested[slot] = template.getCount();
            ItemStack existing = inv.getStackInSlot(slot);
            if (!existing.isEmpty() && StackUtil.isMatchingItemOrList(template, existing)) {
                existingCounts[slot] = existing.getCount();
                matching[slot] = true;
            }
        }
        return RequestMath.comparatorSignal(requested, existingCounts, matching);
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        int active = 0;
        int missing = 0;
        for (int i = 0; i < NB_ITEMS; i++) {
            if (!getRequestTemplate(i).isEmpty()) {
                active++;
                if (!getRequest(i).isEmpty()) {
                    missing++;
                }
            }
        }
        left.add("active_requests = " + active);
        left.add("missing_requests = " + missing);
    }

    private static boolean isValidSlot(int index) {
        return index >= 0 && index < NB_ITEMS;
    }

    private static ItemStack sanitizeTemplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        copy.setCount(RequestMath.sanitizeTemplateCount(copy.getCount(), copy.getMaxStackSize()));
        return copy;
    }

    private static ItemStack copyRemainder(ItemStack stack, int accepted) {
        int remaining = stack.getCount() - accepted;
        if (remaining <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack.copy();
        remainder.setCount(remaining);
        return remainder;
    }
}
