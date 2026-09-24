//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.lib.platform.storage.MutableItemStorage;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;
import java.util.Arrays;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.tiles.IHasWork;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.craft.IAutoCraft;
import buildcraft.lib.tile.craft.WorkbenchCrafting;
import buildcraft.lib.tile.item.ItemHandlerFiltered;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.lib.compat.NbtCompat;

public abstract class TileAutoWorkbenchBase extends TileBC_Neptune
    implements IHasWork, IMjRedstoneReceiver, IAutoCraft {

    /** A redstone engine generates <code> 1 * {@link MjAmount#MICRO_MJ_PER_MJ}</code> per tick. This makes it a lot slower without one
     * powering it. */
    private static final long POWER_GEN_PASSIVE = MjAmount.MICRO_MJ_PER_MJ / 5;

    /** It takes 10 seconds to craft an item. */
    private static final long POWER_REQUIRED = POWER_GEN_PASSIVE * 20 * 10;

    private static final long POWER_LOST = POWER_GEN_PASSIVE * 10;
    private static final int POWER_SAVE_INTERVAL = 20;

    private static final Identifier ADVANCEMENT_AUTOCRAFT = Identifier.parse("buildcraftfactory:lazy_crafting");

    public final ItemHandlerSimple invBlueprint;
    public final ItemHandlerSimple invMaterialFilter;
    public final ItemHandlerFiltered invMaterials;
    public final ItemHandlerSimple invResult;
    protected final WorkbenchCrafting crafting;
    /** The amount of power that is stored until crafting can begin. When this reaches the minimum power required it
     * will craft the current recipe. */
    private long powerStored;
    private final buildcraft.lib.compat.transfer.TransferJournal<Long> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(() -> powerStored, value -> powerStored = value,
            old -> markChunkDirty());
    private long powerStoredLast;
    private long persistedPowerStored = Long.MIN_VALUE;
    private long lastPowerSaveTick = Long.MIN_VALUE;

    public ItemStack resultClient = ItemStack.EMPTY;
    private int recipeSelectionIndexClient = -1;
    private int recipeSelectionCountClient;

    public TileAutoWorkbenchBase(BlockEntityType<?> type, BlockPos pos, BlockState state, int width, int height) {
        super(type, pos, state);
        int slots = width * height;
        invBlueprint = itemManager.addInvHandler("blueprint", slots, EnumAccess.PHANTOM);
        invMaterialFilter = itemManager.addInvHandler("material_filter", slots, EnumAccess.PHANTOM);
        invMaterials = new ItemHandlerFiltered(invMaterialFilter, true);
        invMaterials.setCallback(itemManager.callback);
        itemManager.addInvHandler("materials", invMaterials, EnumAccess.INSERT, EnumPipePart.VALUES);
        invResult = itemManager.addInvHandler("result", 1, EnumAccess.EXTRACT, EnumPipePart.VALUES);
        crafting = new WorkbenchCrafting(width, height, this, invBlueprint, invMaterials, invResult);
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, this, EnumPipePart.VALUES);
        caps.addProvider(new MjCapabilityHelper(this));
        caps.addProvider(itemManager);
    }

    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        super.writeData(bcData);
        bcData.writeLong("powerStored", powerStored);
        crafting.writeSelection(nbt);
    }

    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        super.readData(bcData);
        powerStored = Math.max(0, Math.min(POWER_REQUIRED, bcData.readLong("powerStored")));
        powerStoredLast = powerStored;
        persistedPowerStored = powerStored;
        crafting.readSelection(nbt);
    }

    protected void onSlotChange(MutableItemStorage handler, int slot, @Nonnull ItemStack before,
        @Nonnull ItemStack after) {
        super.onSlotChange(handler, slot, before, after);
        if (!ItemStack.matches(before, after)) {
            crafting.onInventoryChange(handler);
        }
    }

    public void update() {
        if (level.isClientSide()) {
            return;
        }
        boolean didChange = crafting.tick();
        boolean completedCraft = false;
        long oldPowerStored = powerStored;
        if (crafting.canCraft()) {
            if (powerStored >= POWER_REQUIRED) {
                if (crafting.craft()) {
                    completedCraft = true;
                    // This is used for #hasWork(), to ensure that it doesn't return
                    // false for the one tick in between crafts.
                    powerStored = crafting.canCraft() ? 1 : 0;
                    AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT_AUTOCRAFT);
                }
            } else {
                powerStored += POWER_GEN_PASSIVE;
            }
        } else if (powerStored >= POWER_LOST) {
            powerStored -= POWER_LOST;
        } else {
            powerStored = 0;
        }
        if (powerStored != oldPowerStored) {
            markPowerProgressChanged(completedCraft || powerStored == 0);
        }
        if (didChange) {
            resultClient = crafting.getAssumedResult().copy();
            createFilters();
            sendNetworkGuiUpdate(NET_GUI_DATA);
        }
    }

    private void markPowerProgressChanged(boolean force) {
        if (powerStored == persistedPowerStored) {
            return;
        }
        long now = level.getGameTime();
        if (force || lastPowerSaveTick == Long.MIN_VALUE || now - lastPowerSaveTick >= POWER_SAVE_INTERVAL) {
            setChanged();
            persistedPowerStored = powerStored;
            lastPowerSaveTick = now;
        }
    }

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_GUI_TICK) {
                buffer.writeLong(powerStored);
            } else if (id == NET_GUI_DATA) {
                resultClient = crafting.getAssumedResult().copy();
                buffer.writeInt(crafting.getSelectedRecipeIndex());
                buffer.writeInt(crafting.getMatchingRecipeCount());
            }
        }
    }

    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_GUI_TICK) {
                powerStoredLast = powerStored;
                powerStored = buffer.readLong();
                if (powerStored < 10) {
                    // properly handle crafting finishes
                    powerStoredLast = powerStored;
                }
            } else if (id == NET_GUI_DATA) {
                recipeSelectionIndexClient = buffer.readInt();
                recipeSelectionCountClient = buffer.readInt();
            }
        }
    }

    public boolean hasWork() {
        return powerStored > 0;
    }

    public double getProgress(float partialTicks) {
        return MathUtil.interp(partialTicks, powerStoredLast, powerStored) / POWER_REQUIRED;
    }

    public WorkbenchCrafting getWorkbenchCrafting() {
        return crafting;
    }

    public int getRecipeSelectionIndex() {
        return level != null && level.isClientSide() ? recipeSelectionIndexClient : crafting.getSelectedRecipeIndex();
    }

    public int getRecipeSelectionCount() {
        return level != null && level.isClientSide() ? recipeSelectionCountClient : crafting.getMatchingRecipeCount();
    }

    public boolean cycleRecipe(int delta) {
        if (level == null || level.isClientSide() || !crafting.selectRecipe(delta)) {
            return false;
        }
        resultClient = crafting.getAssumedResult().copy();
        createFilters();
        setChanged();
        sendNetworkGuiUpdate(NET_GUI_DATA);
        return true;
    }

    private void createFilters() {
        if (crafting.getAssumedResult().isEmpty()) {
            for (int s = 0; s < invMaterialFilter.getSlots(); s++) {
                invMaterialFilter.setStackInSlot(s, ItemStack.EMPTY);
            }
            return;
        }
        NonNullList<ItemStack> uniqueStacks = NonNullList.create();
        int slotCount = invBlueprint.getSlots();
        int[] requirements = new int[slotCount];
        for (int s = 0; s < slotCount; s++) {
            ItemStack bptStack = invBlueprint.getStackInSlot(s);
            if (!bptStack.isEmpty()) {
                boolean foundMatch = false;
                for (int i = 0; i < uniqueStacks.size(); i++) {
                    if (StackUtil.canMerge(bptStack, uniqueStacks.get(i))) {
                        foundMatch = true;
                        requirements[i]++;
                        break;
                    }
                }
                if (!foundMatch) {
                    requirements[uniqueStacks.size()] = 1;
                    uniqueStacks.add(bptStack);
                }
            }
        }
        int uniqueSlotCount = uniqueStacks.size();
        int[] slotAllocationCount = new int[uniqueSlotCount];
        Arrays.fill(slotAllocationCount, 1);
        int slotsLeft = slotCount - uniqueSlotCount;
        for (int i = 0; i < slotsLeft; i++) {
            int smallestDifference = Integer.MAX_VALUE;
            int smallestDifferenceIndex = 0;

            for (int s = 0; s < uniqueSlotCount; s++) {
                ItemStack stack = uniqueStacks.get(s);
                int uniqueCountTotal = stack.getMaxStackSize() * slotAllocationCount[s];

                int difference = uniqueCountTotal / requirements[s];
                if (difference < smallestDifference) {
                    smallestDifference = difference;
                    smallestDifferenceIndex = s;
                }
            }
            slotAllocationCount[smallestDifferenceIndex]++;
        }

        int realIndex = 0;
        for (int s = 0; s < uniqueSlotCount; s++) {
            ItemStack stack = uniqueStacks.get(s).copy();
            stack.setCount(1);
            for (int i = 0; i < slotAllocationCount[s]; i++) {
                invMaterialFilter.setStackInSlot(realIndex, stack);
                realIndex++;
            }
        }
        if (realIndex != slotCount) {
            throw new IllegalStateException("Somehow the balanced formula wasn't perfectly balanced!");
        }
    }

    // IMjRedstoneReceiver

    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

    public long getPowerRequested() {
        return POWER_REQUIRED - powerStored;
    }

    public long receivePower(long microJoules, FluidAction simulate) {
        long req = getPowerRequested();
        long taken = Math.min(req, microJoules);
        if (simulate.execute()) {
            transferJournal.record();
            powerStored += taken;
        }
        return microJoules - taken;
    }

    // IAutoCraft

    public ItemStack getCurrentRecipeOutput() {
        return crafting.getAssumedResult();
    }

    public ItemHandlerSimple getInvBlueprint() {
        return invBlueprint;
    }
}
