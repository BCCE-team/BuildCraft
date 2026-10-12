package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.pipe.ItemTransitComponent;
import buildcraft.api.v2.pipe.ItemTransitContext;
import buildcraft.api.v2.pipe.ItemTransitData;
import buildcraft.api.v2.pipe.ItemTransitPhase;
import buildcraft.api.v2.pipe.ItemTransitStatus;
import buildcraft.api.v2.pipe.ItemTransitTransfer;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.StackUtil;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Applies addon transit actions while PipeFlowItems owns the item being processed. */
public final class PipeItemTransitRuntime {
    private PipeItemTransitRuntime() { }

    public record Result(ItemStack stack, ItemTransitData transit, UUID id,
                         Direction redirected, int delay, Direction eject) {
        public Result {
            stack = stack.copy();
        }
    }

    public static Result fire(Pipe pipe, ItemTransitPhase phase, ItemStack stack,
                              ItemTransitData transit, Direction input, Direction output, UUID id) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(transit, "transit");
        boolean hasListener = false;
        for (PipeComponent component : pipe.components()) {
            if (component instanceof ItemTransitComponent) { hasListener = true; break; }
        }
        if (!hasListener) return new Result(stack, transit, id, null, 0, null);
        Context context = new Context(pipe, phase, stack, transit, input, output, id);
        try {
            for (PipeComponent component : pipe.components()) {
                if (!(component instanceof ItemTransitComponent listener)) continue;
                try {
                    listener.onTransit(context);
                } catch (RuntimeException ex) {
                    BCLog.logger.warn("Item transit callback failed at {} for {}",
                        pipe.position(), component.typeId(), ex);
                }
            }
        } finally {
            context.open = false;
        }
        return new Result(context.stack, context.transit, context.id,
            context.redirected, context.delay, context.eject);
    }

    private static final class Context implements ItemTransitContext {
        private final Pipe pipe;
        private final PipeView view;
        private final ItemTransitPhase phase;
        private final Direction input;
        private final Direction output;
        private ItemStack stack;
        private ItemTransitData transit;
        private UUID id;
        private Direction redirected;
        private int delay;
        private Direction eject;
        private boolean open = true;

        private Context(Pipe pipe, ItemTransitPhase phase, ItemStack stack, ItemTransitData transit,
                        Direction input, Direction output, UUID id) {
            this.pipe = pipe;
            this.view = ReadOnlyPipeView.of(pipe);
            this.phase = phase;
            this.stack = stack.copy();
            this.transit = transit;
            this.input = input;
            this.output = output;
            this.id = id;
        }

        private void checkOpen() {
            if (!open) throw new IllegalStateException("Expired item transit context");
        }

        private boolean writable() {
            checkOpen();
            Level level = pipe.getHolder().getPipeWorld();
            return level != null && !level.isClientSide() && level.getServer() != null
                && level.getServer().isSameThread()
                && (phase == ItemTransitPhase.CENTER || phase == ItemTransitPhase.ROUTING
                    || phase == ItemTransitPhase.EXIT)
                && delay == 0 && eject == null && !stack.isEmpty();
        }

        @Override public PipeView pipe() { checkOpen(); return view; }
        @Override public ItemTransitPhase phase() { checkOpen(); return phase; }
        @Override public ItemStack stack() { checkOpen(); return stack.copy(); }
        @Override public ItemTransitData transit() { checkOpen(); return transit; }
        @Override public Optional<Direction> input() { checkOpen(); return Optional.ofNullable(input); }
        @Override public Optional<Direction> output() { checkOpen(); return Optional.ofNullable(redirected == null ? output : redirected); }
        @Override public UUID transitId() {
            checkOpen();
            if (id == null) id = UUID.randomUUID();
            return id;
        }

        @Override
        public boolean replace(ItemStack replacement, ItemTransitData metadata) {
            Objects.requireNonNull(replacement, "replacement");
            Objects.requireNonNull(metadata, "metadata");
            if (!writable() || replacement.isEmpty() || replacement.getCount() != stack.getCount()
                || metadata.speedBlocksPerTick() > 1.0) return false;
            this.stack = replacement.copy();
            this.transit = metadata;
            return true;
        }

        @Override
        public boolean redirect(Direction side) {
            Objects.requireNonNull(side, "side");
            if (!writable() || phase == ItemTransitPhase.EXIT || !pipe.isConnected(side)) return false;
            redirected = side;
            return true;
        }

        @Override
        public boolean delay(int ticks) {
            if (!writable() || ticks < 1 || ticks > 1200) return false;
            delay = ticks;
            return true;
        }

        @Override
        public boolean reject() { return delay(20); }

        @Override
        public boolean eject(Direction side) {
            Objects.requireNonNull(side, "side");
            if (!writable()) return false;
            eject = side;
            return true;
        }

        @Override
        public ItemTransitTransfer claim(ItemPort port, OperationMode mode) {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(mode, "mode");
            if (!writable()) return ItemTransitTransfer.blocked(stack.getCount(), ItemTransitStatus.BLOCKED);
            ItemStack offered = stack.copy();
            final ItemTransferResult result;
            try {
                result = port.insert(offered.copy(), mode);
            } catch (RuntimeException ex) {
                BCLog.logger.warn("Addon item port failed to accept a claimed pipe item", ex);
                return ItemTransitTransfer.blocked(offered.getCount(), ItemTransitStatus.FAILED);
            }
            if (result == null || result.requestedCount() != offered.getCount()
                || result.transferredCount() < 0 || result.transferredCount() > offered.getCount()
                || (result.movedAnything() && !StackUtil.canMerge(offered, result.transferred()))) {
                return ItemTransitTransfer.blocked(offered.getCount(), ItemTransitStatus.FAILED);
            }
            int accepted = result.transferredCount();
            if (mode == OperationMode.EXECUTE && accepted > 0) {
                stack.shrink(accepted);
                if (!stack.isEmpty()) delay = 1;
                pipe.markChanged();
                fire(pipe, ItemTransitPhase.TRANSFERRED, result.transferred(), transit, input, output, id);
            }
            return new ItemTransitTransfer(offered.getCount(), accepted,
                mode == OperationMode.SIMULATE ? ItemTransitStatus.SIMULATED
                    : accepted == 0 ? ItemTransitStatus.BLOCKED
                    : accepted == offered.getCount() ? ItemTransitStatus.MOVED : ItemTransitStatus.PARTIAL);
        }

        @Override
        public ItemTransitTransfer transfer(Level targetWorld, BlockPos targetPos, Direction enteringFrom,
                                            OperationMode mode) {
            Objects.requireNonNull(targetWorld, "targetWorld");
            Objects.requireNonNull(targetPos, "targetPos");
            Objects.requireNonNull(enteringFrom, "enteringFrom");
            Objects.requireNonNull(mode, "mode");
            if (!writable()) return ItemTransitTransfer.blocked(stack.getCount(), ItemTransitStatus.BLOCKED);
            Level sourceWorld = pipe.getHolder().getPipeWorld();
            int offeredCount = stack.getCount();
            if (targetWorld.isClientSide() || sourceWorld.getServer() == null
                || targetWorld.getServer() != sourceWorld.getServer()) {
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.SERVER_ONLY);
            }
            if (sourceWorld == targetWorld && pipe.position().equals(targetPos)) {
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.INVALID_TARGET);
            }
            if (!targetWorld.hasChunkAt(targetPos)) {
                if (mode == OperationMode.EXECUTE) delay = 20;
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.UNLOADED);
            }
            BlockEntity tile = targetWorld.getBlockEntity(targetPos);
            if (!(tile instanceof TilePipeHolder targetHolder)) {
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.INVALID_TARGET);
            }
            Pipe target = targetHolder.getPipe();
            if (target == null || target == Pipe.EMPTY || !(target.flow instanceof PipeFlowItems flow)) {
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.INVALID_TARGET);
            }
            UUID token = mode == OperationMode.SIMULATE ? id : transitId();
            ItemStack offered = stack.copy();
            ItemStack excess;
            try {
                excess = flow.injectTransitStack(offered, mode == OperationMode.EXECUTE, enteringFrom,
                    transit.color().orElse(null), transit.speedBlocksPerTick(), token);
            } catch (RuntimeException ex) {
                BCLog.logger.warn("Item transit target rejected a transfer at {}", targetPos, ex);
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.FAILED);
            }
            if (excess == null || excess.getCount() > offeredCount || (!excess.isEmpty() && !StackUtil.canMerge(offered, excess))) {
                return ItemTransitTransfer.blocked(offeredCount, ItemTransitStatus.FAILED);
            }
            int accepted = offeredCount - excess.getCount();
            if (mode == OperationMode.EXECUTE && accepted > 0) {
                stack = excess.copy();
                // A remainder is a separate ownership record, not a second copy of the delivered token.
                if (!stack.isEmpty()) { id = UUID.randomUUID(); delay = 1; }
                pipe.markChanged();
                ItemStack moved = offered.copy();
                moved.setCount(accepted);
                fire(pipe, ItemTransitPhase.TRANSFERRED, moved, transit, input, output, token);
            }
            return new ItemTransitTransfer(offeredCount, accepted,
                mode == OperationMode.SIMULATE ? ItemTransitStatus.SIMULATED
                    : accepted == 0 ? ItemTransitStatus.BLOCKED
                    : accepted == offeredCount ? ItemTransitStatus.MOVED : ItemTransitStatus.PARTIAL);
        }
    }
}
