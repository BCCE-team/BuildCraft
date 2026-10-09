//? source if >=1.21.11
package buildcraft.factory.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.block.ICustomRotationHandler;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.api.v2.recipe.HeatExchangeRecipeDefinition;
import buildcraft.lib.recipe.MachineRecipeApiBridge;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.block.BlockHeatExchange;
import buildcraft.factory.client.gui.MenuHeatExchange;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.block.VanillaRotationHandlers;
import buildcraft.lib.cap.CapabilityHelper;
import buildcraft.lib.fluid.FluidSmoother;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.fluid.TankManager;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.PlatformStorage;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.neoforge.items.ItemStackHandler;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.NbtCompat;

public class TileHeatExchange extends TileBC_Neptune implements IDebuggable, MenuProvider{

    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("HeatExchanger");
    public static final int NET_ID_CHANGE_SECTION = IDS.allocId("CHANGE_SECTION");
    public static final int NET_ID_TANK_IN = IDS.allocId("TANK_IN");
    public static final int NET_ID_TANK_OUT = IDS.allocId("TANK_OUT");
    public static final int NET_ID_STATE = IDS.allocId("STATE");

    /** the maximum amount of fluid that can be transferred per tick for each number of middle sections. numbers need to
     * be divisors of 1000 */
    private static final int[] FLUID_MULT = { 5, 10, 20 };

    protected TankContainerData tankData = null;
    protected Tank[] tanks = new Tank[4];
    protected DataSlot stateData = new DataSlot(){
        public int get() {
            return 0;
        }
        public void set(int index) {}
    };

    public TileHeatExchange(BlockPos pos, BlockState state) {
        super(BCFactoryBlocks.ENTITYBLOCKHEATEXCHANGE.get(), pos, state);
    }

    public IdAllocator getIdAllocator() {
        return IDS;
    }

