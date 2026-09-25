/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.tile;

import buildcraft.api.v2.pipe.PipeRemovalReason;
import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeEvent;
import buildcraft.transport.internal.pipe.PipeEventTileState;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.compat.CompatCapTransfromer;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.silicon.plug.FilterEventHandler;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.client.model.ModelPipe;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.PipeEventBus;
import buildcraft.transport.pipe.PluggableHolder;
import buildcraft.transport.wire.WireManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TilePipeHolder extends TileBC_Neptune implements IPipeHolder, IDebuggable{

    protected static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("pipe");

    public static final int NET_UPDATE_MULTI = IDS.allocId("UPDATE_MULTI");
    public static final int NET_UPDATE_PIPE_BEHAVIOUR = getReceiverId(PipeMessageReceiver.BEHAVIOUR);
    public static final int NET_UPDATE_PIPE_FLOW = getReceiverId(PipeMessageReceiver.FLOW);
    public static final int NET_UPDATE_PLUG_DOWN = getReceiverId(PipeMessageReceiver.PLUGGABLE_DOWN);
    public static final int NET_UPDATE_PLUG_UP = getReceiverId(PipeMessageReceiver.PLUGGABLE_UP);
    public static final int NET_UPDATE_PLUG_NORTH = getReceiverId(PipeMessageReceiver.PLUGGABLE_NORTH);
    public static final int NET_UPDATE_PLUG_SOUTH = getReceiverId(PipeMessageReceiver.PLUGGABLE_SOUTH);
    public static final int NET_UPDATE_PLUG_WEST = getReceiverId(PipeMessageReceiver.PLUGGABLE_WEST);
    public static final int NET_UPDATE_PLUG_EAST = getReceiverId(PipeMessageReceiver.PLUGGABLE_EAST);
    public static final int NET_UPDATE_WIRES = getReceiverId(PipeMessageReceiver.WIRES);
    public static final int NET_UPDATE_API_COMPONENTS = getReceiverId(PipeMessageReceiver.API_COMPONENTS);

    private static final ResourceLocation ADVANCEMENT_PLACE_PIPE = ResourceLocation.parse(
        "buildcrafttransport:pipe_dream"
    );

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    private int[] redstoneValues = new int[6];
    private int[] oldRedstoneValues = new int[] { -1, -1, -1, -1, -1, -1 };

    static {
        for (PipeMessageReceiver rec : PipeMessageReceiver.values()) {
            IDS.allocId("UPDATE_" + rec);
        }
    }

    public static final int[] NET_UPDATE_PLUGS = { //
        NET_UPDATE_PLUG_DOWN, NET_UPDATE_PLUG_UP, //
        NET_UPDATE_PLUG_NORTH, NET_UPDATE_PLUG_SOUTH, //
        NET_UPDATE_PLUG_WEST, NET_UPDATE_PLUG_EAST,//
    };

    private static int getReceiverId(PipeMessageReceiver type) {
        return NET_UPDATE_MULTI + 1 + type.ordinal();
    }

    public final WireManager wireManager = new WireManager(this);
    public final PipeEventBus eventBus = new PipeEventBus();
    private final Map<Direction, PluggableHolder> pluggables = new EnumMap<>(Direction.class);
    private Pipe pipe = Pipe.EMPTY;
    private boolean scheduleRenderUpdate = true;
    /** Send one complete pipe creation payload after placement, before incremental behaviour/flow updates. */
    private boolean scheduleFullRenderSync;
    private long lastPeriodicSaveTick = Long.MIN_VALUE;
    private boolean wasFlowPersistentlyActive;
    private final Set<PipeMessageReceiver> networkUpdates = EnumSet.noneOf(PipeMessageReceiver.class);
    private final Set<PipeMessageReceiver> networkGuiUpdates = EnumSet.noneOf(PipeMessageReceiver.class);
    /** Payload source while a synchronous NET_UPDATE_MULTI packet is being encoded. */
    private Set<PipeMessageReceiver> activeNetworkBatch = Collections.emptySet();
    
    protected ModelData modeldata;
    private CompoundTag unknownData;
    private HolderLookup.Provider serializationRegistries;
    public int hitPart = 0;

    public TilePipeHolder(BlockPos pos, BlockState bs) {
    	super(BCTransportBlocks.PIPE_HOLDER_BE.get(), pos, bs);
        for (Direction side : Direction.values()) {
            pluggables.put(side, new PluggableHolder(this, side));
        }
        caps.addCapabilityInstance(PipeApi.CAP_PIPE_HOLDER, this, EnumPipePart.values());
        caps.addCapability(PipeApi.CAP_PIPE, this::getPipe, EnumPipePart.values());
        caps.addCapability(PipeApi.CAP_PLUG, this::getPluggable, EnumPipePart.FACES);
    }

    
    
    @Override
    protected boolean storesMachineDataAtRoot() { return true; }
    @Override
    protected boolean requiresPersistenceRegistries() { return false; }

    // Read + write


	@Override
    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        HolderLookup.Provider previousRegistries = serializationRegistries;
        serializationRegistries = registries;
        try {
            super.writeData(bcData);
            if (pipe != Pipe.EMPTY) {
                nbt.put("pipe", pipe.writeToNbt());
            } else if (unknownData != null) {
                // Never erase a pipe merely because this version cannot decode it yet.
                nbt.put("pipe", unknownData.copy());
            }
            CompoundTag plugs = new CompoundTag();
            for (Direction face : Direction.values()) {
                CompoundTag plugTag = pluggables.get(face).writeToNbt();
                if (!plugTag.isEmpty()) {
                    plugs.put(face.getName(), plugTag);
                }
            }
            if (!plugs.isEmpty()) {
                nbt.put("plugs", plugs);
            }
            nbt.put("wireManager", wireManager.writeToNbt());
            bcData.writeIntArray("redstone", redstoneValues);
        } finally {
            serializationRegistries = previousRegistries;
        }
    }

    @Override
    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        HolderLookup.Provider previousRegistries = serializationRegistries;
        serializationRegistries = registries;
        try {
            super.readData(bcData);
            unknownData = null;
            pipe = Pipe.EMPTY;
            if (bcData.has("pipe", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                CompoundTag pipeData = bcData.readCompound("pipe");
                try {
                    pipe = new Pipe(this, pipeData);
                    eventBus.registerHandler(pipe.behaviour);
                    eventBus.registerHandler(pipe.flow);
                    if (pipe.flow instanceof IFlowItems && BCModules.SILICON.isLoaded()) {
                        eventBus.registerHandler(FilterEventHandler.class);
                    }
                } catch (InvalidInputDataException | RuntimeException e) {
                    // A broken custom payload must not make Minecraft discard the complete
                    // block entity (pipe, facade, gate and wire data). Keep the raw pipe NBT.
                    buildcraft.lib.internal.debug.BCLog.logger.warn(
                        "Failed to migrate pipe at {}; preserving its original NBT", worldPosition, e
                    );
                    pipe = Pipe.EMPTY;
                    unknownData = pipeData.copy();
                }
            }
            CompoundTag plugs = bcData.readCompound("plugs");
            for (Direction face : Direction.values()) {
                pluggables.get(face).readFromNbt(plugs.getCompound(face.getName()));
            }
            wireManager.readFromNbt(bcData.readCompound("wireManager"));
            if (bcData.has("redstone")) {
                int[] temp = bcData.readIntArray("redstone");
                if (temp.length == 6) {
                    redstoneValues = temp;
                }
            }
        } finally {
            serializationRegistries = previousRegistries;
        }
    }


    public static HolderLookup.Provider getRegistryAccess(IPipe pipe) {
        if (pipe.getHolder().getPipeTile() instanceof TilePipeHolder holder
            && holder.serializationRegistries != null) {
            return holder.serializationRegistries;
        }
        Level world = pipe.getHolder().getPipeWorld();
        if (world != null) {
            return world.registryAccess();
        }
        throw new IllegalStateException("Cannot serialize pipe data without a registry lookup");
    }
    
    

    // Misc

	@Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        Item item = stack.getItem();
        if (item instanceof IItemPipe) {
            PipeDefinition definition = ((IItemPipe) item).getDefinition();
            this.unknownData = null;
            this.pipe = new Pipe(this, definition);
            unknownData = null;
            eventBus.registerHandler(pipe.behaviour);
            eventBus.registerHandler(pipe.flow);
            if (pipe.flow instanceof IFlowItems && BCModules.SILICON.isLoaded()) {
                eventBus.registerHandler(FilterEventHandler.class);
            }
            int meta = ItemPipeHolder.getPipeColorId(stack);
            if (meta > 0 && meta <= 16) {
                pipe.setColour(DyeColor.byId(meta - 1));
            }
        }
        if (level != null && !level.isClientSide) {
            markChunkDirty();
            // The capability provider exists as soon as the holder BE is created, but the actual pipe is installed
            // only here. Any cached EMPTY capability must therefore be invalidated immediately. Also notify every
            // neighbour so machines/engines and already-placed pipes can refresh their side topology.
            level.invalidateCapabilities(worldPosition);
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            pipe.markForUpdate();
            // The holder block is created before setPlacedBy fills in the actual pipe. Incremental connection updates
            // may otherwise reach a client whose holder still contains Pipe.EMPTY. Defer one full state packet until
            // the next tile tick so the block entity is present client-side and the pipe has its initial connections.
            scheduleFullRenderSync = true;
        }
        scheduleRenderUpdate();

        if (level != null && !level.isClientSide && hasOwner()) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_PLACE_PIPE);
        }
    }

	@Override
    public void onRemove(boolean dropSelf) {
        if (pipe != Pipe.EMPTY) {
            pipe.onApiRemoved(dropSelf ? PipeRemovalReason.DESTROYED : PipeRemovalReason.REPLACED);
        }
        super.onRemove(dropSelf);
        if (level != null && !level.isClientSide) {
            for (Direction face : Direction.values()) {
                PipePluggable pluggable = getPluggable(face);
                if (pluggable != PipePluggable.EMPTY) {
                    pluggable.onRemove();
                }
            }
        }
    }

	@Override
	public void setRemoved() {
        if (pipe != Pipe.EMPTY) pipe.onApiUnload(PipeRemovalReason.INVALIDATED);
		super.setRemoved();
		eventBus.fireEvent(new PipeEventTileState.Invalidate(this));
		wireManager.invalidate();
	}

	@Override
    public void clearRemoved() {
        super.clearRemoved();
        eventBus.fireEvent(new PipeEventTileState.Validate(this));
        wireManager.validate();
    }

    @Override
    public void onChunkUnloaded() {
        if (pipe != Pipe.EMPTY) pipe.onApiUnload(PipeRemovalReason.CHUNK_UNLOAD);
        super.onChunkUnloaded();
        eventBus.fireEvent(new PipeEventTileState.ChunkUnload(this));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (pipe != Pipe.EMPTY) {
            pipe.onLoad();
            pipe.onApiLoad();
        }
        wireManager.validate();
    }
    
    @Override
	public void neighbourBlockChanged(BlockState state, BlockPos neighbor, boolean harvest) {
        // 1.21.11 can report ordinary block changes through either neighbour callback. Always invalidate the
        // inherited neighbour tile cache and drive the same pipe-topology update path for both callbacks.
        super.onNeighbourBlockChanged(state, neighbor);
        markPipeTopologyDirty();
	}

	@Override
    public void onNeighbourBlockChanged(BlockState state, BlockPos neighbour) {
        super.onNeighbourBlockChanged(state, neighbour);
        markPipeTopologyDirty();
    }

    private void markPipeTopologyDirty() {
        if (level == null || level.isClientSide || pipe == Pipe.EMPTY) {
            return;
        }
        pipe.markForUpdate();
    }
    
    // ITickable

    public void update() {
        redstoneValues = new int[6];
        // Tick objects
        if (pipe != Pipe.EMPTY) {
            pipe.onTick();
        }
        for (Direction face : Direction.values()) {
            pluggables.get(face).onTick();
        }

        if (scheduleFullRenderSync && !level.isClientSide()) {
            scheduleFullRenderSync = false;
            sendNetworkUpdate(NET_RENDER_DATA);
        }

        // Coalesce all pipe-part changes scheduled in the same tick into one packet.
        if (!networkUpdates.isEmpty()) {
            sendNetworkBatch(EnumSet.copyOf(networkUpdates), false);
        }
        // No need to send GUI updates to specific players if the same parts were just broadcast to watchers.
        networkGuiUpdates.removeAll(networkUpdates);
        networkUpdates.clear();

        if (!networkGuiUpdates.isEmpty()) {
            sendNetworkBatch(EnumSet.copyOf(networkGuiUpdates), true);
        }
        networkGuiUpdates.clear();

        if (scheduleRenderUpdate) {
            scheduleRenderUpdate = false;
            redrawBlock();
        }

        wireManager.tick();

        if (!Arrays.equals(redstoneValues, oldRedstoneValues)) {
            Block block = level.getBlockState(worldPosition).getBlock();
            level.updateNeighborsAt(worldPosition, block);
            for (int i = 0; i < 6; i++) {
                Direction face = Direction.values()[i];
                if (oldRedstoneValues[i] != redstoneValues[i]) {
                    level.updateNeighborsAt(worldPosition.offset(face.getNormal()), block);
                }
            }
            oldRedstoneValues = redstoneValues;
        }

        if (!level.isClientSide && pipe != Pipe.EMPTY) {
            boolean flowActive = pipe.flow.requiresPeriodicSave() || pipe.behaviour.requiresPeriodicSave();
            long now = level.getGameTime();
            if (flowActive && (lastPeriodicSaveTick == Long.MIN_VALUE || now - lastPeriodicSaveTick >= 20)) {
                markChunkDirty();
                lastPeriodicSaveTick = now;
            } else if (!flowActive && wasFlowPersistentlyActive) {
                // Persist the transition to empty as well, otherwise the last saved travelling item/fluid could
                // reappear after a reload.
                markChunkDirty();
                lastPeriodicSaveTick = now;
            }
            wasFlowPersistentlyActive = flowActive;
        }
    }

    private void sendNetworkBatch(Set<PipeMessageReceiver> parts, boolean guiOnly) {
        if (parts.isEmpty()) return;
        if (parts.size() == 1) {
            int id = getReceiverId(parts.iterator().next());
            if (guiOnly) sendNetworkGuiUpdate(id);
            else sendNetworkUpdate(id);
            return;
        }
        activeNetworkBatch = EnumSet.copyOf(parts);
        try {
            if (guiOnly) sendNetworkGuiUpdate(NET_UPDATE_MULTI);
            else sendNetworkUpdate(NET_UPDATE_MULTI);
        } finally {
            activeNetworkBatch = Collections.emptySet();
        }
    }

    // Network

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (id == NET_UPDATE_MULTI) {
            int mask = 0;
            for (PipeMessageReceiver type : activeNetworkBatch) {
                mask |= 1 << type.ordinal();
            }
            buffer.writeShort(mask);
            for (PipeMessageReceiver type : PipeMessageReceiver.values()) {
                if ((mask & (1 << type.ordinal())) != 0) {
                    writePayload(getReceiverId(type), buffer, side);
                }
            }
            return;
        }
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                if (pipe == Pipe.EMPTY) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    pipe.writeCreationPayload(buffer);
                }
                for (Direction face : Direction.values()) {
                    pluggables.get(face).writeCreationPayload(buffer);
                }
                wireManager.writePayload(buffer, side);
            } else if (id == NET_UPDATE_PIPE_BEHAVIOUR) {
                if (pipe == Pipe.EMPTY) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    pipe.writePayload(buffer, side);
                }
            } else if (id == NET_UPDATE_WIRES) {
                wireManager.writePayload(buffer, side);
            } else if (id == NET_UPDATE_API_COMPONENTS) {
                if (pipe == Pipe.EMPTY) {
                    buffer.writeBoolean(false);
                } else {
                    buffer.writeBoolean(true);
                    pipe.writeApiComponentSync(buffer);
                }
            }
        }
        if (id == NET_UPDATE_PIPE_FLOW) {
            if (pipe == Pipe.EMPTY || pipe.flow == null) {
                buffer.writeBoolean(false);
            } else {
                buffer.writeBoolean(true);
                pipe.flow.writePayload(PipeFlow.NET_ID_UPDATE, buffer, side);
            }
        } else if (id == NET_UPDATE_PLUG_DOWN) pluggables.get(Direction.DOWN).writePayload(buffer, side);
        else if (id == NET_UPDATE_PLUG_UP) pluggables.get(Direction.UP).writePayload(buffer, side);
        else if (id == NET_UPDATE_PLUG_NORTH) pluggables.get(Direction.NORTH).writePayload(buffer, side);
        else if (id == NET_UPDATE_PLUG_SOUTH) pluggables.get(Direction.SOUTH).writePayload(buffer, side);
        else if (id == NET_UPDATE_PLUG_WEST) pluggables.get(Direction.WEST).writePayload(buffer, side);
        else if (id == NET_UPDATE_PLUG_EAST) pluggables.get(Direction.EAST).writePayload(buffer, side);
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
            	if (buffer.readBoolean()) {
                    pipe = new Pipe(this, buffer, ctx);
                    eventBus.registerHandler(pipe.behaviour);
                    eventBus.registerHandler(pipe.flow);
                    if (pipe.flow instanceof IFlowItems && BCModules.SILICON.isLoaded()) {
                        eventBus.registerHandler(FilterEventHandler.class);
                    }
                } else if (pipe != Pipe.EMPTY) {
                    eventBus.unregisterHandler(pipe.behaviour);
                    eventBus.unregisterHandler(pipe.flow);
                    pipe = Pipe.EMPTY;
                }
                for (Direction face : Direction.values()) {
                    pluggables.get(face).readCreationPayload(buffer);
                }
                wireManager.readPayload(buffer, side, ctx);
                refreshClientPipeModel();
            } else if (id == NET_UPDATE_MULTI) {
                int total = buffer.readUnsignedShort();
                for (PipeMessageReceiver type : PipeMessageReceiver.values()) {
                    if (((total >> type.ordinal()) & 1) == 1) {
                        readPayload(getReceiverId(type), buffer, side, ctx);
                    }
                }
            } else if (id == NET_UPDATE_PIPE_BEHAVIOUR) {
                // Apply the authoritative connection map before refreshing ModelData so the rebuilt chunk
                // always uses the connection lengths decoded by this payload.
                if (buffer.readBoolean()) {
                    if (pipe == Pipe.EMPTY) {
                        throw new IllegalStateException("Pipe was null when it shouldn't have been!");
                    } else {
                        pipe.readPayload(buffer, side, ctx);
                    }
                }
                refreshClientPipeModel();
            } else if (id == NET_UPDATE_WIRES) {
                wireManager.readPayload(buffer, side, ctx);
                refreshClientPipeModel();
            } else if (id == NET_UPDATE_API_COMPONENTS) {
                if (buffer.readBoolean()) {
                    if (pipe == Pipe.EMPTY) throw new IllegalStateException("API component sync arrived without a pipe");
                    pipe.readApiComponentSync(buffer);
                }
            }
        }
        if (id == NET_UPDATE_PIPE_FLOW) {
            if (buffer.readBoolean()) {
                if (pipe == Pipe.EMPTY) {
                    throw new IllegalStateException("Pipe was null when it shouldn't have been!");
                } else {
                    int fId = buffer.readShort();
                    pipe.flow.readPayload(fId, buffer, side);
                }
            }
        } else if (id == NET_UPDATE_PLUG_DOWN) pluggables.get(Direction.DOWN).readPayload(buffer, side, ctx);
        else if (id == NET_UPDATE_PLUG_UP) pluggables.get(Direction.UP).readPayload(buffer, side, ctx);
        else if (id == NET_UPDATE_PLUG_NORTH) pluggables.get(Direction.NORTH).readPayload(buffer, side, ctx);
        else if (id == NET_UPDATE_PLUG_SOUTH) pluggables.get(Direction.SOUTH).readPayload(buffer, side, ctx);
        else if (id == NET_UPDATE_PLUG_WEST) pluggables.get(Direction.WEST).readPayload(buffer, side, ctx);
        else if (id == NET_UPDATE_PLUG_EAST) pluggables.get(Direction.EAST).readPayload(buffer, side, ctx);

        if (side == BCNetworkSide.CLIENT && id >= NET_UPDATE_PLUG_DOWN && id <= NET_UPDATE_PLUG_EAST) {
            refreshClientPipeModel();
        }
    }

    private void refreshClientPipeModel() {
        if (level == null || !level.isClientSide) {
            return;
        }
        requestModelDataUpdate();
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    // IPipeHolder

    @Override
	public Level getPipeWorld() {
		return getLevel();
	}

    @Override
    public BlockPos getPipePos() {
        return getBlockPos();
    }


	@Override
    public BlockEntity getPipeTile() {
        return this;
    }

    @Override
    public Pipe getPipe() {
        return pipe;
    }

    @Override
    public boolean canPlayerInteract(Player player) {
        return canInteractWith(player);
    }

    @Override
    public PipePluggable getPluggable(Direction side) {
        if (side == null) return PipePluggable.EMPTY;
        return pluggables.get(side).pluggable;
    }

    public PipePluggable replacePluggable(Direction side, PipePluggable with) {
        redstoneValues = new int[6];
        PluggableHolder holder = pluggables.get(side);
        PipePluggable old = holder.pluggable;
        holder.setPluggable(with);

        eventBus.unregisterHandler(old);
        eventBus.registerHandler(with);

        if (pipe != Pipe.EMPTY) {
            pipe.markForUpdate();
        }
        if (!level.isClientSide()) {
            if (old != with) {
                wireManager.getWireSystems().rebuildWireSystemsAround(this);
                markChunkDirty();
            }
            holder.sendNewPluggableData();
        }
        scheduleRenderUpdate();
        if (!level.isClientSide()) {
            // Pluggables can add/remove/block side capabilities, so both local capability caches and neighbouring
            // connection graphs must be invalidated, not just the single visual side.
            level.invalidateCapabilities(worldPosition);
            level.updateNeighborsAt(worldPosition, BCTransportBlocks.pipeHolder.get());
        }
        level.neighborChanged(worldPosition.offset(side.getNormal()), BCTransportBlocks.pipeHolder.get(), worldPosition);
        return old;
    }

    @Override
    public IPipe getNeighbourPipe(Direction side) {
        if (level == null) {
            return Pipe.EMPTY;
        }
        BlockPos neighbourPos = worldPosition.relative(side);
        IPipe neighbourPipe = level.getCapability(PipeApi.CAP_PIPE, neighbourPos, side.getOpposite());
        return neighbourPipe == null ? Pipe.EMPTY : neighbourPipe;
    }

    
    
    @Override
    @Nullable
    public <T> T getCapabilityFromPipe(
        Direction side, @Nonnull BlockCapability<T, Direction> capability
    ) {
        PipePluggable plug = getPluggable(side);
        if (plug != PipePluggable.EMPTY) {
            T value = plug.getInternalCapability(capability);
            if (value != null) {
                return value;
            }
            if (plug.isBlocking()) {
                return null;
            }
        }
        if (pipe.isConnected(side) && level != null) {
            Direction targetSide = side.getOpposite();
            BlockPos neighbourPos = worldPosition.relative(side);
            BlockEntity neighbour = level.getBlockEntity(neighbourPos);

            // NeoForge 1.21.11 standard transfer capabilities are position based and may exist without a
            // BlockEntity. Adapt them before falling back to BuildCraft/legacy capability lookups.
            if (capability == CapUtil.CAP_ITEMS) {
                @SuppressWarnings("unchecked")
                T value = (T) CapUtil.getItemHandler(level, neighbourPos, targetSide);
                return value;
            }
            if (capability == CapUtil.CAP_FLUIDS) {
                if (neighbour != null) {
                    T transformed = CompatCapTransfromer.INSTANCE
                        .getCap(neighbour, capability, targetSide)
                        .orElse(null);
                    if (transformed != null) {
                        return transformed;
                    }
                }
                @SuppressWarnings("unchecked")
                T value = (T) CapUtil.getFluidHandler(level, neighbourPos, targetSide);
                return value;
            }
            if (capability == CapUtil.CAP_FE) {
                @SuppressWarnings("unchecked")
                T value = (T) CapUtil.getEnergyStorage(level, neighbourPos, targetSide);
                return value;
            }

            if (neighbour != null) {
                T transformed = CompatCapTransfromer.INSTANCE
                    .getCap(neighbour, capability, targetSide)
                    .orElse(null);
                if (transformed != null) {
                    return transformed;
                }
            }
            return level.getCapability(capability, neighbourPos, targetSide);
        }
        return null;
    }

    @Override
    public void scheduleRenderUpdate() {
        scheduleRenderUpdate = true;
    }

    @Override
    public void scheduleNetworkUpdate(PipeMessageReceiver... parts) {
        Collections.addAll(networkUpdates, parts);
        if (level != null && !level.isClientSide) {
            markChunkDirty();
        }
    }

    @Override
    public void scheduleNetworkGuiUpdate(PipeMessageReceiver... parts) {
        Collections.addAll(networkGuiUpdates, parts);
    }

    @Override
    public void sendMessage(PipeMessageReceiver to, IWriter writer) {
        createAndSendMessage(getReceiverId(to), writer::write);
    }

    @Override
    public void sendGuiMessage(PipeMessageReceiver to, IWriter writer) {
        createAndSendGuiMessage(getReceiverId(to), writer::write);
    }

    @Override
    public WireManager getWireManager() {
        return wireManager;
    }

    @Override
    public boolean fireEvent(PipeEvent event) {
        return eventBus.fireEvent(event);
    }

    @Override
    public int getRedstoneInput(Direction side) {
        if (side == null) {
            return level.getBestNeighborSignal(worldPosition);
        } else {
            return level.getSignal(worldPosition.offset(side.getNormal()), side);
        }
    }

    @Override
    public boolean setRedstoneOutput(Direction side, int value) {
        if (side == null) {
            for (Direction facing : Direction.values()) {
                redstoneValues[facing.ordinal()] = value;
            }
        } else {
            redstoneValues[side.ordinal()] = value;
        }
        return true;
    }

    public int getRedstoneOutput(Direction side) {
        return redstoneValues[side.ordinal()];
    }
    
    @Override
	public void rotate(Rotation axis) {
    	if(axis == Rotation.NONE)
    		return;
    	Map<Direction, PluggableHolder> copyPluggables = Map.copyOf(pluggables);
    	pluggables.clear();
        for (Map.Entry<Direction, PluggableHolder> e : copyPluggables.entrySet()) {
            PluggableHolder plug = e.getValue();
			plug.rotate(axis);
            pluggables.put(axis.rotate(e.getKey()), plug);
        }

        wireManager.rotate(axis);
        int[] newRedstione = new int[6];
        for(Direction face : Direction.values())
        	newRedstione[axis.rotate(face).ordinal()] = redstoneValues[face.ordinal()];
        redstoneValues = newRedstione;
        pipe.rotate(axis);
        if (level != null && !level.isClientSide) {
            markChunkDirty();
        }
	}

    // Caps

    
    @Override
    @Nullable
    public <T> T getCapability(
        BlockCapability<T, Direction> capability, @Nullable Direction facing
    ) {
        if (facing != null) {
            PipePluggable plug = getPluggable(facing);
            if (plug != PipePluggable.EMPTY) {
                T value = plug.getCapability(capability);
                if (value != null) {
                    return value;
                }
                if (plug.isBlocking()) {
                    return null;
                }
            }
        }
        if (pipe != Pipe.EMPTY) {
            T value = pipe.getCapability(capability, facing);
            if (value != null) {
                return value;
            }
        }
        return super.getCapability(capability, facing);
    }

    
    @Override
    public void requestModelDataUpdate() {
        // This class owns an additional ModelData cache on top of NeoForge's model-data lifecycle. Clearing only the
        // framework cache is insufficient: the next getModelData() would otherwise return the exact stale object.
        modeldata = null;
        super.requestModelDataUpdate();
    }

	@Override
	public @NotNull ModelData getModelData() {
		if (modeldata == null) {
			modeldata = TilePipeHolderModelData.build(this);
		}
		return modeldata;
	}

    // Client side stuffs

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        if (pipe == Pipe.EMPTY) {
            left.add("Pipe = null");
        } else {
            left.add("Pipe:");
            pipe.getDebugInfo(left, right, side);
        }
        left.add("Parts:");
        wireManager.parts
            .forEach((part, color) -> left.add(" - " + part + " = " + color + " = " + wireManager.isPowered(part)));
        left.add("All wire systems in world count = "
            + (level.isClientSide ? 0 : wireManager.getWireSystems().wireSystems.size()));
        if (unknownData != null) {
            left.add(unknownData.toString());
        }
    }

}
