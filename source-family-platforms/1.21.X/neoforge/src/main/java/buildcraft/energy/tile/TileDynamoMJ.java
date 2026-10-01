//? source if >=1.21.11
package buildcraft.energy.tile;

import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjCapabilities;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.platform.ExternalEnergyPort;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.Nullable;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.core.BCCoreItems;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.menu.ContainerDynamoMJ;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.platform.storage.EnergyStorage;

import buildcraft.lib.misc.CapUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCPacketContext;

/** BuildCraft 8 MJ Dynamo: consumes MJ and produces Forge Energy. */
public class TileDynamoMJ extends TileEngineBase_BC8 implements MenuProvider {
    public static final int MAX_FE = 10_000;
    public static final long MAX_MJ = 1_000L * MjAmount.MICRO_MJ_PER_MJ;
    public static final double HEAT_RATE = 0.06;
    public static final double COOLDOWN_RATE = 0.01;

    public static final Map<Item, Long> FE_UPGRADES = new LinkedHashMap<>();

    /* Assigned by createConnector(), which is invoked by TileEngineBase_BC8 during base initialization. */
    private MjBattery mjBattery;
    private MjBatteryReceiver mjReceiver;
    private final MjCapabilityHelper inputMjCaps = new MjCapabilityHelper(mjReceiver);

    public final ItemHandlerSimple invUpgrades;
    private int currentFe;
    private final buildcraft.lib.compat.transfer.TransferJournal<Integer> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(() -> currentFe, value -> currentFe = value,
            old -> markChunkDirty());
    private int persistedFeState;
    private long persistedMjState;
    private boolean energyStateCaptured;
    private final EnergyStorage feStorage = new FeStorage();
    private final ExternalEnergyPort api2FeOutputPort = new ExternalEnergyPort() {
        public long insert(long offered, OperationMode mode) { return 0; }
        public long extract(long requested, OperationMode mode) {
            long extracted = Math.min(Math.max(0L, requested), currentFe);
            if (mode == OperationMode.EXECUTE && extracted > 0) {
                transferJournal.record();
                currentFe -= (int) extracted;
                markEnergyStateDirty();
            }
            return extracted;
        }
        public long stored() { return currentFe; }
        public long capacity() { return MAX_FE; }
        public boolean canInsert() { return false; }
        public boolean canExtract() { return true; }
    };

    public TileDynamoMJ(BlockPos pos, BlockState state) {
        super(BCEnergyBlocks.DYNAMO_MJ_TILE.get(), pos, state);
        invUpgrades = itemManager.addInvHandler(
            "upgrades", 4, (slot, stack) -> isValidUpgrade(stack), EnumAccess.NONE
        ).setLimitedInsertor(1);
        caps.addProvider(itemManager);
        caps.addEnergyStorage(side -> side == currentDirection ? feStorage : null, EnumPipePart.VALUES);
    }

    private static void ensureUpgradeMap() {
        if (!FE_UPGRADES.isEmpty()) return;
        FE_UPGRADES.put(BCCoreItems.GEAR_IRON.get(), 2L * MjAmount.MICRO_MJ_PER_MJ);
        FE_UPGRADES.put(BCCoreItems.GEAR_GOLD.get(), 3L * MjAmount.MICRO_MJ_PER_MJ);
    }

    private static boolean isValidUpgrade(ItemStack stack) {
        ensureUpgradeMap();
        return !stack.isEmpty() && FE_UPGRADES.containsKey(stack.getItem());
    }

    @Nonnull
    protected IMjConnector createConnector() {
        mjBattery = new MjBattery(MAX_MJ);
        mjReceiver = new MjBatteryReceiver(mjBattery);
        return mjReceiver;
    }

    public static long getMjPerTick(IItemHandlerAdv upgrades) {
        ensureUpgradeMap();
        long value = 4L * MjAmount.MICRO_MJ_PER_MJ;
        if (upgrades == null) return value;
        for (int slot = 0; slot < upgrades.getSlots(); slot++) {
            ItemStack stack = upgrades.getStackInSlot(slot);
            Long add = stack.isEmpty() ? null : FE_UPGRADES.get(stack.getItem());
            if (add != null) value += add;
        }
        return value;
    }

    public long getMjPerTick() {
        return getMjPerTick(invUpgrades);
    }

    public static int getFeGenerationRate(IItemHandlerAdv upgrades) {
        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        if (ratio <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, getMjPerTick(upgrades) / ratio);
    }

    public int getFeGenerationRate() {
        return getFeGenerationRate(invUpgrades);
    }

    private void captureEnergyState() {
        persistedFeState = currentFe;
        persistedMjState = mjBattery.getStored();
        energyStateCaptured = true;
    }

    private void markEnergyStateDirty() {
        if (buildcraft.lib.compat.transfer.TransferJournal.defer(this::markEnergyStateDirty)) return;
        markChunkDirty();
        captureEnergyState();
    }

