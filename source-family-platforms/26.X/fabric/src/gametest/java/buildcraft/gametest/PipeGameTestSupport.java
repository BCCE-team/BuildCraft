package buildcraft.gametest;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import buildcraft.lib.platform.capability.BlockCapability;

import buildcraft.transport.wire.WireManager;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeEvent;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;

public final class PipeGameTestSupport {
    public static final String LARGE_EMPTY_TEMPLATE = "empty7x3x7";

    private PipeGameTestSupport() {
    }

    public static TilePipeHolder placePipe(GameTestHelper helper, BlockPos pos, PipeDefinition definition) {
        helper.setBlock(pos, BCTransportBlocks.pipeHolder.get().defaultBlockState());
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) {
            helper.fail("pipe holder block did not create TilePipeHolder at " + pos);
            throw new IllegalStateException("missing TilePipeHolder");
        }

        IItemPipe itemPipe = PipeApi.pipeRegistry.getItemForPipe(definition);
        if (!(itemPipe instanceof Item item)) {
            helper.fail("pipe definition has no registered item: " + definition.identifier);
            throw new IllegalStateException("missing pipe item");
        }
        holder.onPlacedBy(null, new ItemStack(item));
        if (holder.getPipe() == Pipe.EMPTY) {
            helper.fail("placing pipe item did not initialise pipe at " + pos);
            throw new IllegalStateException("pipe was not initialised");
        }
        return holder;
    }

    public static int countItem(net.minecraft.world.Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Minimal pipe implementation for behaviour-only GameTests. */
    public static final class TestPipe implements IPipe {
        private final TestHolder holder;
        private final Map<Direction, ConnectedType> connections = new EnumMap<>(Direction.class);
        private final Map<Direction, IPipe> connectedPipes = new EnumMap<>(Direction.class);
        private final Map<Direction, BlockEntity> connectedTiles = new EnumMap<>(Direction.class);
        private PipeDefinition definition;
        private PipeBehaviour behaviour;
        private PipeFlow flow;
        private net.minecraft.world.item.DyeColor colour;

        public TestPipe(Level level) {
            this(level, null);
        }

        public TestPipe(Level level, @Nullable PipeDefinition definition) {
            this.definition = definition;
            this.holder = new TestHolder(level, this);
        }

        public TestPipe connect(Direction side, ConnectedType type) {
            connections.put(side, type);
            return this;
        }

        public TestPipe connectPipe(Direction side, IPipe other) {
            connections.put(side, ConnectedType.PIPE);
            connectedPipes.put(side, other);
            return this;
        }

        public TestPipe connectTile(Direction side, @Nullable BlockEntity tile) {
            connections.put(side, ConnectedType.TILE);
            if (tile != null) {
                connectedTiles.put(side, tile);
            }
            return this;
        }

        public TestPipe setDefinition(@Nullable PipeDefinition definition) {
            this.definition = definition;
            return this;
        }

        public <T> TestPipe exposeCapability(Direction side, BlockCapability<T, Direction> capability, T value) {
            holder.exposeCapability(side, capability, value);
            return this;
        }

        public void setBehaviour(PipeBehaviour behaviour) {
            this.behaviour = behaviour;
        }

        public void setFlow(PipeFlow flow) {
            this.flow = flow;
        }

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

        public net.minecraft.world.item.DyeColor getColour() {
            return colour;
        }

        public void setColour(net.minecraft.world.item.DyeColor colour) {
            this.colour = colour;
        }

        public void markForUpdate() {
        }

        public BlockEntity getConnectedTile(Direction side) {
            return connectedTiles.get(side);
        }

        public IPipe getConnectedPipe(Direction side) {
            return connectedPipes.getOrDefault(side, Pipe.EMPTY);
        }

        public boolean isConnected(Direction side) {
            return connections.containsKey(side);
        }

        public ConnectedType getConnectedType(Direction side) {
            return connections.get(side);
        }

        public void rotate(net.minecraft.world.level.block.Rotation rotation) {
        }

        public <T> @Nullable T getCapability(@Nonnull BlockCapability<T, Direction> capability, @Nullable Direction side) {
            return null;
        }
    }

    private static final class TestHolder implements IPipeHolder {
        private static final GameProfile OWNER = new GameProfile(new UUID(0L, 1L), "BuildCraftGameTest");

        private final Level level;
        private final IPipe pipe;
        // Transactional 26.3 flows mark the holder's backing block entity dirty on root commit.
        // The test holder must obey the same non-null contract as a placed pipe holder.
        private final BlockEntity backingTile = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        private final Map<Direction, Map<BlockCapability<?, Direction>, Object>> capabilities = new EnumMap<>(Direction.class);

        private TestHolder(Level level, IPipe pipe) {
            this.level = level;
            this.pipe = pipe;
        }

        public Level getPipeWorld() {
            return level;
        }

        public BlockPos getPipePos() {
            return BlockPos.ZERO;
        }

        public BlockEntity getPipeTile() {
            return backingTile;
        }

        public IPipe getPipe() {
            return pipe;
        }

        public boolean canPlayerInteract(Player player) {
            return false;
        }

        public PipePluggable getPluggable(Direction side) {
            return PipePluggable.EMPTY;
        }

        public BlockEntity getNeighbourTile(Direction side) {
            return pipe.getConnectedTile(side);
        }

        public IPipe getNeighbourPipe(Direction side) {
            return pipe.getConnectedPipe(side);
        }

        private <T> void exposeCapability(Direction side, BlockCapability<T, Direction> capability, T value) {
            capabilities.computeIfAbsent(side, ignored -> new IdentityHashMap<>()).put(capability, value);
        }

        @SuppressWarnings("unchecked")
        public <T> @Nullable T getCapabilityFromPipe(Direction side, @Nonnull BlockCapability<T, Direction> capability) {
            Map<BlockCapability<?, Direction>, Object> byCapability = capabilities.get(side);
            return byCapability == null ? null : (T) byCapability.get(capability);
        }

        public WireManager getWireManager() {
            return null;
        }

        public GameProfile getOwner() {
            return OWNER;
        }

        public boolean fireEvent(PipeEvent event) {
            return false;
        }

        public void scheduleRenderUpdate() {
        }

        public void scheduleNetworkUpdate(PipeMessageReceiver... parts) {
        }

        public void scheduleNetworkGuiUpdate(PipeMessageReceiver... parts) {
        }

        public void sendMessage(PipeMessageReceiver to, IWriter writer) {
        }

        public void sendGuiMessage(PipeMessageReceiver to, IWriter writer) {
        }

        public void onPlayerOpen(Player player) {
        }

        public void onPlayerClose(Player player) {
        }

        public int getRedstoneInput(Direction side) {
            return 0;
        }

        public boolean setRedstoneOutput(Direction side, int value) {
            return false;
        }
    }
}
