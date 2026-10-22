package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.signal.BuildCraftSignalChannels;
import buildcraft.api.v2.signal.SignalChannelType;
import buildcraft.api.v2.signal.SignalEndpoint;
import buildcraft.api.v2.signal.SignalNetworkView;
import buildcraft.api.v2.signal.SignalPort;
import buildcraft.api.v2.signal.SignalPortProvider;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.signal.SignalService;
import buildcraft.api.v2.signal.SignalUpdateResult;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.wire.WireManager;
import buildcraft.transport.wire.WireSystem;
import buildcraft.transport.wire.WorldSavedDataWireSystems;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Runtime bridge from API2 signal ports to the classic BuildCraft pipe-wire graph. */
public final class SignalServiceImpl implements SignalService {
    public static final SignalServiceImpl INSTANCE = new SignalServiceImpl();

    private SignalServiceImpl() {}

    @Override
    public Optional<SignalPort<?>> port(Level level, BlockPos pos, Direction side, ResourceLocation channelId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(channelId, "channelId");

        DyeColor color = BuildCraftSignalChannels.color(channelId).orElse(null);
        if (color == null) return Optional.empty();
        SignalChannelType<?> rawType = BuildCraftApi.registry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).get(channelId);
        if (rawType == null) return Optional.empty();

        if (!level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof SignalPortProvider provider) {
            Optional<? extends SignalPort<?>> provided = provider.signalPort(side, channelId);
            if (provided.isEmpty() || provided.get().channel() != rawType) return Optional.empty();
            return Optional.of(new ExternalSignalPort(level, pos, side, color, provided.get()));
        }
        if (!(blockEntity instanceof TilePipeHolder holder)) {
            if (!level.hasChunkAt(pos.relative(side))) return Optional.empty();
            BlockEntity adjacent = level.getBlockEntity(pos.relative(side));
            if (!(adjacent instanceof TilePipeHolder neighbour)) return Optional.empty();
            PipeView pipe = neighbour.getPipe();
            if (pipe == null) return Optional.empty();
            PipeExternalEndpoint endpoint = PipeEndpointRuntime.find(level, pipe, side.getOpposite()).orElse(null);
            if (endpoint == null) return Optional.empty();
            try {
                SignalPort<?> supplied = endpoint.signalPort(channelId).orElse(null);
                return supplied == null || supplied.channel() != rawType ? Optional.empty()
                    : Optional.of(new ExternalSignalPort(level, pos, side, color, supplied));
            } catch (RuntimeException ex) { return Optional.empty(); }
        }
        try {
            SignalPort<?> attachment = PipeEndpointRuntime.attachmentPort(holder.getPluggable(side),
                plug -> plug.signalPort(channelId).map(port -> (SignalPort<?>) port)).orElse(null);
            if (attachment != null && attachment.channel() == rawType) {
                return Optional.of(new ExternalSignalPort(level, pos.relative(side),
                    side.getOpposite(), color, attachment));
            }
        } catch (RuntimeException ex) { return Optional.empty(); }
        WireManager manager = holder.getWireManager();