    protected ExchangeSection section;
    private boolean checkNeighbours = true;


    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        CompoundTag nbtSection = bcData.readCompound("section");
        if (!nbtSection.isEmpty()) {
            if (NbtCompat.getBoolean(nbtSection, "start")) {
                section = new ExchangeSectionStart(this, nbtSection);
            } else {
                section = new ExchangeSectionEnd(this, nbtSection);
            }
        }
        checkNeighbours = true;
    }

    public void onLoad() {
//    	checkNeighbours = true;
        super.onLoad();
    }

    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        super.writeData(bcData);
        if (section != null) {
            nbt.put("section", section.writeToNbt());
        }
    }

    public void update() {
        if (checkNeighbours) {
            checkNeighbours = false;
//            if(!level.isClientSide())
//            BCLog.logger.debug("TileHeatExchange:close flag at "+worldPosition);
            Deque<TileHeatExchange> exchangers = findAdjacentExchangers();
            if (level.isClientSide()) {
                // Find the start + end sections and link them up
//            	BCLog.logger.debug("TileHeatExchange:client tick for "+worldPosition);
                if (exchangers.size() > 2) {
                    TileHeatExchange start = exchangers.getFirst();
                    TileHeatExchange end = exchangers.getLast();
                    if (start.isStart() && end.isEnd()) {
                        ((ExchangeSectionStart) start.section).endSection = (ExchangeSectionEnd) end.section;
                    }
                }
                for (TileHeatExchange tile : exchangers) {
                    tile.redrawBlock();
                }
            } else {
                if (exchangers.isEmpty()) {
                    // Something went wrong when searching
                    // (as normally this deque will contain this)
                    checkNeighbours = true;
                } else if (exchangers.size() < 3) {
                    for (TileHeatExchange tile : exchangers) {
                        tile.removeSection();
                    }
                } else if (exchangers.size() > 5) {
                    for (TileHeatExchange tile : exchangers) {
                        tile.removeSection();
                    }
                } else {
                    ExchangeSectionStart sectionStart = null;
                    ExchangeSectionEnd sectionEnd = null;
                    for (TileHeatExchange exchange : exchangers) {
                        // For efficiency, only run this check once.
                        exchange.checkNeighbours = false;
                        if (exchange.section instanceof ExchangeSectionStart) {
                            if (sectionStart == null) {
                                sectionStart = (ExchangeSectionStart) exchange.section;
                            } else {
                                mergeDuplicateSection(sectionStart, (ExchangeSectionStart) exchange.section, exchange);
                            }
                        } else if (exchange.section instanceof ExchangeSectionEnd) {
                            if (sectionEnd == null) {
                                sectionEnd = (ExchangeSectionEnd) exchange.section;
                            } else {
                                mergeDuplicateSection(sectionEnd, (ExchangeSectionEnd) exchange.section, exchange);
                            }
                        }
                        exchange.clearSectionReference();
                        exchange.tankData = null;
                        exchange.tanks[0] = null;
                        exchange.tanks[1] = null;
                        exchange.tanks[2] = null;
                        exchange.tanks[3] = null;
                    }
                    if (sectionStart == null) {
                        sectionStart = new ExchangeSectionStart(exchangers.getFirst());
                    }
                    if (sectionEnd == null) {
                        sectionEnd = new ExchangeSectionEnd(exchangers.getLast());
                    }
                    sectionStart.endSection = sectionEnd;
                    sectionStart.middleCount = exchangers.size() - 2;
                    exchangers.getFirst().setSection(sectionStart);
                    exchangers.getLast().setSection(sectionEnd);

                    TankContainerData structureTankData = new TankContainerData(
                        sectionStart.tankInput,
                        sectionStart.tankOutput,
                        sectionEnd.tankInput,
                        sectionEnd.tankOutput
                    );
                    TileHeatExchange startTile = exchangers.getFirst();
                    TileHeatExchange endTile = exchangers.getLast();
                    startTile.tankData = structureTankData;
                    endTile.tankData = structureTankData;
                    startTile.tanks[0] = sectionStart.tankInput;
                    startTile.tanks[1] = sectionStart.tankOutput;
                    startTile.tanks[2] = sectionEnd.tankInput;
                    startTile.tanks[3] = sectionEnd.tankOutput;
                    endTile.tanks[0] = sectionStart.tankInput;
                    endTile.tanks[1] = sectionStart.tankOutput;
                    endTile.tanks[2] = sectionEnd.tankInput;
                    endTile.tanks[3] = sectionEnd.tankOutput;

                    for (TileHeatExchange exchange : exchangers) {
                        exchange.sendNetworkUpdate(NET_ID_CHANGE_SECTION);
                        exchange.syncBlockState();
                    }
                }

            }

        }
        if (section != null) {
            section.tick();
        }
    }

    /**
     * Preserves fluid when neighbouring exchanger structures collapse into one. Compatible contents are merged into
     * the retained section; anything that cannot fit is emitted through the normal fluid-drop path instead of being
     * silently lost when the duplicate section object is discarded.
     */
    private static void mergeDuplicateSection(
        ExchangeSection retained, ExchangeSection duplicate, TileHeatExchange duplicateTile
    ) {
        mergeTankContents(retained.tankInput, duplicate.tankInput);
        mergeTankContents(retained.tankOutput, duplicate.tankOutput);

        NonNullList<ItemStack> overflowDrops = NonNullList.create();
        duplicate.tankManager.addDrops(overflowDrops);
        if (!overflowDrops.isEmpty()) {
            InventoryUtil.dropAll(duplicateTile.getLevel(), duplicateTile.getBlockPos(), overflowDrops);
        }
    }

    private static void mergeTankContents(Tank target, Tank source) {
        FluidStack sourceFluid = source.getFluid();
        if (sourceFluid.isEmpty()) {
            return;
        }
        int accepted = target.fillInternal(sourceFluid, FluidAction.SIMULATE);
        if (accepted <= 0) {
            return;
        }
        FluidStack transfer = sourceFluid.copyWithAmount(accepted);
        int filled = target.fillInternal(transfer, FluidAction.EXECUTE);
        if (filled > 0) {
            source.drainInternal(filled, FluidAction.EXECUTE);
        }
    }

    private void removeSection() {
        tankData = null;
        tanks[0] = null;
        tanks[1] = null;
        tanks[2] = null;
        tanks[3] = null;
        if (section == null) {
            syncBlockState();
            return;
        }
//        BCLog.logger.info("[] removing section...");
        NonNullList<ItemStack> list = NonNullList.create();
        section.tankManager.addDrops(list);
        InventoryUtil.dropAll(getLevel(), getBlockPos(), list);
        clearSectionReference();
        sendNetworkUpdate(NET_ID_CHANGE_SECTION);
        syncBlockState();
    }

    private void clearSectionReference() {
        if (section != null) {
            section = null;
            invalidateCapabilityCache();
        }
    }

    private void invalidateCapabilityCache() {
        if (level != null) {
            level.invalidateCapabilities(worldPosition);
        }
    }

    private Deque<TileHeatExchange> findAdjacentExchangers() {
        Direction thisFacing = getFacing();
        if (thisFacing == null) {
            // Odd. This means that we are getting a property from a different block
            return new ArrayDeque<>();
        }
        Direction dirToStart = thisFacing.getClockWise();
        Direction dirToEnd = thisFacing.getCounterClockWise();
        Deque<TileHeatExchange> exchangers = new ArrayDeque<>();
        exchangers.add(this);
        for (int i = 1; i < 6; i++) {
            BlockEntity neighbour = getLocalTile(worldPosition.offset(dirToStart.getUnitVec3i().multiply(i)));
            if (neighbour instanceof TileHeatExchange) {
                TileHeatExchange other = (TileHeatExchange) neighbour;
                if (other.getFacing() != thisFacing) {
                    break;
                }
                exchangers.addFirst(other);
            } else {
                break;
            }
        }
        for (int i = 1; i < 6; i++) {
            BlockEntity neighbour = getLocalTile(worldPosition.offset(dirToEnd.getUnitVec3i().multiply(i)));
            if (neighbour instanceof TileHeatExchange) {
                TileHeatExchange other = (TileHeatExchange) neighbour;
                if (other.getFacing() != thisFacing) {
                    break;
                }
                exchangers.addLast(other);
            } else {
                break;
            }
        }
        return exchangers;
    }

    private void setSection(ExchangeSection section) {
        if (this.section != section) {
            this.section = section;
            invalidateCapabilityCache();
            section.setTile(this);
            sendNetworkUpdate(NET_ID_CHANGE_SECTION);
            syncBlockState();
        }
    }

    private void syncBlockState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = getCurrentStateForBlock(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get());
        if (state == null) {
            return;
        }
        BlockState actual = BlockHeatExchange.withActualState(state, level, worldPosition);
        if (actual != state) {
            level.setBlock(worldPosition, actual, Block.UPDATE_ALL);
        }
    }

    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_ID_CHANGE_SECTION, buffer, side, ctx);
            } else if (id == NET_ID_CHANGE_SECTION) {
                if (buffer.readBoolean()) {
                    boolean start = buffer.readBoolean();
                    if (start) {
                        section = section instanceof ExchangeSectionStart ? section : new ExchangeSectionStart(this);
                    } else {
                        section = section instanceof ExchangeSectionEnd ? section : new ExchangeSectionEnd(this);
                    }
                    invalidateCapabilityCache();
                    section.readPayload(NET_ID_CHANGE_SECTION, buffer, side, ctx);
                } else {
                    clearSectionReference();
                }
                checkNeighbours = true;
            } else if (section != null) {
                section.readPayload(id, buffer, side, ctx);
            }
        }
    }

    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