    private void markEnergyStateDirtyIfChanged() {
        if (!energyStateCaptured) {
            captureEnergyState();
            return;
        }
        if (persistedFeState != currentFe || persistedMjState != mjBattery.getStored()) {
            markEnergyStateDirty();
        }
    }

    protected void engineUpdate() {
        if (!isRedstonePowered && currentFe > 0) currentFe--;
        currentOutput = 0;
        mjBattery.tick(level, worldPosition);
        markEnergyStateDirtyIfChanged();
    }

    protected void burn() {
        if (!isRedstonePowered || currentFe >= MAX_FE) return;

        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        if (ratio <= 0) return;

        long stored = mjBattery.getStored();
        if (stored <= 0) return;

        int maxFe = Math.min(getFeGenerationRate(), (int) Math.min(Integer.MAX_VALUE, stored / ratio));
        if (maxFe <= 0) return;
        if ((long) currentFe + maxFe >= MAX_FE) return;

        long cost = Math.multiplyExact((long) maxFe, ratio);
        if (mjBattery.extractPower(cost)) {
            transferJournal.record();
            currentFe += maxFe;
            currentOutput = maxFe;
            heat = Math.min(200.0, heat + HEAT_RATE);
            markEnergyStateDirty();
        }
    }

    public void updateHeatLevel() {
        if (heat > MIN_HEAT) heat -= COOLDOWN_RATE;
        if (heat <= MIN_HEAT) heat = MIN_HEAT;
        getPowerStage();
    }

    public boolean isBurning() { return mjBattery.getStored() > 0 && isRedstonePowered; }
    public double getPistonSpeed() {
        return switch (getPowerStage()) {
            case BLUE -> 0.04;
            case GREEN -> 0.05;
            case YELLOW -> 0.06;
            case RED -> 0.07;
            default -> 0;
        };
    }
    public double getPowerLevel() { return currentFe / (double) MAX_FE; }

    public long extractPower(long min, long max, boolean doExtract) {
        if (min < 0 || max < min || currentFe < min) return 0;
        long actualMax = Math.min(max, maxPowerExtracted());
        if (actualMax < min) return 0;
        int extracted = (int) Math.min(currentFe, actualMax);
        if (doExtract && extracted > 0) {
            transferJournal.record();
            currentFe -= extracted;
            markEnergyStateDirty();
        }
        return extracted;
    }

    protected boolean isFacingReceiver(Direction side) {
        return getFeReceiver(side) != null;
    }

    protected long getPowerToExtract(boolean doExtract) {
        EnergyStorage receiver = getFeReceiver(currentDirection);
        if (receiver == null) return 0;
        int offered = (int) Math.min(Integer.MAX_VALUE, Math.min(currentFe, maxPowerExtracted()));
        if (offered <= 0) return 0;
        int accepted = receiver.receiveEnergy(offered, true);
        accepted = Math.max(0, Math.min(offered, accepted));
        if (doExtract && accepted > 0) {
            transferJournal.record();
            currentFe -= accepted;
            markEnergyStateDirty();
        }
        return accepted;
    }

    protected void sendPower() {
        EnergyStorage receiver = getFeReceiver(currentDirection);
        if (receiver == null) return;
        int offered = (int) Math.min(Integer.MAX_VALUE, Math.min(currentFe, maxPowerExtracted()));
        if (offered <= 0) return;
        int accepted = receiver.receiveEnergy(offered, false);
        accepted = Math.max(0, Math.min(offered, accepted));
        if (accepted > 0) {
            transferJournal.record();
            currentFe -= accepted;
            markEnergyStateDirty();
        }
    }

    public boolean isPoweredTile(BlockEntity tile, Direction side) {
        if (tile == null) return false;
        if (tile.getClass() == getClass()) {
            TileDynamoMJ other = (TileDynamoMJ) tile;
            return other.currentDirection == currentDirection;
        }
        return getFeReceiver(tile, side) != null;
    }

    @Nullable
    private EnergyStorage getFeReceiver(BlockEntity tile, Direction side) {
        if (tile == null || level == null) return null;
        EnergyStorage receiver = PlatformStorage.energy(level, tile.getBlockPos(), side.getOpposite());
        return receiver != null && receiver.canReceive() ? receiver : null;
    }

    @Nullable
    private EnergyStorage getFeReceiver(Direction side) {
        TileDynamoMJ dynamo = this;
        BlockEntity next = null;
        for (int len = 0; len <= getMaxChainLength(); len++) {
            next = dynamo.getTileBuffer(side).getTile();
            if (next == null) return null;
            if (next instanceof TileDynamoMJ other) {
                if (other.currentDirection != side) return null;
                dynamo = other;
            } else {
                break;
            }
        }
        if (next == null || next instanceof TileDynamoMJ) return null;
        return getFeReceiver(next, side);
    }