        @SuppressWarnings("unchecked")
        SignalChannelType<Boolean> type = (SignalChannelType<Boolean>) rawType;
        return Optional.of(new ClassicWirePort(manager, side, color, type));
    }

    @Override
    public Collection<? extends SignalNetworkView<?>> networks(Level level) {
        Objects.requireNonNull(level, "level");
        if (level.isClientSide()) return List.of();

        WorldSavedDataWireSystems saved = WorldSavedDataWireSystems.get(level);
        List<SignalNetworkView<?>> result = new ArrayList<>();
        saved.wireSystems.forEach((wireSystem, powered) -> {
            ResourceLocation id = BuildCraftSignalChannels.id(wireSystem.color);
            SignalChannelType<?> rawType = BuildCraftApi.registry(BuildCraftRegistries.SIGNAL_CHANNEL_TYPES).get(id);
            if (rawType == null) return;
            @SuppressWarnings("unchecked")
            SignalChannelType<Boolean> type = (SignalChannelType<Boolean>) rawType;
            LinkedHashSet<SignalEndpoint<Boolean>> endpoints = new LinkedHashSet<>();
            for (WireSystem.WireElement element : wireSystem.elements) {
                if (element.type == WireSystem.WireElement.Type.EMITTER_SIDE) {
                    endpoints.add(new SignalEndpoint<>(element.blockPos, element.emitterSide, type));
                } else if (element.type == WireSystem.WireElement.Type.WIRE_PART) {
                    for (Direction side : Direction.values()) {
                        endpoints.add(new SignalEndpoint<>(element.blockPos, side, type));
                    }
                }
            }
            result.add(new ClassicWireNetwork(type, Boolean.TRUE.equals(powered), List.copyOf(endpoints)));
        });
        return List.copyOf(result);
    }


    private static final class ExternalSignalPort implements SignalPort<Object> {
        private final Level level;
        private final BlockPos pos;
        private final Direction side;
        private final DyeColor color;
        private final SignalPort<Object> delegate;

        @SuppressWarnings("unchecked")
        private ExternalSignalPort(Level level, BlockPos pos, Direction side, DyeColor color, SignalPort<?> delegate) {
            this.level = level;
            this.pos = pos;
            this.side = side;
            this.color = color;
            this.delegate = (SignalPort<Object>) delegate;
        }

        @Override public SignalChannelType<Object> channel() { return delegate.channel(); }
        @Override public boolean connected() { return hasMatchingWire(); }
        @Override public Object value() { return delegate.value(); }
        @Override public Object publishedValue() { return delegate.publishedValue(); }
        @Override public SignalUpdateResult<Object> receive(Object value, OperationMode mode) { return delegate.receive(value, mode); }

        @Override
        public SignalUpdateResult<Object> publish(Object value, OperationMode mode) {
            SignalUpdateResult<Object> result = delegate.publish(value, mode);
            if (mode == OperationMode.EXECUTE && result.changed() && !level.isClientSide()) {
                BlockEntity adjacent = level.getBlockEntity(pos.relative(side));
                if (adjacent instanceof TilePipeHolder holder && holder.getWireManager().hasPartOfColor(color)) {
                    WorldSavedDataWireSystems.get(level).gatesChanged = true;
                    holder.getWireManager().updateBetweens(false);
                }
            }
            return result;
        }

        private boolean hasMatchingWire() {
            BlockEntity adjacent = level.getBlockEntity(pos.relative(side));
            return adjacent instanceof TilePipeHolder holder && holder.getWireManager().hasPartOfColor(color);
        }
    }

    private record ClassicWireNetwork(
        SignalChannelType<Boolean> channel,
        Boolean value,
        Collection<SignalEndpoint<Boolean>> endpoints
    ) implements SignalNetworkView<Boolean> {}

    private static final class ClassicWirePort implements SignalPort<Boolean> {
        private final WireManager manager;
        private final Direction side;
        private final DyeColor color;
        private final SignalChannelType<Boolean> channel;

        private ClassicWirePort(WireManager manager, Direction side, DyeColor color, SignalChannelType<Boolean> channel) {
            this.manager = manager;
            this.side = side;
            this.color = color;
            this.channel = channel;
        }

        @Override public SignalChannelType<Boolean> channel() { return channel; }
        @Override public boolean connected() { return manager.hasPartOfColor(color); }
        @Override public Boolean value() { return connected() && manager.isAnyPowered(color); }
        @Override public Boolean publishedValue() { return manager.isSignalOutputActive(side, color); }

        @Override
        public SignalUpdateResult<Boolean> publish(Boolean value, OperationMode mode) {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(mode, "mode");
            boolean previous = manager.isSignalOutputActive(side, color);
            if (mode == OperationMode.EXECUTE && !manager.getHolder().getPipeWorld().isClientSide()) {
                manager.setSignalOutput(side, color, value);
            }
            return new SignalUpdateResult<>(previous != value, previous, value);
        }
    }
}
