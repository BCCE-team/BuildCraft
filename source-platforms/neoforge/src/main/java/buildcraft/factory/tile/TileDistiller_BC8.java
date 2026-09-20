/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;

import java.io.IOException;
import java.util.List;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.lib.internal.api.v2.MachineDefinitionLookup;
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.recipe.MachineRecipeApiBridge;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCoreConfig;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.expression.DefaultContexts;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.node.value.NodeVariableBoolean;
import buildcraft.lib.expression.node.value.NodeVariableLong;
import buildcraft.lib.expression.node.value.NodeVariableObject;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FluidSmoother;
import buildcraft.lib.fluid.FluidSmoother.IFluidDataSender;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.data.AverageLong;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.misc.data.ModelVariableData;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileDistiller_BC8 extends TileBC_Neptune implements IDebuggable, MachineRuntimeView {
    public static final FunctionContext MODEL_FUNC_CTX;
    private static final NodeVariableObject<Direction> MODEL_FACING;
    private static final NodeVariableBoolean MODEL_ACTIVE;
    private static final NodeVariableLong MODEL_POWER_AVG;
    private static final NodeVariableLong MODEL_POWER_MAX;

    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("Distiller");
    public static final int NET_TANK_IN = IDS.allocId("TANK_IN");
    public static final int NET_TANK_GAS_OUT = IDS.allocId("TANK_GAS_OUT");
    public static final int NET_TANK_LIQUID_OUT = IDS.allocId("TANK_LIQUID_OUT");
    
    static {
        ExpressionCompat.setup();
        MODEL_FUNC_CTX = DefaultContexts.createWithAll();
        MODEL_FACING = MODEL_FUNC_CTX.putVariableObject("direction", Direction.class);
        MODEL_POWER_AVG = MODEL_FUNC_CTX.putVariableLong("power_average");
        MODEL_POWER_MAX = MODEL_FUNC_CTX.putVariableLong("power_max");
        MODEL_ACTIVE = MODEL_FUNC_CTX.putVariableBoolean("active");
    }

    public static final long MAX_MJ_PER_TICK = 6 * MjAmount.MICRO_MJ_PER_MJ;

    private final Tank tankIn = new Tank("in", 4 * FluidType.BUCKET_VOLUME, this, this::isDistillableFluid);
    private final Tank tankGasOut = new Tank("gasOut", 4 * FluidType.BUCKET_VOLUME, this);
    private final Tank tankLiquidOut = new Tank("liquidOut", 4 * FluidType.BUCKET_VOLUME, this);

    private final MjBattery mjBattery;
    private final long maxMjPerTick;

    public final FluidSmoother smoothedTankIn;
    public final FluidSmoother smoothedTankGasOut;
    public final FluidSmoother smoothedTankLiquidOut;
    
    /** The model variables, used to keep track of the various state-based variables. */
    public final ModelVariableData clientModelData = new ModelVariableData();

    private DistillationRecipeDefinition currentRecipe;
    private long distillPower = 0;
    private boolean hasWork, isActive = false;
    private final AverageLong powerAvg = new AverageLong(100);
    private final SafeTimeTracker updateTracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 2);
    private boolean changedSinceNetUpdate = true;

    private long powerAvgClient;

	public TileDistiller_BC8(BlockPos pos, BlockState bs) {
		super(BCFactoryBlocks.ENTITYBLOCKDISTILLER.get(), pos, bs);
        maxMjPerTick = MachineDefinitionLookup.maxInputMicroMj(
            BuildCraftContentIds.Machines.DISTILLER, MAX_MJ_PER_TICK
        );
        mjBattery = new MjBattery(MachineDefinitionLookup.capacityMicroMj(
            BuildCraftContentIds.Machines.DISTILLER, 1024 * MjAmount.MICRO_MJ_PER_MJ
        ));
        tankIn.setCanDrain(false);
        tankGasOut.setCanFill(false);
        tankLiquidOut.setCanFill(false);

        tankManager.add(tankIn);
        tankManager.add(tankGasOut);
        tankManager.addLast(tankLiquidOut);

        smoothedTankIn = new FluidSmoother(createSender(NET_TANK_IN), tankIn);
        smoothedTankGasOut = new FluidSmoother(createSender(NET_TANK_GAS_OUT), tankGasOut);
        smoothedTankLiquidOut = new FluidSmoother(createSender(NET_TANK_LIQUID_OUT), tankLiquidOut);

        caps.addFluidStorage(tankIn, EnumPipePart.HORIZONTALS);
        caps.addFluidStorage(tankGasOut, EnumPipePart.UP);
        caps.addFluidStorage(tankLiquidOut, EnumPipePart.DOWN);
        caps.addCapabilityInstance(TilesAPI.CAP_HAS_WORK, () -> hasWork, EnumPipePart.VALUES);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(mjBattery)));
    }

    private IFluidDataSender createSender(int netId) {
        return writer -> createAndSendMessage(netId, writer);
    }

    private boolean isDistillableFluid(FluidStack fluid) {
        return MachineRecipeApiBridge.findDistillation(fluid) != null;
    }

    
    @Override
	protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
		super.writeData(bcData);
        nbt.put("tanks", tankManager.serializeNBT(registries));
        nbt.put("battery", mjBattery.serializeNBT(registries));
        bcData.writeLong("distillPower", distillPower);
        powerAvg.writeToNbt(nbt, "powerAvg");
	}

    
	@Override
	protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
		super.readData(bcData);
        tankManager.deserializeNBT(registries, bcData.readCompound("tanks"));
        mjBattery.deserializeNBT(registries, bcData.readCompound("battery"));
        distillPower = bcData.readLong("distillPower");
        powerAvg.readFromNbt(nbt, "powerAvg");
	}


    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_TANK_IN, buffer, side);
                writePayload(NET_TANK_GAS_OUT, buffer, side);
                writePayload(NET_TANK_LIQUID_OUT, buffer, side);
                buffer.writeBoolean(isActive);
                powerAvgClient = powerAvg.getAverageLong();
                final long div = MjAmount.MICRO_MJ_PER_MJ / 2;
                //BCLog.d(powerAvgClient/(double)MjAmount.MICRO_MJ_PER_MJ + "");
                powerAvgClient = Math.round(powerAvgClient / (double) div) * div;
                buffer.writeLong(powerAvgClient);
            } else if (id == NET_TANK_IN) {
                smoothedTankIn.writeInit(buffer);
            } else if (id == NET_TANK_GAS_OUT) {
                smoothedTankGasOut.writeInit(buffer);
            } else if (id == NET_TANK_LIQUID_OUT) {
                smoothedTankLiquidOut.writeInit(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_TANK_IN, buffer, side, ctx);
                readPayload(NET_TANK_GAS_OUT, buffer, side, ctx);
                readPayload(NET_TANK_LIQUID_OUT, buffer, side, ctx);

                smoothedTankIn.resetSmoothing(getLevel());
                smoothedTankGasOut.resetSmoothing(getLevel());
                smoothedTankLiquidOut.resetSmoothing(getLevel());

                isActive = buffer.readBoolean();
                powerAvgClient = buffer.readLong();
            } else if (id == NET_TANK_IN) {
                smoothedTankIn.handleMessage(getLevel(), buffer);
            } else if (id == NET_TANK_GAS_OUT) {
                smoothedTankGasOut.handleMessage(getLevel(), buffer);
            } else if (id == NET_TANK_LIQUID_OUT) {
                smoothedTankLiquidOut.handleMessage(getLevel(), buffer);
            }
        }
    }

    public void setClientModelVariablesForItem() {
        DefaultContexts.RENDER_PARTIAL_TICKS.value = 1;
        MODEL_ACTIVE.value = false;
        MODEL_POWER_AVG.value = 0;
        MODEL_POWER_MAX.value = 6;
        MODEL_FACING.value = Direction.WEST;
    }

    public void setClientModelVariables(float partialTicks) {
        DefaultContexts.RENDER_PARTIAL_TICKS.value = partialTicks;

        MODEL_ACTIVE.value = isActive;
        MODEL_POWER_AVG.value = powerAvgClient / MjAmount.MICRO_MJ_PER_MJ;
        MODEL_POWER_MAX.value = maxMjPerTick / MjAmount.MICRO_MJ_PER_MJ;
        MODEL_FACING.value = Direction.WEST;

        BlockState state = level.getBlockState(worldPosition);
        if (state.getBlock() == BCFactoryBlocks.DISTILLER_BLOCK.get()) {
            MODEL_FACING.value = state.getValue(BlockBCBase_Neptune.PROP_FACING);
        }
    }
    
    public void update() {
        smoothedTankIn.tick(getLevel());
        smoothedTankGasOut.tick(getLevel());
        smoothedTankLiquidOut.tick(getLevel());
        if (level.isClientSide) {
            setClientModelVariables(1);
            clientModelData.tick();
            return;
        }
        powerAvg.tick();
        changedSinceNetUpdate |= powerAvgClient != powerAvg.getAverageLong();

        currentRecipe = MachineRecipeApiBridge.findDistillation(tankIn.getFluid());
        if (currentRecipe == null) {
            mjBattery.addPowerChecking(distillPower, FluidAction.EXECUTE);
            distillPower = 0;
            isActive = false;
            hasWork = false;
        } else {
            FluidStack reqIn = MachineRecipeApiBridge.inputStack(currentRecipe.input(), tankIn.getFluid());
            FluidStack outLiquid = MachineRecipeApiBridge.outputStack(currentRecipe.liquidOutput());
            FluidStack outGas = MachineRecipeApiBridge.outputStack(currentRecipe.gasOutput());

            FluidStack potentialIn = tankIn.drainInternal(reqIn, FluidAction.SIMULATE);
            boolean canExtract = FluidCompatRegistry.areEquivalent(reqIn, potentialIn) && reqIn.getAmount() == potentialIn.getAmount();

            boolean canFillLiquid = tankLiquidOut.fillInternal(outLiquid, FluidAction.SIMULATE) == outLiquid.getAmount();
            boolean canFillGas = tankGasOut.fillInternal(outGas, FluidAction.SIMULATE) == outGas.getAmount();

            if (canExtract && canFillLiquid && canFillGas) {
                hasWork = true;
                long max = maxMjPerTick;
                max *= mjBattery.getStored() + max;
                max /= mjBattery.getCapacity() / 2;
                max = Math.min(max, maxMjPerTick);
                long powerReq = currentRecipe.powerRequiredMicroMj();
                long power = mjBattery.extractPower(0, max);
                powerAvg.push(max);
                distillPower += power;
                isActive = power > 0;
                if (distillPower >= powerReq) {
                    isActive = true;
                    distillPower -= powerReq;
                    tankIn.drainInternal(reqIn, FluidAction.EXECUTE);
                    tankGasOut.fillInternal(outGas, FluidAction.EXECUTE);
                    tankLiquidOut.fillInternal(outLiquid, FluidAction.EXECUTE);
                }
            } else {
                hasWork = false;
                mjBattery.addPowerChecking(distillPower, FluidAction.EXECUTE);
                distillPower = 0;
                isActive = false;
            }
        }

        if (changedSinceNetUpdate && updateTracker.markTimeIfDelay(level)) {
            powerAvgClient = powerAvg.getAverageLong();
            sendNetworkUpdate(NET_RENDER_DATA);
            changedSinceNetUpdate = false;
        }
    }
    

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
		return tankManager.onActivated(player, worldPosition, hand);
	}

	@Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("In = " + tankIn.getDebugString());
        left.add("GasOut = " + tankGasOut.getDebugString());
        left.add("LiquidOut = " + tankLiquidOut.getDebugString());
        left.add("Battery = " + mjBattery.getDebugString());
        left.add("Progress = " + MjFormatting.formatMicroMj(distillPower) + " MJ");
        left.add("Rate = " + LocaleUtil.localizeMjFlow(powerAvgClient));
        left.add("CurrRecipe = " + currentRecipe);
    }
    @Override
    public void getClientDebugInfo(List<String> left, List<String> right, Direction side) {
        setClientModelVariables(1);
        left.add("Model Variables:");
        left.add("  facing = " + MODEL_FACING);
        left.add("  active = " + MODEL_ACTIVE);
        left.add("  power_average = " + MODEL_POWER_AVG);
        left.add("  power_max = " + MODEL_POWER_MAX);
        left.add("Current Model Variables:");
    }
    @Override
    public net.minecraft.resources.ResourceLocation api2MachineTypeId() {
        return BuildCraftContentIds.Machines.DISTILLER;
    }

}
