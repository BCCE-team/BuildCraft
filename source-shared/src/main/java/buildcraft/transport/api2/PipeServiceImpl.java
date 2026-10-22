package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.ItemInjectionRequest;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeAttachmentPlacementContext;
import buildcraft.api.v2.pipe.PipeAttachmentResult;
import buildcraft.api.v2.pipe.PipeAttachmentType;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.api.v2.pipe.PipeService;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.PipeRegistry;
import buildcraft.transport.tile.TilePipeHolder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Runtime implementation of the public API2 pipe facade. */
public final class PipeServiceImpl implements PipeService {
    public static final PipeServiceImpl INSTANCE = new PipeServiceImpl();

    private PipeServiceImpl() {}

    @Override
    public Item createItem(ResourceLocation pipeTypeId) {
        Objects.requireNonNull(pipeTypeId, "pipeTypeId");
        PipeType type = BuildCraftApi.registry(BuildCraftRegistries.PIPE_TYPES).get(pipeTypeId);
        if (type == null) throw new IllegalArgumentException("Unknown API2 pipe type: " + pipeTypeId);
        var definition = PipeRegistry.INSTANCE.ensureRuntimeDefinition(type);
        IItemPipe existing = PipeRegistry.INSTANCE.getItemForPipe(definition);
        if (existing instanceof Item item) return item;
        return PipeRegistry.INSTANCE.createItemForPipe(definition);
    }

    @Override
    public Optional<PipeView> pipe(Level level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        if (!level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) return Optional.empty();
        Pipe pipe = holder.getPipe();
        return pipe == null || pipe == Pipe.EMPTY ? Optional.empty() : Optional.of(ReadOnlyPipeView.of(pipe));
    }

    @Override
    public Optional<PipeExternalEndpoint> externalEndpoint(Level level, BlockPos pos, Direction side) {
        Objects.requireNonNull(side, "side");
        return pipe(level, pos).flatMap(view -> PipeEndpointRuntime.find(level, view, side));
    }

    @Override
    public PipeAttachmentResult placeAttachment(
        Level level,
        BlockPos pos,
        Direction side,
        ResourceLocation attachmentTypeId,
        ItemStack stack,
        AutomationActor actor,
        OperationMode mode
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(attachmentTypeId, "attachmentTypeId");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");

        if (mode == OperationMode.EXECUTE && level.isClientSide()) return PipeAttachmentResult.failure("server_only");
        if (!level.hasChunkAt(pos)) return PipeAttachmentResult.failure("unloaded_pipe");
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) return PipeAttachmentResult.failure("not_a_pipe");
        Pipe pipe = holder.getPipe();
        if (pipe == null || pipe == Pipe.EMPTY) return PipeAttachmentResult.failure("empty_pipe_holder");
        if (holder.getPluggable(side) != PipePluggable.EMPTY) return PipeAttachmentResult.failure("side_occupied");

        PipeAttachmentType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES).get(attachmentTypeId);
        if (type == null) return PipeAttachmentResult.failure("unknown_attachment_type");

        final PipeAttachment attachment;
        try {
            PipeView view = PipeAttachmentBridge.isLegacy(attachmentTypeId) ? pipe : ReadOnlyPipeView.of(pipe);
            attachment = type.create(new PipeAttachmentPlacementContext(view, side, stack, actor, mode));
        } catch (RuntimeException ex) {
            return PipeAttachmentResult.failure("attachment_factory_failed:" + ex.getClass().getSimpleName());
        }
        if (attachment == null || !attachmentTypeId.equals(attachment.typeId()) || attachment.side() != side) {
            return PipeAttachmentResult.failure("incorrect_attachment_instance");
        }
        if (mode == OperationMode.EXECUTE) {
            if (attachment instanceof LegacyPipeAttachmentView legacy) {
                holder.replacePluggable(side, legacy.internalPluggable());
            } else {
                var definition = PipeAttachmentBridge.definition(attachmentTypeId);
                if (definition == null) return PipeAttachmentResult.failure("unknown_attachment_definition");
                ApiPipePluggable adapter = new ApiPipePluggable(definition, holder, side, type, attachment);
                try {
                    adapter.writeToNbt();
                } catch (RuntimeException ex) {
                    return PipeAttachmentResult.failure("attachment_state_invalid");
                }
                holder.replacePluggable(side, adapter);
                adapter.installed();
            }
        }
        return PipeAttachmentResult.success(attachment);
    }

    @Override
    public boolean removeAttachment(
        Level level,
        BlockPos pos,
        Direction side,
        AutomationActor actor,
        OperationMode mode
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        if (mode == OperationMode.EXECUTE && level.isClientSide()) return false;
        if (!level.hasChunkAt(pos)) return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) return false;
        PipePluggable current = holder.getPluggable(side);
        if (current == null || current == PipePluggable.EMPTY) return false;
        if (mode == OperationMode.EXECUTE) {
            holder.replacePluggable(side, PipePluggable.EMPTY);
            current.onRemove();
        }
        return true;
    }

    @Override
    public ItemTransferResult injectItem(Level level, BlockPos pos, ItemInjectionRequest request, OperationMode mode) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mode, "mode");
        if (!level.hasChunkAt(pos)) return ItemTransferResult.nothing(request.stack().getCount());
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder holder)) {
            return ItemTransferResult.nothing(request.stack().getCount());
        }
        Pipe pipe = holder.getPipe();
        if (pipe == null || pipe == Pipe.EMPTY) {
            return ItemTransferResult.nothing(request.stack().getCount());
        }
        return pipe.itemPipePort(request.from())
            .map(port -> port.inject(request, mode))
            .orElseGet(() -> ItemTransferResult.nothing(request.stack().getCount()));
    }
}
