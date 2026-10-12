/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.ItemInjectionRequest;
import buildcraft.api.v2.pipe.ItemPipePort;
import buildcraft.api.v2.pipe.ExternalEnergyRouteComponent;
import buildcraft.api.v2.pipe.ExternalEnergyRouteContext;
import buildcraft.api.v2.pipe.FluidRouteComponent;
import buildcraft.api.v2.pipe.FluidRouteContext;
import buildcraft.api.v2.pipe.ItemRouteComponent;
import buildcraft.api.v2.pipe.ItemAdmissionComponent;
import buildcraft.api.v2.pipe.ItemAdmissionContext;
import buildcraft.api.v2.pipe.ItemRouteContext;
import buildcraft.api.v2.pipe.ItemTransitData;
import buildcraft.api.v2.pipe.PipeActivationComponent;
import buildcraft.api.v2.pipe.PipeActivationContext;
import buildcraft.api.v2.pipe.PipeActivationResult;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeEndpointKind;
import buildcraft.api.v2.pipe.PipeConnectionComponent;
import buildcraft.api.v2.pipe.PipeConnectionContext;
import buildcraft.api.v2.pipe.PipeConnectionDecision;
import buildcraft.api.v2.pipe.PipeConnectionRule;
import buildcraft.api.v2.pipe.MjRouteComponent;
import buildcraft.api.v2.pipe.MjRouteContext;
import buildcraft.api.v2.pipe.PipeMutationContext;
import buildcraft.api.v2.pipe.PipeExecutionContext;
import buildcraft.api.v2.pipe.PipeConnectionChange;
import buildcraft.api.v2.pipe.PipeTickComponent;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.logic.routing.WeightedOrder;
import buildcraft.transport.internal.pipe.ICustomPipeConnection;
import buildcraft.transport.internal.pipe.IFlowFluid;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeConnectionAPI;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeEventConnectionChange;
import buildcraft.transport.internal.pipe.PipeFaceTex;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.transport.api2.PipeTypeBridge;
import buildcraft.transport.api2.PipeComponentRuntime;
import buildcraft.transport.api2.PipeResourceRuntime;
import buildcraft.transport.api2.PipeAttachmentBridge;
import buildcraft.transport.api2.ReadOnlyPipeView;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import buildcraft.transport.client.model.key.PipeModelKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.NbtCompat;

public final class Pipe implements IPipe, IDebuggable, PipeExecutionContext {
    private static final float DEFAULT_CONNECTION_DISTANCE = 0.25f;
    
    public final static Pipe EMPTY = new Pipe();

    public final IPipeHolder holder;
    public final PipeDefinition definition;
    public final PipeBehaviour behaviour;
    public final PipeFlow flow;
    private final List<PipeComponent> apiComponents;
    private final PipeComponentRuntime apiRuntime;
    private boolean apiEnvironmentActive;
    private boolean apiEnvironmentInvalidated;
    private DyeColor colour = null;
    private boolean updateMarked = true;
    private final EnumMap<Direction, Float> connected = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, ConnectedType> types = new EnumMap<>(Direction.class);
    private PipeModelKey lastModel;

    public Pipe(IPipeHolder holder, PipeDefinition definition) {		
        this.holder = holder;
        this.definition = definition;
        this.behaviour = definition.logicConstructor.createBehaviour(this);
        this.flow = definition.flowType.creator.createFlow(this);
        this.apiRuntime = createApiRuntime(null);
        this.apiComponents = apiRuntime.components();
    }
    
    /**Only use for {@link IPipe#EMPTY}*/
    private Pipe() {
		this.holder = null;
		this.definition = null;
		this.behaviour = null;
		this.flow = null;
        this.apiRuntime = null;
        this.apiComponents = List.of();
    }

    // read + write

    public Pipe(IPipeHolder holder, CompoundTag nbt) throws InvalidInputDataException {
        this.holder = holder;
        this.colour = NBTUtilBC.readEnum(nbt.get("col"), DyeColor.class);
        this.definition = PipeRegistry.INSTANCE.loadDefinition(NbtCompat.getString(nbt, "def"));
        if (!definition.canBeColoured) {
            colour = null;
        }
        this.behaviour = definition.logicLoader.loadBehaviour(this, NbtCompat.getCompound(nbt, "beh"));
        this.flow = definition.flowType.loader.loadFlow(this, NbtCompat.getCompound(nbt, "flow"));
        byte[] savedComponents = nbt.get("apiComponents") instanceof ByteArrayTag value
            ? value.getAsByteArray() : null;
        this.apiRuntime = createApiRuntime(savedComponents);
        this.apiComponents = apiRuntime.components();

        int connectionData = NbtCompat.getInt(nbt, "con");
        for (Direction face : Direction.values()) {
            int data = (connectionData >>> (face.ordinal() * 2)) & 0b11;
            // The only important aspect of this is the pipe type
            // as the texture index is just used at the client (which is updated in the first tick)
            // and the distance is only used on the server for item pipe travel times.
            // (which is minor enough that it doesn't really matter)
            if (data == 0b01) {
                connected.put(face, DEFAULT_CONNECTION_DISTANCE);
                types.put(face, ConnectedType.PIPE);
            } else if (data == 0b10) {
                connected.put(face, DEFAULT_CONNECTION_DISTANCE);
                types.put(face, ConnectedType.TILE);
            }
        }
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("col", NBTUtilBC.writeEnum(colour));
        nbt.putString("def", definition.identifier.toString());
        nbt.put("beh", behaviour.writeToNbt());
        nbt.put("flow", flow.writeToNbt());
        byte[] components = apiRuntime.save();
        if (components.length != 0) nbt.putByteArray("apiComponents", components);

        int connectionData = 0;
        for (Direction face : Direction.values()) {
            ConnectedType type = types.get(face);
            if (type != null) {
                int data = type == ConnectedType.PIPE ? 0b01 : 0b10;
                connectionData |= data << (face.ordinal() * 2);
            }
        }
        nbt.putInt("con", connectionData);
        return nbt;
    }