//    	BCLog.logger.debug("TileHeatExchange:send message at "+worldPosition+" "+checkNeighbours);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_ID_CHANGE_SECTION, buffer, side);
            } else if (id == NET_ID_CHANGE_SECTION) {
                if (section == null) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    buffer.writeBoolean(section instanceof ExchangeSectionStart);
                    section.writePayload(id, buffer, side);
                }
            } else if (section != null) {
                section.writePayload(id, buffer, side);
            }
        }
    }

    public AABB getRenderBoundingBox() {
        if (section instanceof ExchangeSectionStart start) {
            Direction facing = getFacing();
            if (facing != null) {
                Direction towardEnd = facing.getCounterClockWise();
                BlockPos endPos = worldPosition.relative(towardEnd, Math.max(1, start.middleCount + 1));
                double minX = Math.min(worldPosition.getX(), endPos.getX());
                double minY = Math.min(worldPosition.getY(), endPos.getY());
                double minZ = Math.min(worldPosition.getZ(), endPos.getZ());
                double maxX = Math.max(worldPosition.getX(), endPos.getX()) + 1;
                double maxY = Math.max(worldPosition.getY(), endPos.getY()) + 1;
                double maxZ = Math.max(worldPosition.getZ(), endPos.getZ()) + 1;
                return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
        return new AABB(worldPosition);
    }

    @Nullable
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction facing) {
        if (section != null) {
            T value = section.caps.getCapability(capability, facing);
            if (value != null) {
                return value;
            }
        }
        return super.getCapability(capability, facing);
    }

    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (section != null&&FluidUtilBC.onTankActivated(player, worldPosition, hand, section.tankManager)) {
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer && tankData != null && level.getBlockEntity(worldPosition) instanceof TileHeatExchange tile) {
            serverPlayer.openMenu(tile, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return InteractionResult.SUCCESS;
    }

    public void setRemoved() {
        checkNeighbours = true;
        super.setRemoved();
    }

    public void clearRemoved() {
        if (section instanceof ExchangeSectionStart) {
            ((ExchangeSectionStart) section).endSection = null;
        }
        super.clearRemoved();
    }

    public void neighbourBlockChanged(BlockState state, BlockPos neighbor, boolean harvest) {
        if (neighbor.getY() != worldPosition.getY()) {
            // Heat exchange tiles can only be horizontally adjacent
            return;
        }
        checkNeighbours = true;
        syncBlockState();
    }

    public void onNeighbourBlockChanged(BlockState state, BlockPos nehighbour) {
    }

    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        super.addDrops(toDrop, fortune);
        if (section != null) {
            section.tankManager.addDrops(toDrop);
        }
    }

    /** Called by {@link Block#rotateBlock(Level, BlockPos, Direction)} and
     * {@link ICustomRotationHandler#attemptRotation(Level, BlockPos, BlockState, Direction)} when the
     * {@link Direction} is {@link Direction#UP} or {@link Direction#DOWN}.
     * <p>
     * If this exchanger is not part of a larger structure then this will rotate this block 90 degrees. If this is part
     * of a larger structure then all adjacent heat exchangers will be rotated 180 degrees to swap the start and end
     * blocks. */
    public void rotate(Rotation axis) {
        Direction thisFacing = getFacing();
        if (thisFacing == null || level == null || level.isClientSide()) {
            return;
        }
        Deque<TileHeatExchange> exchangers = findAdjacentExchangers();
        if (exchangers.size() == 1) {
            BlockState state = getBlockState();
            level.setBlock(
                getBlockPos(),
                BlockHeatExchange.withActualState(
                    state.setValue(BlockHeatExchange.PROP_FACING, VanillaRotationHandlers.ROTATE_HORIZONTAL.next(thisFacing)),
                    level,
                    worldPosition
                ),
                Block.UPDATE_ALL
            );
            checkNeighbours = true;
            markChunkDirty();
        } else {
            ExchangeSectionStart start = null;
            ExchangeSectionEnd end = null;
            for (TileHeatExchange exchange : exchangers) {
                if (exchange.section instanceof ExchangeSectionStart) {
                    start = (ExchangeSectionStart) exchange.section;
                } else if (exchange.section instanceof ExchangeSectionEnd) {
                    end = (ExchangeSectionEnd) exchange.section;
                }
                exchange.clearSectionReference();
                exchange.tankData = null;
                exchange.tanks[0] = null;
                exchange.tanks[1] = null;
                exchange.tanks[2] = null;
                exchange.tanks[3] = null;
                BlockState exchangeState = exchange.getBlockState()
                    .setValue(BlockHeatExchange.PROP_FACING, thisFacing.getOpposite());
                level.setBlock(
                    exchange.getBlockPos(),
                    BlockHeatExchange.withActualState(exchangeState, level, exchange.getBlockPos()),
                    Block.UPDATE_ALL
                );
                exchange.checkNeighbours = true;
                exchange.markChunkDirty();
            }
            if (start != null) {
                TileHeatExchange tile = exchangers.getLast();
                tile.section = start;
                tile.invalidateCapabilityCache();
                start.setTile(tile);
                tile.markChunkDirty();
                tile.sendNetworkUpdate(NET_ID_CHANGE_SECTION);
            }

            if (end != null) {
                TileHeatExchange tile = exchangers.getFirst();
                tile.section = end;
                tile.invalidateCapabilityCache();
                end.setTile(tile);
                tile.markChunkDirty();
                tile.sendNetworkUpdate(NET_ID_CHANGE_SECTION);
            }

            for (TileHeatExchange exchange : exchangers) {
                exchange.syncBlockState();
            }
        }

        SoundUtil.playSlideSound(getLevel(), getBlockPos());
    }


    public boolean isStart() {
        return section instanceof ExchangeSectionStart;
    }

    public boolean isEnd() {
        return section instanceof ExchangeSectionEnd;
    }

    public ExchangeSection getSection() {
        return section;
    }

    @Nullable
    public FluidStackInterp getTankInRenderInfo(double part) {
        return section == null ? null : section.smoothedTankInput.getFluidForRender(part);
    }

    @Nullable
    public FluidStackInterp getTankOutRenderInfo(double part) {
        return section == null ? null : section.smoothedTankOutput.getFluidForRender(part);
    }

    @Nullable
    Direction getFacing() {
        BlockState state = getCurrentStateForBlock(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get());
        if (state == null) {
            return null;
        }
        return state.getValue(BlockBCBase_Neptune.PROP_FACING);
    }

    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        if (section == null) {
            left.add("section = null");
        } else {
            left.add("section = " + (section instanceof ExchangeSectionStart ? "start" : "end"));
            section.getDebugInfo(left, right, side);
        }
    }

    static abstract class ExchangeSection {
        final Tank tankInput, tankOutput;
        final TankManager tankManager;
        public final FluidSmoother smoothedTankInput, smoothedTankOutput;
        public final CapabilityHelper caps = new CapabilityHelper();
        private TileHeatExchange tile;

        ExchangeSection(TileHeatExchange tile) {
            tankInput = new Tank("input", 2 * FluidType.BUCKET_VOLUME, tile);
            tankOutput = new Tank("output", 2 * FluidType.BUCKET_VOLUME, tile);
            tankOutput.setCanFill(false);
            tankManager = new TankManager(tankOutput, tankInput);
            smoothedTankInput = createFluidSmoother(tankInput, NET_ID_TANK_IN);
            smoothedTankOutput = createFluidSmoother(tankOutput, NET_ID_TANK_OUT);
            this.setTile(tile);
        }

        ExchangeSection(TileHeatExchange tile, CompoundTag nbt) {
            this(tile);
            tankInput.readFromNBT(NbtCompat.getCompound(nbt, "input"));
            tankOutput.readFromNBT(NbtCompat.getCompound(nbt, "output"));
        }

        FluidSmoother createFluidSmoother(Tank tank, int netId) {
            return new FluidSmoother(w -> getTile().createAndSendMessage(netId, w), tank);
        }

        CompoundTag writeToNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.put("input", tankInput.serializeNBT());
            nbt.put("output", tankOutput.serializeNBT());
            return nbt;
        }

        void tick() {
            Level world = getTile().level;
            smoothedTankInput.tick(world);
            smoothedTankOutput.tick(world);
        }

        void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
            if (side == BCNetworkSide.CLIENT) {
                if (id == NET_ID_CHANGE_SECTION) {
                    readPayload(NET_ID_TANK_IN, buffer, side, ctx);
                    readPayload(NET_ID_TANK_OUT, buffer, side, ctx);
                    smoothedTankInput.resetSmoothing(getTile().level);
                    smoothedTankOutput.resetSmoothing(getTile().level);
                } else if (id == NET_ID_TANK_IN) {
                    smoothedTankInput.handleMessage(getTile().level, buffer);
                } else if (id == NET_ID_TANK_OUT) {
                    smoothedTankOutput.handleMessage(getTile().level, buffer);
                }
            } else if (side == BCNetworkSide.SERVER) {

            }
        }

        void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
            if (side == BCNetworkSide.SERVER) {
                if (id == NET_ID_CHANGE_SECTION) {
                    writePayload(NET_ID_TANK_IN, buffer, side);
                    writePayload(NET_ID_TANK_OUT, buffer, side);
                } else if (id == NET_ID_TANK_IN) {
                    smoothedTankInput.writeInit(buffer);
                } else if (id == NET_ID_TANK_OUT) {
                    smoothedTankOutput.writeInit(buffer);
                }
            } else if (side == BCNetworkSide.CLIENT) {

            }
        }

        void getDebugInfo(List<String> left, List<String> right, Direction side) {
            left.add("tank_input = " + tankInput.getDebugString());
            left.add("tank_output = " + tankOutput.getDebugString());
            left.add("smoothed_input: ");
            smoothedTankInput.getDebugInfo(left, right, side);
            left.add("smoothed_output: ");
            smoothedTankOutput.getDebugInfo(left, right, side);
        }

        public TileHeatExchange getTile() {
            return tile;
        }

        public void setTile(TileHeatExchange tile) {
            this.tile = tile;
            tankInput.setBlockEntity(tile);
            tankOutput.setBlockEntity(tile);
        }

    }

    public static class ExchangeSectionStart extends ExchangeSection {

        private ExchangeSectionEnd endSection;
        public int middleCount;
        private int progress = 0;
        private int progressLast = 0;
        private EnumProgressState progressState = EnumProgressState.OFF;
        private EnumProgressState lastSentState = EnumProgressState.OFF;
        private int inputCoolantAmountCharge = 0;
        private int inputHeatantAmountCharge = 0;

        {
            tankInput.setFilter(this::isHeatant);
            caps.addFluidStorage(tankInput, EnumPipePart.DOWN);
            caps.addFluidStorage(this::getTankForSide, EnumPipePart.HORIZONTALS);
        }

        ExchangeSectionStart(TileHeatExchange tile) {
            super(tile);
        }

        ExchangeSectionStart(TileHeatExchange tile, CompoundTag nbt) {
            super(tile, nbt);
            inputCoolantAmountCharge = NbtCompat.getInt(nbt, "coolantCharge");
            inputHeatantAmountCharge = NbtCompat.getInt(nbt, "heatantCharge");
        }

        CompoundTag writeToNbt() {
            CompoundTag nbt = super.writeToNbt();
            nbt.putBoolean("start", true);
            nbt.putInt("coolantCharge", inputCoolantAmountCharge);
            nbt.putInt("heatantCharge", inputHeatantAmountCharge);
            return nbt;
        }

        void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
            super.readPayload(id, buffer, side, ctx);
            if (side == BCNetworkSide.CLIENT) {
                if (id == NET_ID_CHANGE_SECTION) {
                    middleCount = buffer.readUnsignedByte();
                } else if (id == NET_ID_STATE) {
                    progressState = buffer.readEnum(EnumProgressState.class);
                }
            }
        }

        void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
            super.writePayload(id, buffer, side);
            if (side == BCNetworkSide.SERVER) {
                if (id == NET_ID_CHANGE_SECTION) {
                    buffer.writeByte(middleCount);
                } else if (id == NET_ID_STATE) {
                    buffer.writeEnum(progressState);
                }
            }
        }

        public ExchangeSectionEnd getEndSection() {
            return endSection;
        }

        public EnumProgressState getProgressState() {
            return progressState;
        }

        public double getProgress(float partialTicks) {
            return MathUtil.interp(partialTicks, progressLast, progress) / 120.0;
        }

        private boolean isHeatant(FluidStack fluid) {
            return MachineRecipeApiBridge.findHeating(fluid) != null;
        }

        private Tank getTankForSide(Direction side) {
            Direction thisFacing = getTile().getFacing();
            if (thisFacing == null || side != thisFacing.getClockWise()) {
                return null;
            }
            return tankOutput;
        }

        void tick() {
            super.tick();
            updateProgress();
            if (getTile().level.isClientSide()) {
                spawnParticles();
                return;
            }
            if (endSection != null) {
                craft();
            } else if (progressState != EnumProgressState.OFF) {
                progressState = EnumProgressState.STOPPING;
            }
            output();
            if (progressState != lastSentState) {
                lastSentState = progressState;
                getTile().sendNetworkUpdate(NET_ID_STATE);
            }
        }

        private void updateProgress() {
            progressLast = progress;
            switch (progressState) {
                case STOPPING: {
                    progress--;
                    if (progress <= 0) {
                        progress = 0;
                        progressState = EnumProgressState.OFF;
                    }
                    return;
                }
                case PREPARING:
                case RUNNING: {
                    int lag = 120;
                    progress++;
                    if (progress >= lag) {
                        progress = lag;
                        progressState = EnumProgressState.RUNNING;
                    }
                    return;
                }
                default: {
                    return;
                }
            }
        }

        private void craft() {
            Tank c_in = endSection.tankInput;
            Tank c_out = tankOutput;
            Tank h_in = tankInput;
            Tank h_out = endSection.tankOutput;
            HeatExchangeRecipeDefinition c_recipe = MachineRecipeApiBridge.findCooling(c_in.getFluid());
            HeatExchangeRecipeDefinition h_recipe = MachineRecipeApiBridge.findHeating(h_in.getFluid());
            if (h_recipe == null || c_recipe == null) {
                progressState = EnumProgressState.STOPPING;
                return;
            }
            if (c_recipe.heatFrom() <= h_recipe.heatFrom()) {
                progressState = EnumProgressState.STOPPING;
                return;
            }
            int c_diff = c_recipe.heatFrom() - c_recipe.heatTo();
            int h_diff = h_recipe.heatTo() - h_recipe.heatFrom();
            if (h_diff < 1 || c_diff < 1) {
                throw new IllegalStateException("Invalid recipe " + c_recipe + ", " + h_recipe);
            }
            if (middleCount < 1 || middleCount > FLUID_MULT.length) {
                progressState = EnumProgressState.STOPPING;
                return;
            }

            int maxAmount = FLUID_MULT[middleCount - 1];
            ExchangeAmounts amounts = findExchangeAmounts(
                c_recipe, c_in, c_out, c_diff,
                h_recipe, h_in, h_out, h_diff,
                maxAmount
            );
            if (amounts == null) {
                progressState = EnumProgressState.STOPPING;
                return;
            }

            FluidStack c_in_f = setAmount(c_in.getFluid(), amounts.coolingInput);
            FluidStack c_out_f = recipeOutputForInput(c_recipe, amounts.coolingInput);
            FluidStack h_in_f = setAmount(h_in.getFluid(), amounts.heatingInput);
            FluidStack h_out_f = recipeOutputForInput(h_recipe, amounts.heatingInput);

            if (progressState == EnumProgressState.OFF) {
                progressState = EnumProgressState.PREPARING;
            } else if (progressState == EnumProgressState.RUNNING) {
                fill(c_out, c_out_f);
                drain(c_in, c_in_f);

                fill(h_out, h_out_f);
                drain(h_in, h_in_f);
            }
        }

        /**
         * Finds an exact integer-mB exchange. Heat is conserved as inputAmount * heatDelta on both sides and each
         * recipe output is scaled by its own input/output ratio. maxAmount remains the per-side throughput ceiling.
         */
        @Nullable
        private static ExchangeAmounts findExchangeAmounts(
            HeatExchangeRecipeDefinition coolingRecipe, Tank coolingInputTank, Tank coolingOutputTank, int coolingDelta,
            HeatExchangeRecipeDefinition heatingRecipe, Tank heatingInputTank, Tank heatingOutputTank, int heatingDelta,
            int maxAmount
        ) {
            for (int coolingInput = maxAmount; coolingInput > 0; coolingInput--) {
                long heat = (long) coolingInput * coolingDelta;
                if (heat % heatingDelta != 0) {
                    continue;
                }
                long heatingInputLong = heat / heatingDelta;
                if (heatingInputLong <= 0 || heatingInputLong > maxAmount || heatingInputLong > Integer.MAX_VALUE) {
                    continue;
                }
                int heatingInput = (int) heatingInputLong;

                FluidStack coolingInputStack = setAmount(coolingInputTank.getFluid(), coolingInput);
                FluidStack heatingInputStack = setAmount(heatingInputTank.getFluid(), heatingInput);
                FluidStack coolingOutputStack = recipeOutputForInput(coolingRecipe, coolingInput);
                FluidStack heatingOutputStack = recipeOutputForInput(heatingRecipe, heatingInput);
                if (coolingOutputStack == INVALID_RECIPE_AMOUNT || heatingOutputStack == INVALID_RECIPE_AMOUNT) {
                    continue;
                }
                if (drainableAmount(coolingInputTank, coolingInputStack) != coolingInput
                    || drainableAmount(heatingInputTank, heatingInputStack) != heatingInput) {
                    continue;
                }
                if (!canFillExactly(coolingOutputTank, coolingOutputStack)
                    || !canFillExactly(heatingOutputTank, heatingOutputStack)) {
                    continue;
                }
                return new ExchangeAmounts(coolingInput, heatingInput);
            }
            return null;
        }

        private static final FluidStack INVALID_RECIPE_AMOUNT = FluidStack.EMPTY;

        /** Returns null for consumed output and INVALID_RECIPE_AMOUNT when this integer-mB slice cannot preserve ratio. */
        private static FluidStack recipeOutputForInput(HeatExchangeRecipeDefinition recipe, int inputAmount) {
            if (recipe == null || recipe.output().isEmpty()) {
                return null;
            }
            long recipeInput = recipe.input().amount().milliBuckets();
            long recipeOutput = recipe.output().amount().milliBuckets();
            if (recipeInput <= 0 || recipeOutput <= 0 || recipeOutput > Long.MAX_VALUE / inputAmount) {
                return INVALID_RECIPE_AMOUNT;
            }
            long scaledNumerator = recipeOutput * inputAmount;
            if (scaledNumerator % recipeInput != 0) {
                return INVALID_RECIPE_AMOUNT;
            }
            long scaledOutput = scaledNumerator / recipeInput;
            if (scaledOutput <= 0 || scaledOutput > Integer.MAX_VALUE) {
                return INVALID_RECIPE_AMOUNT;
            }
            FluidStack output = FuelApiBridge.stackOfVariant(recipe.output().requireVariant(), (int) scaledOutput);
            return output.isEmpty() ? INVALID_RECIPE_AMOUNT : output;
        }

        private static boolean canFillExactly(Tank tank, FluidStack fluid) {
            return fluid == null || tank.fillInternal(fluid, FluidAction.SIMULATE) == fluid.getAmount();
        }

        private static final class ExchangeAmounts {
            final int coolingInput;
            final int heatingInput;

            ExchangeAmounts(int coolingInput, int heatingInput) {
                this.coolingInput = coolingInput;
                this.heatingInput = heatingInput;
            }
        }

        private void spawnParticles() {
            if (progressState == EnumProgressState.RUNNING) {
                ExchangeSectionEnd end = endSection;
                if (end == null) {
                    return;
                }
                Vec3 from = VecUtil.convertCenter(getTile().getBlockPos());
                FluidStack c_in_f = end.smoothedTankInput.getFluidForRender();
                if (c_in_f != null && c_in_f.getFluid() == Fluids.LAVA) {
                    Direction facing = getTile().getFacing();
                    if (facing != null) {
                        spewForth(from, facing.getClockWise(), ParticleTypes.LARGE_SMOKE);
                    }
                }

                FluidStack h_in_f = smoothedTankInput.getFluidForRender();
                from = VecUtil.convertCenter(end.getTile().getBlockPos());
                if (h_in_f != null && h_in_f.getFluid() == Fluids.WATER) {
                    Direction dir = Direction.UP;
                    spewForth(from, dir, ParticleTypes.CLOUD);
                }
            }
        }

        private void spewForth(Vec3 from, Direction dir, ParticleOptions particle) {
            Vec3 vecDir = Vec3.atLowerCornerOf(dir.getUnitVec3i());
            from = from.add(vecDir);

            double x = from.x;
            double y = from.y;
            double z = from.z;

            Vec3 motion = VecUtil.scale(vecDir, 0.4);
            Minecraft mc = Minecraft.getInstance();
            ParticleStatus particleType = mc.options.particles().get();
            Level w = getTile().getLevel();
            if (particleType == ParticleStatus.MINIMAL || w == null) {
                return;
            }
            int particleCount = particleType == ParticleStatus.ALL ? 5 : 2;
            for (int i = 0; i < particleCount; i++) {
                double dx = motion.x + (Math.random() - 0.5) * 0.1;
                double dy = motion.y + (Math.random() - 0.5) * 0.1;
                double dz = motion.z + (Math.random() - 0.5) * 0.1;
                double interp = i / (double) particleCount;
                x -= dx * interp;
                y -= dy * interp;
                z -= dz * interp;

                w.addParticle(particle, x, y, z, dx, dy, dz);
            }
        }

        private void output() {
            FluidStorage<FluidStack> thisOut = getFluidAutoOutputTarget();
            FluidUtilBC.moveStorage(tankOutput, thisOut, FluidType.BUCKET_VOLUME);

            if (endSection != null) {
                FluidStorage<FluidStack> endOut = endSection.getFluidAutoOutputTarget();
                FluidUtilBC.moveStorage(endSection.tankOutput, endOut, 1000);
            }
        }

        private static FluidStack setAmount(FluidStack fluid, int mult) {
            if (fluid == null) {
                return null;
            }
            return fluid.copyWithAmount(mult);
        }

        private static int drainableAmount(Tank t, FluidStack fluid) {
            FluidStack f2 = t.drainInternal(fluid, FluidAction.SIMULATE);
            return f2 == null ? 0 : f2.getAmount();
        }

        private static void fill(Tank t, FluidStack fluid) {
            if (fluid == null) {
                return;
            }
            int a = t.fillInternal(fluid, FluidAction.EXECUTE);
            if (a != fluid.getAmount()) {
                String err = "Buggy transition! Failed to fill " + fluid.getFluid();
                throw new IllegalStateException(err + " x " + fluid.getAmount() + " into " + t);
            }
        }

        private static void drain(Tank t, FluidStack fluid) {
            FluidStack f2 = t.drainInternal(fluid, FluidAction.EXECUTE);
            if (f2 == null || f2.getAmount() != fluid.getAmount()) {
                String err = "Buggy transition! Failed to drain " + fluid.getFluid();
                throw new IllegalStateException(err + " x " + fluid.getAmount() + " from " + t);
            }
        }

        @Nullable
        private FluidStorage<FluidStack> getFluidAutoOutputTarget() {
            Direction facing = getTile().getFacing();
            if (facing == null) {
                return null;
            }
            BlockPos neighbourPos = getTile().getBlockPos().relative(facing.getClockWise());
            return PlatformStorage.fluids(getTile().getLevel(), neighbourPos, facing.getCounterClockWise());
        }

        void getDebugInfo(List<String> left, List<String> right, Direction side) {
            super.getDebugInfo(left, right, side);
            left.add("progress = " + progress);
            left.add("state = " + progressState);
            left.add("has_end = " + (endSection != null));
            // left.add("heatProvided = " + heatProvided);
            // left.add("coolingProvided = " + coolingProvided);
        }
    }

    public static class ExchangeSectionEnd extends ExchangeSection {

        {
            tankInput.setFilter(this::isCoolant);
            caps.addFluidStorage(tankOutput, EnumPipePart.UP);
            caps.addFluidStorage(this::getTankForSide, EnumPipePart.HORIZONTALS);
        }

        ExchangeSectionEnd(TileHeatExchange tile) {
            super(tile);
        }

        ExchangeSectionEnd(TileHeatExchange tile, CompoundTag nbt) {
            super(tile, nbt);
        }

        private boolean isCoolant(FluidStack fluid) {
            return MachineRecipeApiBridge.findCooling(fluid) != null;
        }

        private Tank getTankForSide(Direction side) {
            Direction thisFacing = getTile().getFacing();
            if (thisFacing == null || side != thisFacing.getCounterClockWise()) {
                return null;
            }
            return tankInput;
        }

        CompoundTag writeToNbt() {
            CompoundTag nbt = super.writeToNbt();
            nbt.putBoolean("start", false);
            return nbt;
        }

        @Nullable
        FluidStorage<FluidStack> getFluidAutoOutputTarget() {
            BlockPos neighbourPos = getTile().getBlockPos().above();
            return PlatformStorage.fluids(getTile().getLevel(), neighbourPos, Direction.DOWN);
        }
    }

    public enum EnumProgressState {
        /** Progress is at 0, not moving. */
        OFF,
        /** Progress is increasing from 0 to max */
        PREPARING,
        /** progress stays at max */
        RUNNING,
        /** Progress is decreasing from max to 0. */
        STOPPING;
    }

    public Tank getSectionTank(int index) {
        return tanks[index%4];
    }

    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MenuHeatExchange(id, inventory, new ItemStackHandler(4), tankData, stateData, ContainerLevelAccess.create(level, worldPosition));
    }

    public Component getDisplayName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }
}
