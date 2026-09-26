package buildcraft.energy.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.platform.ExternalEnergyPort;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.core.BCCoreItems;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.menu.ContainerEngineFE;
import buildcraft.lib.engine.EngineConnector;
import buildcraft.lib.engine.TileEngineBase_BC8;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.platform.storage.EnergyStorage;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.misc.CapUtil;

/** BuildCraft 8 FE Engine: consumes Forge Energy and produces MJ. */
public class TileEngineFE extends TileEngineBase_BC8 implements MenuProvider {
    public static final int MAX_FE = 10_000;
    public static final double HEAT_RATE = 0.06;
    public static final double COOLDOWN_RATE = 0.01;

    public static final Map<Item, Long> FE_UPGRADES = new LinkedHashMap<>();

    private int currentFe;
    private final buildcraft.lib.compat.transfer.TransferJournal<Integer> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(() -> currentFe, value -> currentFe = value,
            old -> markChunkDirty());
    public final ItemHandlerSimple invUpgrades;
    private final EnergyStorage feStorage = new FeStorage();
    private final ExternalEnergyPort api2FeInputPort = new ExternalEnergyPort() {
        public long insert(long offered, OperationMode mode) {
            int accepted = feStorage.receiveEnergy((int) Math.min(Integer.MAX_VALUE, Math.max(0L, offered)), mode == OperationMode.SIMULATE);
            return Math.max(0, accepted);
        }
        public long extract(long requested, OperationMode mode) { return 0; }
        public long stored() { return currentFe; }
        public long capacity() { return MAX_FE; }
        public boolean canInsert() { return true; }
        public boolean canExtract() { return false; }
    };

    public TileEngineFE(BlockPos pos, BlockState state) {
        super(BCEnergyBlocks.ENGINE_FE_TILE_BC8.get(), pos, state);
        invUpgrades = itemManager.addInvHandler(
            "upgrades", 4, (slot, stack) -> isValidUpgrade(stack), EnumAccess.NONE
        ).setLimitedInsertor(1);
        caps.addProvider(itemManager);
        caps.addEnergyStorage(side -> side != currentDirection ? feStorage : null, EnumPipePart.VALUES);
    }

    private static void ensureUpgradeMap() {
        if (!FE_UPGRADES.isEmpty()) return;
        FE_UPGRADES.put(BCCoreItems.GEAR_IRON.get(), MjAmount.MICRO_MJ_PER_MJ * 2);
        FE_UPGRADES.put(BCCoreItems.GEAR_GOLD.get(), MjAmount.MICRO_MJ_PER_MJ * 3);
    }

    private static boolean isValidUpgrade(ItemStack stack) {
        ensureUpgradeMap();
        return !stack.isEmpty() && FE_UPGRADES.containsKey(stack.getItem());
    }

    public int getCurrentFe() { return currentFe; }

    public static long getMjPerTick(IItemHandlerAdv upgrades) {
        ensureUpgradeMap();
        long value = MjAmount.MICRO_MJ_PER_MJ * 4;
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

    public static int getFeConsumptionRate(IItemHandlerAdv upgrades) {
        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        if (ratio <= 0) return 0;
        long required = getMjPerTick(upgrades) / ratio;
        if (required <= 0) return 1;
        return (int) Math.min(Integer.MAX_VALUE, required);
    }

    public int getFeConsumptionRate() {
        return getFeConsumptionRate(invUpgrades);
    }

    protected void writeData(BCValueOutput bcData) {
        super.writeData(bcData);
        bcData.writeInt("currentFE", currentFe);
    }

    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        currentFe = Math.max(0, Math.min(MAX_FE, bcData.readInt("currentFE")));
    }

    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT && (id == NET_GUI_DATA || id == NET_GUI_TICK)) currentFe = buffer.readVarInt();
    }

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER && (id == NET_GUI_DATA || id == NET_GUI_TICK)) buffer.writeVarInt(currentFe);
    }

    protected void burn() {
        if (currentFe <= 0 || !isRedstonePowered) return;
        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        if (ratio <= 0) return;
        int consumedFe = Math.min(currentFe, getFeConsumptionRate());
        long generatedMj = consumedFe * ratio;
        if (power + generatedMj >= getMaxPower()) return;
        currentOutput = generatedMj;
        addPower(generatedMj);
        transferJournal.record();
        currentFe -= consumedFe;
        heat = Math.min(200, heat + HEAT_RATE);
        markChunkDirty();
    }

    public void updateHeatLevel() {
        if (heat > MIN_HEAT) heat -= COOLDOWN_RATE;
        if (heat < MIN_HEAT) heat = MIN_HEAT;
        getPowerStage();
    }

    public boolean isBurning() { return currentFe > 0 && isRedstonePowered; }
    public double getPistonSpeed() {
        return switch (getPowerStage()) {
            case BLUE -> 0.04;
            case GREEN -> 0.05;
            case YELLOW -> 0.06;
            case RED -> 0.07;
            default -> 0;
        };
    }
    @Nonnull @Override protected IMjConnector createConnector() { return new EngineConnector(false); }
    public Optional<ExternalEnergyPort> externalEnergyPort(Direction side) {
        return side != currentDirection ? Optional.of(api2FeInputPort) : Optional.empty();
    }

    public long getMaxPower() { return 1000 * MjAmount.MICRO_MJ_PER_MJ; }
    public long maxPowerReceived() { return 200 * MjAmount.MICRO_MJ_PER_MJ; }
    public long maxPowerExtracted() { return 500 * MjAmount.MICRO_MJ_PER_MJ; }
    public float explosionRange() { return 4; }
    @Override protected int getMaxChainLength() { return 4; }
    public long getCurrentOutput() { return currentFe > 0 ? getMjPerTick() : 0; }

    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack current = player.getItemInHand(hand).copy();
        InteractionResult parent = super.onActivated(player, hand, hit);
        if (parent.consumesAction()) return parent;
        if (!current.isEmpty()) {
            if (EntityUtil.getWrenchHand(player) != null) return InteractionResult.PASS;
            if (current.getItem() instanceof IItemPipe) return InteractionResult.PASS;
        }
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buf -> buf.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    public Component getDisplayName() { return Component.translatable("block.buildcraftcore.engine_fe"); }
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerEngineFE(id, inventory, invUpgrades, ContainerLevelAccess.create(level, worldPosition));
    }


    private final class FeStorage implements EnergyStorage {
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (maxReceive <= 0) return 0;
            int accepted = Math.min(maxReceive, MAX_FE - currentFe);
            if (!simulate && accepted > 0) { transferJournal.record(); currentFe += accepted; markChunkDirty(); }
            return accepted;
        }
        public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        public int getEnergyStored() { return currentFe; }
        public int getMaxEnergyStored() { return MAX_FE; }
        public boolean canExtract() { return false; }
        public boolean canReceive() { return true; }
    }
}