    // network

    public Pipe(IPipeHolder holder, FriendlyByteBuf buffer, BCPacketContext ctx) throws IOException {
        this.holder = holder;
        try {
            this.definition = PipeRegistry.INSTANCE.loadDefinition(buffer.readUtf(64));
        } catch (InvalidInputDataException e) {
            throw new IOException(e);
        }
        this.behaviour = definition.logicConstructor.createBehaviour(this);
        readPayload(buffer, BCNetworkSide.CLIENT, ctx);
        this.flow = definition.flowType.creator.createFlow(this);
        this.apiRuntime = createApiRuntime(null);
        this.apiComponents = apiRuntime.components();
        this.flow.readPayload(PipeFlow.NET_ID_FULL_STATE, buffer, BCNetworkSide.CLIENT);
        apiRuntime.readSync(buffer);
    }

    public void writeCreationPayload(FriendlyByteBuf buffer) {
        buffer.writeUtf(definition.identifier.toString(), 64);
        writePayload(buffer, BCNetworkSide.SERVER);
        flow.writePayload(PipeFlow.NET_ID_FULL_STATE, buffer, BCNetworkSide.SERVER);
        apiRuntime.writeFullSync(buffer);
    }

    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        if (side == BCNetworkSide.SERVER) {
            buffer.writeByte(colour == null ? 0 : colour.getId() + 1);
            for (Direction face : Direction.values()) {
                Float con = connected.get(face);
                if (con != null) {
                    buffer.writeBoolean(true);
                    buffer.writeFloat(con);
                    MessageUtil.writeEnumOrNull(buffer, types.get(face));
                } else {
                    buffer.writeBoolean(false);
                }
            }
            behaviour.writePayload(buffer, side);
        }
    }
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        if (side == BCNetworkSide.CLIENT) {
            connected.clear();
            types.clear();
            
            int nColour = buffer.readUnsignedByte();
            colour = nColour == 0 ? null : DyeColor.byId(nColour - 1);

            for (Direction face : Direction.values()) {
                if (buffer.readBoolean()) {
                    float dist = buffer.readFloat();
                    connected.put(face, dist);
                    ConnectedType type = MessageUtil.readEnumOrNull(buffer, ConnectedType.class);
                    types.put(face, type);
                }
            }

            behaviour.readPayload(buffer, side, ctx);

/*            PipeModelKey model = getModel();
            if (!model.equals(lastModel)) {
                lastModel = model;
                getHolder().scheduleRenderUpdate();
            }*/
        }
    }

    // IPipe

    public IPipeHolder getHolder() {
        return holder;
    }

    public PipeDefinition getDefinition() {
        return definition;
    }

    public PipeBehaviour getBehaviour() {
        return behaviour;
    }

    public PipeFlow getFlow() {
        return flow;
    }

    public DyeColor getColour() {
        return this.colour;
    }

    public void setColour(DyeColor colour) {
        if (definition.canBeColoured) {
            this.colour = colour;
            markForUpdate();
        }
    }

    // Caps

    @Nullable
    public <T> T getCapability(
        BlockCapability<T, Direction> capability, @Nullable Direction facing
    ) {
        T value = behaviour.getCapability(capability, facing);
        if (value != null) {
            return value;
        }
        return flow.getCapability(capability, facing);
    }

    // misc

    public void onLoad() {
        apiEnvironmentActive = true;
        markForUpdate();
        apiRuntime.onLoad();
    }

    public void onApiPlaced() {
        apiEnvironmentActive = true;
        apiRuntime.placed();
    }

    public void onApiRemoved() {
        apiRuntime.removed();
        apiEnvironmentActive = false;
    }

    public void onChunkUnloaded() {
        apiEnvironmentActive = false;
        apiRuntime.onChunkUnloaded();
    }

    public void invalidateApiComponents() {
        apiEnvironmentActive = false;
        apiEnvironmentInvalidated = true;
        apiRuntime.invalidate();
    }

    public void onTick() {
        if (updateMarked) {
            // Ensure that the behaviour and flow *always* get valid connection data
            // (for example if we just read from disk)
            updateConnections();
        }
        behaviour.onTick();
        flow.onTick();
        apiRuntime.tick();
        PipeResourceRuntime.tick(this);
        if (updateMarked) {
            updateConnections();
        }
    }

    private void updateConnections() {
        if (holder.getPipeWorld().isClientSide()) {
            return;
        }
        updateMarked = false;

        EnumMap<Direction, Float> old = connected.clone();
        EnumMap<Direction, ConnectedType> oldTypes = types.clone();

        connected.clear();
        types.clear();

        Level level = holder.getPipeWorld();
        BlockPos pipePos = holder.getPipePos();
        for (Direction facing : Direction.values()) {
            PipePluggable plug = getHolder().getPluggable(facing);
            if (plug != PipePluggable.EMPTY && plug.isBlocking()) {
                continue;
            }

            BlockPos nPos = pipePos.relative(facing);
            BlockState neighbour = level.getBlockState(nPos);
            BlockEntity oTile = level.getBlockEntity(nPos);

            // Pipe topology is a block-capability lookup in NeoForge. Do not require a neighbouring
            // BlockEntity before asking for CAP_PIPE/CAP_PLUG because capabilities are position based.
            IPipe oPipe = getHolder().getNeighbourPipe(facing);
            if (oPipe != Pipe.EMPTY) {
                PipeBehaviour oBehaviour = oPipe.getBehaviour();
                if (oBehaviour == null) {
                    continue;
                }
                PipePluggable oPlug = level.getCapability(PipeApi.CAP_PLUG, nPos, facing.getOpposite());
                if (oPlug == null) {
                    oPlug = PipePluggable.EMPTY;
                }
                if (oPlug == PipePluggable.EMPTY || !oPlug.isBlocking()) {
                    if (canConnectToPipeApiAware(facing, oPipe, neighbour)) {
                        connected.put(facing, DEFAULT_CONNECTION_DISTANCE);
                        types.put(facing, ConnectedType.PIPE);
                    }
                    continue;
                }
            }

            ICustomPipeConnection cust = PipeConnectionAPI.getCustomConnection(neighbour.getBlock());
            if (cust == null) {
                cust = DefaultPipeConnection.INSTANCE;
            }
            float ext = DEFAULT_CONNECTION_DISTANCE
                + cust.getExtension(level, nPos, facing.getOpposite(), neighbour);

            boolean forceFlow = flow.shouldForceConnection(facing, level, nPos, oTile);
            boolean flowCompatible = forceFlow || flow.canConnect(facing, level, nPos, oTile);
            boolean legacyConnect = behaviour.shouldForceConnection(facing, level, nPos, oTile) || forceFlow
                || (behaviour.canConnect(facing, level, nPos, oTile) && flowCompatible);
            PipeConnectionDecision apiDecision = apiConnectionDecision(facing, neighbour);
            if (apiDecision != PipeConnectionDecision.DENY
                && (legacyConnect || (apiDecision == PipeConnectionDecision.ALLOW && flowCompatible))) {
                connected.put(facing, ext);
                types.put(facing, ConnectedType.TILE);
            }
        }
        boolean topologyChanged = !old.equals(connected) || !oldTypes.equals(types);
        if (topologyChanged) {
            for (Direction face : Direction.values()) {
                boolean oldConnected = old.containsKey(face);
                boolean newConnected = connected.containsKey(face);
                ConnectedType oldType = oldTypes.get(face);
                ConnectedType newType = types.get(face);
                if (oldConnected != newConnected || oldType != newType) {
                    IPipe neighbourPipe = getHolder().getNeighbourPipe(face);
                    if (neighbourPipe != Pipe.EMPTY) {
                        neighbourPipe.markForUpdate();
                    }
                    holder.fireEvent(new PipeEventConnectionChange(holder, face));
                    apiRuntime.connectionsChanged(new PipeConnectionChange(face,
                        oldType == null ? PipeEndpointKind.NONE
                            : oldType == ConnectedType.PIPE ? PipeEndpointKind.PIPE : PipeEndpointKind.BLOCK,
                        newType == null ? PipeEndpointKind.NONE
                            : newType == ConnectedType.PIPE ? PipeEndpointKind.PIPE : PipeEndpointKind.BLOCK));
                }
            }
            // Connection lengths/types are part of the baked pipe model, not just flow state.
            holder.scheduleRenderUpdate();
        }
        getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
    }

    // API2 pipe runtime surface

    private PipeComponentRuntime createApiRuntime(byte[] saved) {
        PipeType type = definition.getApiType();
        if (type == null) type = PipeTypeBridge.ensureRegistered(definition);
        return new PipeComponentRuntime(this, type,
            holder.getPipeWorld() != null && holder.getPipeWorld().isClientSide(), saved);
    }

    public Identifier typeId() {
        return definition.getApiType() != null ? definition.getApiType().id() : definition.identifier;
    }

    public BlockPos position() {
        return holder.getPipePos();
    }

    @Override
    public boolean isClientSide() {
        return holder != null && holder.getPipeWorld() != null && holder.getPipeWorld().isClientSide();
    }

    @Override
    public <T> Optional<T> presentation(Identifier channelId, Class<T> type) {
        return apiRuntime == null ? Optional.empty() : apiRuntime.presentation(channelId, type);
    }

    public Set<Direction> connectedSides() {
        if (connected.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(EnumSet.copyOf(connected.keySet()));
    }

    public PipeEndpointKind endpoint(Direction side) {
        ConnectedType type = types.get(Objects.requireNonNull(side, "side"));
        if (type == null) return PipeEndpointKind.NONE;
        return type == ConnectedType.PIPE ? PipeEndpointKind.PIPE : PipeEndpointKind.BLOCK;
    }

    public Optional<DyeColor> color() {
        return Optional.ofNullable(colour);
    }

    public boolean colorable() {
        return definition.canBeColoured;
    }

    public Map<Direction, PipeAttachment> attachments() {
        EnumMap<Direction, PipeAttachment> result = new EnumMap<>(Direction.class);
        for (Direction side : Direction.values()) {
            PipeAttachment attachment = PipeAttachmentBridge.attachment(holder.getPluggable(side), side);
            if (attachment != null) result.put(side, attachment);
        }
        return Collections.unmodifiableMap(result);
    }

    public List<PipeComponent> components() {
        return apiComponents == null ? List.of() : apiComponents;
    }

    public Optional<PipeComponent> component(Identifier typeId) {
        Objects.requireNonNull(typeId, "typeId");
        return (apiComponents == null ? List.<PipeComponent>of() : apiComponents).stream().filter(component -> typeId.equals(component.typeId())).findFirst();
    }

    public Optional<ItemPort> itemPort(Direction side) {
        return itemPipePort(side).map(port -> (ItemPort) port);
    }

    public Optional<ItemPipePort> itemPipePort(Direction side) {
        Objects.requireNonNull(side, "side");
        if (!(flow instanceof IFlowItems itemFlow)) return Optional.empty();
        return Optional.of(new ItemPipePort() {
            public ItemTransferResult insert(ItemStack offered, OperationMode mode) {
                return inject(new ItemInjectionRequest(offered, side, ItemTransitData.DEFAULT), mode);
            }

            public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode) {
                Objects.requireNonNull(matcher, "matcher");
                Objects.requireNonNull(mode, "mode");
                if (maxCount < 0) throw new IllegalArgumentException("maxCount must be non-negative");
                // Item pipe extraction means pulling an adjacent inventory into the pipe, not returning items to
                // the API caller. Exposing that as ItemPort.extract would violate transfer-result semantics.
                return ItemTransferResult.nothing(maxCount);
            }

            public ItemTransferResult inject(ItemInjectionRequest request, OperationMode mode) {
                Objects.requireNonNull(request, "request");
                Objects.requireNonNull(mode, "mode");
                ItemStack offered = request.stack();
                ItemStack remainder = itemFlow.injectItem(
                    offered.copy(), mode == OperationMode.EXECUTE, side,
                    request.transit().color().orElse(null), request.transit().speedBlocksPerTick()
                );
                int accepted = Math.max(0, offered.getCount() - remainder.getCount());
                return ItemTransferResult.ofInsertion(offered, accepted);
            }
        });
    }

    public Optional<FluidPort> fluidPort(Direction side) {
        Objects.requireNonNull(side, "side");
        if (!(flow instanceof IFlowFluid fluidFlow)) return Optional.empty();
        return Optional.of(new FluidPort() {
            public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
                Objects.requireNonNull(offered, "offered");
                Objects.requireNonNull(mode, "mode");
                if (offered.isEmpty()) return FluidTransferResult.ofInsertion(offered, FluidAmount.ZERO);
                long amount = Math.min(Integer.MAX_VALUE, offered.amount().milliBuckets());
                FluidVolume bounded = offered.withAmount(FluidAmount.of(amount));
                var stack = FuelApiBridge.stackOf(bounded);
                if (stack.isEmpty()) return FluidTransferResult.ofInsertion(offered, FluidAmount.ZERO);
                int accepted = fluidFlow.insertFluidsForce(stack, side, fluidAction(mode));
                return FluidTransferResult.ofInsertion(offered, FluidAmount.of(Math.max(0, accepted)));
            }

            public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationMode mode) {
                Objects.requireNonNull(matcher, "matcher");
                Objects.requireNonNull(maxAmount, "maxAmount");
                Objects.requireNonNull(mode, "mode");
                if (maxAmount.isZero()) return FluidTransferResult.nothing(maxAmount);
                int max = (int) Math.min(Integer.MAX_VALUE, maxAmount.milliBuckets());
                var simulated = fluidFlow.extractFluidsForce(1, max, side, fluidAction(OperationMode.SIMULATE));
                if (simulated == null || simulated.isEmpty()) return FluidTransferResult.nothing(maxAmount);
                FluidVolume simulatedVolume = FuelApiBridge.volumeOf(simulated);
                if (!matcher.matches(simulatedVolume.requireVariant(), FuelApiBridge.MATCH_CONTEXT)) {
                    return FluidTransferResult.nothing(maxAmount);
                }
                if (mode == OperationMode.SIMULATE) {
                    return FluidTransferResult.ofExtraction(maxAmount, simulatedVolume);
                }
                var extracted = fluidFlow.extractFluidsForce(1, max, side, fluidAction(OperationMode.EXECUTE));
                FluidVolume volume = extracted == null ? FluidVolume.empty() : FuelApiBridge.volumeOf(extracted);
                return FluidTransferResult.ofExtraction(maxAmount, volume);
            }
        });
    }

    public Optional<MjPort> mjPort(Direction side) {
        Objects.requireNonNull(side, "side");
        if (!(flow instanceof PipeFlowPower powerFlow)) return Optional.empty();
        return Optional.of(new MjPort() {
            public MjTransferResult insert(MjAmount offered, OperationMode mode) {
                Objects.requireNonNull(offered, "offered");
                Objects.requireNonNull(mode, "mode");
                long accepted = powerFlow.receivePowerFromApi(side, offered.microMj(), mode == OperationMode.SIMULATE);
                return MjTransferResult.of(offered, MjAmount.ofMicro(Math.max(0, Math.min(offered.microMj(), accepted))));
            }

            public MjTransferResult extract(MjAmount requested, OperationMode mode) {
                Objects.requireNonNull(requested, "requested");
                Objects.requireNonNull(mode, "mode");
                Level world = holder.getPipeWorld();
                if (world == null || world.isClientSide() || world.getServer() == null
                    || !world.getServer().isSameThread()) return MjTransferResult.none(requested);
                long amount = powerFlow.extractPowerForApi(side, requested.microMj(), mode == OperationMode.SIMULATE);
                if (amount > 0 && mode == OperationMode.EXECUTE) markChanged();
                return MjTransferResult.of(requested, MjAmount.ofMicro(amount));
            }
            public MjAmount stored() { return MjAmount.ofMicro(powerFlow.getStoredPowerForApi(side)); }
            public MjAmount capacity() { return MjAmount.ofMicro(powerFlow.getMaxPowerForApi()); }
            public boolean canInsert() { return powerFlow.canReceivePowerFromApi(); }
            public boolean canExtract() { return true; }
        });
    }

    public Optional<ExternalEnergyPort> externalEnergyPort(Direction side) {
        Objects.requireNonNull(side, "side");
        if (!(flow instanceof PipeFlowForgeEnergy energyFlow)) return Optional.empty();
        return Optional.of(new ExternalEnergyPort() {
            public long insert(long offered, OperationMode mode) {
                Objects.requireNonNull(mode, "mode");
                if (offered <= 0) return 0;
                int bounded = (int) Math.min(Integer.MAX_VALUE, offered);
                return energyFlow.receiveEnergyFromApi(side, bounded, mode == OperationMode.SIMULATE);
            }

            public long extract(long requested, OperationMode mode) {
                Objects.requireNonNull(mode, "mode");
                if (requested <= 0) return 0;
                Level world = holder.getPipeWorld();
                if (world == null || world.isClientSide() || world.getServer() == null
                    || !world.getServer().isSameThread()) return 0;
                int taken = energyFlow.extractEnergyForApi(side, (int) Math.min(Integer.MAX_VALUE, requested),
                    mode == OperationMode.SIMULATE);
                if (taken > 0 && mode == OperationMode.EXECUTE) markChanged();
                return taken;
            }
            public long stored() { return energyFlow.getStoredEnergyForApi(side); }
            public long capacity() { return energyFlow.getMaxEnergyForApi(); }
            public boolean canInsert() { return energyFlow.canReceiveEnergyFromApi(); }
            public boolean canExtract() { return true; }
        });
    }

    public boolean isServerSide() {
        return holder != null && holder.getPipeWorld() != null && !holder.getPipeWorld().isClientSide();
    }

    public boolean isActive() {
        return apiEnvironmentActive && !apiEnvironmentInvalidated;
    }

    public boolean isNeighbourLoaded(Direction side) {
        Objects.requireNonNull(side, "side");
        Level level = holder.getPipeWorld();
        return isActive() && level != null && level.hasChunkAt(position().relative(side));
    }

    public Optional<BlockState> neighbourState(Direction side) {
        return isNeighbourLoaded(side) ? Optional.of(holder.getPipeWorld().getBlockState(position().relative(side)))
            : Optional.empty();
    }

    public Optional<PipeView> neighbourPipe(Direction side) {
        if (!isNeighbourLoaded(side)) return Optional.empty();
        IPipe next = holder.getNeighbourPipe(side);
        return next instanceof PipeView view && next != Pipe.EMPTY ? Optional.of(ReadOnlyPipeView.of(view)) : Optional.empty();
    }

    private void requireActiveWrite() {
        if (!isServerSide() || !isActive()) {
            throw new IllegalStateException("Pipe mutation requires an active server-side pipe");
        }
    }

    public boolean changeColor(Optional<DyeColor> color) {
        requireActiveWrite();
        Objects.requireNonNull(color, "color");
        if (!definition.canBeColoured) return false;
        DyeColor next = color.orElse(null);
        if (Objects.equals(colour, next)) return true;
        setColour(next);
        markChanged();
        holder.scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        return true;
    }

    public void refreshConnections() {
        requireActiveWrite();
        markForUpdate();
        holder.getPipeTile().setChanged();
    }

    public void requestAttachmentSync(Direction side) {
        requireActiveWrite();
        Objects.requireNonNull(side, "side");
        if (PipeAttachmentBridge.attachment(holder.getPluggable(side), side) == null) {
            throw new IllegalArgumentException("No attachment on side " + side);
        }
        holder.getPipeTile().setChanged();
        holder.scheduleNetworkUpdate(PipeMessageReceiver.PLUGGABLES[side.ordinal()]);
    }

    public void markChanged() {
        requireActiveWrite();
        markForUpdate();
        holder.getPipeTile().setChanged();
        if (apiRuntime != null && apiRuntime.stateChanged()) {
            holder.scheduleNetworkUpdate(PipeMessageReceiver.API_COMPONENTS);
        }
    }

    public void requestSync(Identifier channelId) {
        requireActiveWrite();
        Objects.requireNonNull(channelId, "channelId");
        if (BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).get(channelId) == null) {
            throw new IllegalArgumentException("Unknown pipe sync channel: " + channelId);
        }
        if (apiRuntime != null && apiRuntime.requestSync(channelId)) {
            holder.scheduleNetworkUpdate(PipeMessageReceiver.API_COMPONENTS);
        }
    }

    public void writeApiComponentSync(FriendlyByteBuf buffer) {
        apiRuntime.writeChangedSync(buffer);
    }

    public void readApiComponentSync(FriendlyByteBuf buffer) throws IOException {
        apiRuntime.readSync(buffer);
    }

    public PipeActivationResult activateApiComponents(Direction side, ItemStack held, AutomationActor actor, OperationMode mode) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        PipeActivationResult result = PipeActivationResult.PASS;
        PipeActivationContext context = new PipeActivationContext(this, side, held, actor, mode);
        for (PipeComponent component : apiComponents) {
            if (!(component instanceof PipeActivationComponent activation)) continue;
            PipeActivationResult current = Objects.requireNonNull(activation.activate(context), "activation result");
            if (current == PipeActivationResult.DENIED || current == PipeActivationResult.FAILED) return current;
            if (current == PipeActivationResult.SUCCESS) result = current;
        }
        return result;
    }

    /** Only the component decides admission; the existing PipeFlowItems owns all accepted stacks. */
    public boolean acceptItemInjection(ItemStack stack, Direction input, OperationMode mode) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
        if (stack.isEmpty()) return false;
        ItemAdmissionContext context = null;
        for (PipeComponent component : apiComponents) {
            if (!(component instanceof ItemAdmissionComponent admission)) continue;
            try {
                if (context == null) context = new ItemAdmissionContext(
                    buildcraft.transport.api2.ReadOnlyPipeView.of(this), input, stack, mode);
                if (!admission.accepts(context)) return false;
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe admission component {} failed at {}", component.typeId(), position(), failure);
                return false;
            }
        }
        return true;
    }

    public List<Direction> applyItemRouting(Direction input, ItemStack stack, List<Direction> candidates) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(candidates, "candidates");
        List<ItemRouteComponent> routers = apiComponents.stream()
            .filter(ItemRouteComponent.class::isInstance)
            .map(ItemRouteComponent.class::cast)
            .toList();
        if (routers.isEmpty() || candidates.isEmpty()) return candidates;

        LinkedHashMap<Direction, Long> weights = new LinkedHashMap<>();
        for (Direction candidate : candidates) {
            long baseWeight = 1L;
            IPipe neighbour = getConnectedPipe(candidate);
            if (neighbour != null && neighbour != Pipe.EMPTY && neighbour.getDefinition().getApiType() != null) {
                baseWeight = neighbour.getDefinition().getApiType().itemProfile()
                    .map(profile -> (long) profile.routingWeight())
                    .orElse(1L);
            }
            weights.putIfAbsent(candidate, baseWeight);
        }
        for (ItemRouteComponent router : routers) {
            Set<Direction> active = new LinkedHashSet<>();
            for (Map.Entry<Direction, Long> entry : weights.entrySet()) {
                if (entry.getValue() > 0) active.add(entry.getKey());
            }
            if (active.isEmpty()) break;
            try {
                Map<Direction, Integer> decision = router.route(new ItemRouteContext(this, input, stack, active)).weights();
                applyRouteDecision(weights, decision);
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe item routing component failed at {}", position(), failure);
            }
        }
        return weightedOrder(weights);
    }

    public List<Direction> applyFluidRouting(Set<Direction> inputs, FluidVolume volume, Set<Direction> candidates) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(candidates, "candidates");
        List<FluidRouteComponent> routers = apiComponents.stream()
            .filter(FluidRouteComponent.class::isInstance)
            .map(FluidRouteComponent.class::cast)
            .toList();
        if (candidates.isEmpty()) return List.of();

        LinkedHashMap<Direction, Long> weights = initialRouteWeights(candidates);
        for (FluidRouteComponent router : routers) {
            Set<Direction> active = activeDirections(weights);
            if (active.isEmpty()) break;
            try {
                applyRouteDecision(weights, router.route(new FluidRouteContext(this, inputs, volume, active)).weights());
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe fluid routing component failed at {}", position(), failure);
            }
        }
        return weightedOrder(weights);
    }

    public Map<Direction, Long> applyMjRouting(Direction input, MjAmount amount, Set<Direction> candidates) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(candidates, "candidates");
        LinkedHashMap<Direction, Long> weights = initialRouteWeights(candidates);
        for (PipeComponent component : apiComponents) {
            if (!(component instanceof MjRouteComponent router)) continue;
            Set<Direction> active = activeDirections(weights);
            if (active.isEmpty()) break;
            try {
                applyRouteDecision(weights, router.route(new MjRouteContext(this, input, amount, active)).weights());
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe MJ routing component failed at {}", position(), failure);
            }
        }
        return Collections.unmodifiableMap(weights);
    }

    public Map<Direction, Long> applyExternalEnergyRouting(Direction input, long amount, Set<Direction> candidates) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(candidates, "candidates");
        if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
        LinkedHashMap<Direction, Long> weights = initialRouteWeights(candidates);
        for (PipeComponent component : apiComponents) {
            if (!(component instanceof ExternalEnergyRouteComponent router)) continue;
            Set<Direction> active = activeDirections(weights);
            if (active.isEmpty()) break;
            try {
                applyRouteDecision(weights, router.route(new ExternalEnergyRouteContext(this, input, amount, active)).weights());
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe FE routing component failed at {}", position(), failure);
            }
        }
        return Collections.unmodifiableMap(weights);
    }

    private static LinkedHashMap<Direction, Long> initialRouteWeights(Iterable<Direction> candidates) {
        LinkedHashMap<Direction, Long> weights = new LinkedHashMap<>();
        for (Direction candidate : candidates) weights.putIfAbsent(Objects.requireNonNull(candidate, "candidate"), 1L);
        return weights;
    }

    private static Set<Direction> activeDirections(Map<Direction, Long> weights) {
        LinkedHashSet<Direction> active = new LinkedHashSet<>();
        for (Map.Entry<Direction, Long> entry : weights.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) active.add(entry.getKey());
        }
        return active;
    }

    private static void applyRouteDecision(Map<Direction, Long> weights, Map<Direction, Integer> decision) {
        Objects.requireNonNull(decision, "route decision");
        LinkedHashMap<Direction, Long> nextWeights = new LinkedHashMap<>(weights);
        for (Map.Entry<Direction, Integer> entry : decision.entrySet()) {
            if (!nextWeights.containsKey(entry.getKey())) continue;
            int multiplier = Objects.requireNonNull(entry.getValue(), "route weight");
            if (multiplier <= 0) {
                nextWeights.put(entry.getKey(), 0L);
            } else {
                long previous = nextWeights.get(entry.getKey());
                long next = previous > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : previous * multiplier;
                nextWeights.put(entry.getKey(), next);
            }
        }
        weights.clear();
        weights.putAll(nextWeights);
    }

    private List<Direction> weightedOrder(Map<Direction, Long> source) {
        return WeightedOrder.order(source, () -> holder.getPipeWorld().getRandom().nextLong());
    }

    private boolean canConnectToPipeApiAware(Direction side, IPipe other, BlockState neighbourState) {
        if (!canColoursConnect(getColour(), other.getColour()) || !canFlowsConnect(side, getFlow(), other.getFlow())) {
            return false;
        }
        PipeConnectionDecision thisDecision = apiConnectionDecision(side, neighbourState);
        PipeConnectionDecision otherDecision = PipeConnectionDecision.PASS;
        if (other instanceof Pipe otherPipe) {
            otherDecision = otherPipe.apiConnectionDecision(
                side.getOpposite(), holder.getPipeWorld().getBlockState(holder.getPipePos())
            );
        }
        if (thisDecision == PipeConnectionDecision.DENY || otherDecision == PipeConnectionDecision.DENY) return false;
        if (thisDecision == PipeConnectionDecision.ALLOW || otherDecision == PipeConnectionDecision.ALLOW) return true;
        return canBehavioursConnect(side, getBehaviour(), other.getBehaviour());
    }

    private PipeConnectionDecision apiConnectionDecision(Direction side, BlockState neighbourState) {
        PipeConnectionContext context = new PipeConnectionContext(holder.getPipeWorld(), this, side, neighbourState);
        PipeConnectionDecision result = PipeConnectionDecision.PASS;
        for (PipeComponent component : apiComponents) {
            if (!(component instanceof PipeConnectionComponent connection)) continue;
            PipeConnectionDecision current;
            try {
                current = Objects.requireNonNull(connection.connection(context), "connection decision");
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Pipe connection component failed at {}", position(), failure);
                return PipeConnectionDecision.DENY;
            }
            if (current == PipeConnectionDecision.DENY) return current;
            if (current == PipeConnectionDecision.ALLOW) result = current;
        }
        for (PipeConnectionRule rule : BuildCraftApi.registry(BuildCraftRegistries.PIPE_CONNECTION_RULES).values()) {
            PipeConnectionDecision current = Objects.requireNonNull(rule.decide(context), "connection rule decision");
            if (current == PipeConnectionDecision.DENY) return current;
            if (current == PipeConnectionDecision.ALLOW) result = current;
        }
        return result;
    }

    private static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction fluidAction(OperationMode mode) {
        return mode == OperationMode.EXECUTE ? net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE : net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;
    }

    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        Item item = (Item) PipeApi.pipeRegistry.getItemForPipe(definition);
        if (item != null) {
            ItemStack drop = new ItemStack(item, 1);
            ItemPipeHolder.setPipeColor(drop, colour);
            toDrop.add(drop);
        }
        flow.addDrops(toDrop, fortune);
        behaviour.addDrops(toDrop, fortune);
        apiRuntime.addDrops(toDrop, fortune);
    }

    public static boolean canPipesConnect(Direction to, IPipe one, IPipe two) {
        return canColoursConnect(one.getColour(), two.getColour())//
        && canBehavioursConnect(to, one.getBehaviour(), two.getBehaviour())//
        && canFlowsConnect(to, one.getFlow(), two.getFlow());
    }

    public static boolean canColoursConnect(DyeColor one, DyeColor two) {
        return one == null || two == null || one == two;
    }

    public static boolean canBehavioursConnect(Direction to, PipeBehaviour one, PipeBehaviour two) {
        return one.canConnect(to, two) && two.canConnect(to.getOpposite(), one);
    }

    public static boolean canFlowsConnect(Direction to, PipeFlow one, PipeFlow two) {
        return one.canConnect(to, two) && two.canConnect(to.getOpposite(), one);
    }

    public void markForUpdate() {
        updateMarked = true;
    }
    public PipeModelKey getModel() {
        PipeFaceTex[] sides = new PipeFaceTex[6];
        float[] mc = new float[6];
        for (Direction face : Direction.values()) {
            int i = face.ordinal();
            sides[i] = behaviour.getTextureData(face);
            mc[i] = getConnectedDist(face);
        }
        return new PipeModelKey(definition, behaviour.getTextureData(null), sides, mc, colour);
    }

    public BlockEntity getConnectedTile(Direction side) {
        if (connected.containsKey(side)) {
            BlockEntity offset = getHolder().getNeighbourTile(side);
            if (offset == null && !getHolder().getPipeWorld().isClientSide()) {
                markForUpdate();
            } else {
                return offset;
            }
        }
        return null;
    }

    public IPipe getConnectedPipe(Direction side) {
        if (connected.containsKey(side) && getConnectedType(side) == ConnectedType.PIPE) {
            IPipe offset = getHolder().getNeighbourPipe(side);
            if (offset == Pipe.EMPTY && !getHolder().getPipeWorld().isClientSide()) {
                markForUpdate();
            } else {
                return offset;
            }
        }
        return Pipe.EMPTY;
    }

    public ConnectedType getConnectedType(Direction side) {
        return types.get(side);
    }

    public boolean isConnected(Direction side) {
        return connected.containsKey(side);
    }

    public float getConnectedDist(Direction face) {
        Float custom = connected.get(face);
        return custom == null ? 0 : custom;
    }

    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("Colour = " + colour);
        left.add("Definition = " + definition.identifier);
        if (behaviour instanceof IDebuggable) {
            left.add("Behaviour:");
            ((IDebuggable) behaviour).getDebugInfo(left, right, side);
            left.add("");
        } else {
            left.add("Behaviour = " + behaviour.getClass());
        }

        if (flow instanceof IDebuggable) {
            left.add("Flow:");
            ((IDebuggable) flow).getDebugInfo(left, right, side);
            left.add("");
        } else {
            left.add("Flow = " + flow.getClass());
        }
        for (Direction face : Direction.values()) {
            right.add(face + " = " + types.get(face) + ", " + getConnectedDist(face));
        }
    }

	public void rotate(Rotation rot) {
		Map<Direction, Float> copyConnected = new EnumMap<Direction, Float>(connected);
		Map<Direction, ConnectedType> copyTypes = new EnumMap<Direction, ConnectedType>(types);
		connected.clear();
		types.clear();
		for(Direction dir : Direction.values()) {
			Direction targetDir = rot.rotate(dir);
			connected.put(targetDir, copyConnected.get(dir));
			types.put(targetDir, copyTypes.get(dir));
		}
		behaviour.rotate(rot);
	}
}
