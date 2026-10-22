/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.energy.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;

import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.enums.EnumPowerStage;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.BCEnergyConfig;
import buildcraft.energy.menu.ContainerEngineStone_BC8;
import buildcraft.lib.delta.DeltaInt;
import buildcraft.lib.delta.DeltaManager.EnumNetworkVisibility;
import buildcraft.lib.engine.EngineConnector;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
//? if mc_26_x && (>=26.3) {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
//? if mc_26_x && (>=26.3) {
import buildcraft.lib.compat.GameProfileCompat;
import buildcraft.lib.compat.ItemCompat;
import net.neoforged.neoforge.transfer.item.ItemResource;
//? } else {
import net.neoforged.neoforge.items.IItemHandlerModifiable;
//?}

public class TileEngineStone_BC8 extends TileEngineBase_BC8 implements MenuProvider{
//? if mc_26_x && (>=26.3) {
    private static final Identifier ADVANCEMENT_LAVA_POWER =
        Identifier.parse("buildcraftenergy:lava_power");
//? } else {
    private static final ResourceLocation ADVANCEMENT_LAVA_POWER =
        ResourceLocation.parse("buildcraftenergy:lava_power");
//?}
    private static final long MAX_OUTPUT = MjAmount.MICRO_MJ_PER_MJ;
    private static final long MIN_OUTPUT = MAX_OUTPUT / 3;
    // private static final long TARGET_OUTPUT = 0.375f;
    private static final float kp = 1f;
    private static final float ki = 0.05f;
    private static final long eLimit = (MAX_OUTPUT - MIN_OUTPUT) * 20;

    public final DeltaInt deltaFuelLeft = deltaManager.addDelta("fuel_left", EnumNetworkVisibility.GUI_ONLY);
    public final ItemHandlerSimple invFuel;

    int burnTime = 0;
    int totalBurnTime = 0;
    long esum = 0;

    private boolean isForceInserting = false;

    public TileEngineStone_BC8(BlockPos pos, BlockState state) {
    	super(BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get(), pos, state);
//? if mc_26_x && (>=26.3) {
        invFuel = itemManager.addInvHandler("fuel", 1, this::isValidFuel, EnumAccess.BOTH, EnumPipePart.VALUES).setChecker((a,b) -> isForceInserting || ItemCompat.isFuel(b));
//? } else {
        invFuel = itemManager.addInvHandler("fuel", 1, this::isValidFuel, EnumAccess.BOTH, EnumPipePart.VALUES).setChecker((a,b) -> b.getBurnTime(RecipeType.SMELTING) > 0);
//?}
        caps.addProvider(itemManager);
    }

    
     // Actually this should be called isVaildFuel, but if i change it, there will be a bug in Eclipse202306
    private boolean isValidFuel(int slot, ItemStack stack) {
        // Always allow inserting container items if they aren't fuel
//? if mc_26_x && (>=26.3) {
        return isForceInserting || (!ItemResource.of(stack).isEmpty() && ItemCompat.isFuel(stack));
//? } else {
        return isForceInserting || getItemBurnTime(stack) > 0;
//?}
    }