    public net.minecraft.resources.Identifier typeId() { return BuildCraftContentIds.Engines.MJ_DYNAMO; }
    public MjAmount outputPerTick() { return MjAmount.ZERO; }
    public Optional<MjPort> mjPort(Direction side) {
        return side != currentDirection ? Optional.of(mjReceiver) : Optional.empty();
    }
    public Optional<MjPortDescriptor> mjPortDescriptor(Direction side) {
        if (side == currentDirection) return Optional.empty();
        return Optional.of(new MjPortDescriptor(net.minecraft.resources.Identifier.parse("buildcraft:mj"),
            Set.of(MjPortRole.CONSUMER, MjPortRole.CONNECTOR, MjPortRole.READABLE),
            MjAmount.ofMicro(getMjPerTick()), MjAmount.ZERO));
    }
    public Optional<ExternalEnergyPort> externalEnergyPort(Direction side) {
        return side == currentDirection ? Optional.of(api2FeOutputPort) : Optional.empty();
    }

    public long getMaxPower() { return MAX_MJ; }
    public long minPowerReceived() { return 0; }
    public long maxPowerReceived() { return 0; }
    public long maxPowerExtracted() { return MAX_FE / 10; }
    public float explosionRange() { return 4; }
    @Override protected int getMaxChainLength() { return 3; }
    public long getCurrentOutput() { return currentFe > 0 ? getFeGenerationRate() : 0; }

    public int getCurrentFe() { return currentFe; }
    public long getMjStored() { return mjBattery.getStored(); }
    public long getMjCapacity() { return mjBattery.getCapacity(); }
    public Direction getCurrentDirection() { return currentDirection; }
    public boolean isRedstonePowered() { return isRedstonePowered; }

    protected void writeData(BCValueOutput bcData) {
        CompoundTag tag = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.writeData(bcData);
        bcData.writeInt("currentFE", currentFe);
        tag.put("mj", mjBattery.serializeNBT(registries));
    }

    protected void readData(BCValueInput bcData) {
        HolderLookup.Provider registries = bcData.registries();
        super.readData(bcData);
        currentFe = Math.max(0, Math.min(MAX_FE, bcData.readInt("currentFE")));
        if (bcData.has("mj")) mjBattery.deserializeNBT(registries, bcData.readCompound("mj"));
        captureEnergyState();
    }

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER && (id == NET_GUI_DATA || id == NET_GUI_TICK)) {
            buffer.writeVarInt(currentFe);
            buffer.writeLong(mjBattery.getStored());
        }
    }

    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT && (id == NET_GUI_DATA || id == NET_GUI_TICK)) {
            currentFe = buffer.readVarInt();
            CompoundTag battery = new CompoundTag();
            battery.putLong("stored", buffer.readLong());
            mjBattery.deserializeNBT(battery);
        }
    }

    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand).copy();
        InteractionResult parent = super.onActivated(player, hand, hit);
        if (parent.consumesAction()) return parent;
        if (!held.isEmpty()) {
            if (EntityUtil.getWrenchHand(player) != null) return InteractionResult.PASS;
            if (held.getItem() instanceof IItemPipe) return InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buf -> buf.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    public Component getDisplayName() { return Component.translatable("block.buildcraftenergy.mj_dynamo"); }
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerDynamoMJ(id, inventory, invUpgrades, ContainerLevelAccess.create(level, worldPosition));
    }

    private static boolean isMjCapability(BlockCapability<?, ?> capability) {
        return capability == MjCapabilities.CAP_CONNECTOR
            || capability == MjCapabilities.CAP_RECEIVER
            || capability == MjCapabilities.CAP_REDSTONE_RECEIVER
            || capability == MjCapabilities.CAP_READABLE
            || capability == MjCapabilities.CAP_PASSIVE_PROVIDER;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction side) {
        if (side != currentDirection) {
            T mj = inputMjCaps.getCapability(capability, side);
            if (mj != null) return mj;
        } else if (isMjCapability(capability)) {
            return null;
        }
        return super.getCapability(capability, side);
    }

    private final class FeStorage implements EnergyStorage {
        public int receiveEnergy(int maxReceive, boolean simulate) { return 0; }
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = Math.min(Math.max(0, maxExtract), currentFe);
            if (!simulate && extracted > 0) {
                transferJournal.record();
                currentFe -= extracted;
                markEnergyStateDirty();
            }
            return extracted;
        }
        public int getEnergyStored() { return currentFe; }
        public int getMaxEnergyStored() { return MAX_FE; }
        public boolean canExtract() { return true; }
        public boolean canReceive() { return false; }
    }

    public EngineVisualType getVisualType() {
        return EngineVisualType.MJ_DYNAMO;
    }

}