    // BlockEntity overrides

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        burnTime = bcData.readInt("burnTime");
        totalBurnTime = bcData.readInt("totalBurnTime");
        esum = bcData.readLong("esum");
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected void writeData(BCValueOutput bcData) {
        super.writeData(bcData);
        bcData.writeInt("burnTime", burnTime);
        bcData.writeInt("totalBurnTime", totalBurnTime);
        bcData.writeLong("esum", esum);
    }

//? if mc_26_x && (>=26.3) {
    private void refreshForcedFuelState() {
        if (isForceInserting && invFuel.getStackInSlot(0).isEmpty()) {
            isForceInserting = false;
//? } else {
    @Override
    protected void onSlotChange(IItemHandlerModifiable handler, int slot, @Nonnull ItemStack before,
        @Nonnull ItemStack after) {
        if (handler == invFuel) {
            if (isForceInserting && after.isEmpty()) {
                isForceInserting = false;
            }
//?}
        }
    }

    // Engine overrides
    
//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
//? if mc_26_x && (>=26.3) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
//? } else {
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
//?}
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
	}

    
    
	@Nonnull
//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected IMjConnector createConnector() {
        return new EngineConnector(false);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean isBurning() {
        return burnTime > 0;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected boolean canUnlockPoweringUpAdvancement() {
        return true;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected void engineUpdate() {
//? if mc_26_x && (>=26.3) {
        refreshForcedFuelState();
//? } else {
//?}
        super.engineUpdate();
//? if mc_26_x && (>=26.3) {
//? } else {
        // The previously produced MJ/t must not remain visible after fuel runs out
        // or the engine enters an overheat state.
        currentOutput = 0;
//?}
        if (burnTime > 0) {
            burnTime--;
            if (getPowerStage() != EnumPowerStage.OVERHEAT) {
                // this seems wrong...
                long error = 3 * getMaxPower() / 8 - power;
                esum = clamp(esum + error, -eLimit, eLimit);
                long output = getCurrentOutput();
                currentOutput = output; // Comment out for constant power
                addPower(output);
            }
        }
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void burn() {
//? if mc_26_x && (>=26.3) {
        refreshForcedFuelState();
//? } else {
//?}
        if (burnTime == 0 && isRedstonePowered) {
            burnTime = totalBurnTime = getItemBurnTime(invFuel.getStackInSlot(0));

            if (burnTime > 0) {
                deltaFuelLeft.setValue(100);
                deltaFuelLeft.addDelta(0, totalBurnTime, -100);

                ItemStack fuel = invFuel.extractItem(0, 1, false);
                if (fuel.is(Items.LAVA_BUCKET) && getOwner() != null) {
//? if mc_26_x && (>=26.3) {
                    AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT_LAVA_POWER);
//? } else {
                    AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_LAVA_POWER);
//?}
                }
//? if mc_26_x && (>=26.3) {
                ItemStack container = ItemCompat.getCraftingRemainingItem(fuel);
//? } else {
                ItemStack container = fuel.getCraftingRemainingItem();
//?}
                if (!container.isEmpty()) {
                    if (invFuel.getStackInSlot(0).isEmpty()) {
                        isForceInserting = false;
                        ItemStack leftover = invFuel.insert(container, false, false);
                        if (!leftover.isEmpty()) {
                            isForceInserting = true;
                            invFuel.setStackInSlot(0, leftover);
                        }
                    } else {
                        // Not good!
                        InventoryUtil.addToBestAcceptor(getLevel(), getBlockPos(), null, container);
                    }
                }
            }
        }
    }

//? if mc_26_x && (>=26.3) {
    private int getItemBurnTime(ItemStack itemstack) {
        // Providers observe the real machine and a snapshot of its inventory.
        var contents = new net.minecraft.world.SimpleContainer(
            invFuel.stacks.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
        return ItemCompat.getBurnTime(itemstack, this, contents);
//? } else {
    private static int getItemBurnTime(ItemStack itemstack) {
        return itemstack.getBurnTime(RecipeType.SMELTING);
//?}
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long maxPowerReceived() {
        return 200 * MjAmount.MICRO_MJ_PER_MJ;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long maxPowerExtracted() {
        return MAX_OUTPUT;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long getMaxPower() {
        return 1000 * MjAmount.MICRO_MJ_PER_MJ;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected boolean shouldExplodeOnOverheat() {
        return BCEnergyConfig.enableStirlingEngineExplosion;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public float explosionRange() {
        return 2;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long getCurrentOutput() {
        // double e = 0.375 * getMaxEnergy() - energy;
        // esum = MathUtils.clamp(esum + e, -eLimit, eLimit);
        // return MathUtils.clamp(e * 1 + esum * 0.05, MIN_OUTPUT, MAX_OUTPUT);

        long e = 3 * getMaxPower() / 8 - power;
        return clamp(e + esum / 20, MIN_OUTPUT, MAX_OUTPUT);
    }

    private static long clamp(long val, long min, long max) {
        return Math.max(min, Math.min(max, val));
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("esum = " + MjFormatting.formatMicroMj(esum) + " MJ");
        long e = 3 * getMaxPower() / 8 - power;
        left.add("output = " + MjFormatting.formatMicroMj(clamp(e + esum / 20, MIN_OUTPUT, MAX_OUTPUT)) + " MJ");
        left.add("burnTime = " + burnTime);
        left.add("delta = " + deltaFuelLeft.getDynamic(0));
    }


//? if mc_26_x && (>=26.3) {
//? } else {
	@Override
//?}
	public AbstractContainerMenu createMenu(int id, Inventory playerIncentory, Player p_39956_) {
		return new ContainerEngineStone_BC8(id, playerIncentory, invFuel, ContainerLevelAccess.create(level, worldPosition));
	}


//? if mc_26_x && (>=26.3) {
//? } else {
	@Override
//?}
	public Component getDisplayName() {
		return Component.translatable("block.buildcraftcore.engine_"+this.getBlockState().getValue(BuildCraftProperties.ENGINE_TYPE).getSerializedName());
	}
}
