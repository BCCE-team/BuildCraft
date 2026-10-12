package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.KnownPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.persistence.PayloadResolution;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeAttachmentPlacementContext;
import buildcraft.api.v2.pipe.PipeActivationContext;
import buildcraft.api.v2.pipe.PipeActivationResult;
import buildcraft.api.v2.pipe.PipeAttachmentType;
import buildcraft.api.v2.pipe.PipeExecutionContext;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistryBuilder;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistrySnapshot;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pluggable host for public PipeAttachment implementations. */
public final class ApiPipePluggable extends PipePluggable {
    private static final int MAGIC = 0x42434154;
    private static final int MAX_BYTES = 64 * 1024;
    private final PipeAttachmentType<?> attachmentType;
    private PipeAttachment attachment;
    private CompoundTag lastSaved;
    private boolean removed;
    private boolean tickFailed;

    public ApiPipePluggable(PluggableDefinition definition, IPipeHolder holder, Direction side,
        PipeAttachmentType<?> attachmentType, PipeAttachment attachment) {
        super(definition, holder, side);
        this.attachmentType = Objects.requireNonNull(attachmentType, "attachmentType");
        this.attachment = Objects.requireNonNull(attachment, "attachment");
        requireMatching(attachment);
    }

    private void requireMatching(PipeAttachment value) {
        if (!attachmentType.id().equals(value.typeId()) || value.side() != side) {
            throw new IllegalArgumentException("Wrong attachment type or side: " + value.typeId());
        }
    }

    public PipeAttachment attachment() { return attachment; }

    public void installed() {
        if (holder.getPipeWorld() != null && !holder.getPipeWorld().isClientSide()
            && holder.getPipe() instanceof PipeExecutionContext context) {
            try { attachment.placed(context); }
            catch (RuntimeException ex) { report("placed", ex); }
            try { context.requestAttachmentSync(side); }
            catch (RuntimeException ex) { report("initial sync", ex); }
        }
    }

    @Override public boolean isBlocking() {
        try { return attachment.blocksConnection(); }
        catch (RuntimeException ex) { report("connection", ex); return true; }
    }

    @Override public VoxelShape getBoundingBox() {
        try { return Objects.requireNonNull(attachment.boundingBox(), "bounding box"); }
        catch (RuntimeException ex) { report("bounds", ex); return Shapes.empty(); }
    }

    @Override public ItemStack getPickStack() {
        try { return Objects.requireNonNull(attachment.pickStack(), "pick stack").copy(); }
        catch (RuntimeException ex) { report("pick", ex); return ItemStack.EMPTY; }
    }

    @Override public void addDrops(NonNullList<ItemStack> drops, int fortune) {
        try {
            for (ItemStack item : attachment.drops(fortune)) {
                if (item != null && !item.isEmpty()) drops.add(item.copy());
            }
        } catch (RuntimeException ex) { report("drops", ex); }
    }

    @Override public void onTick() {
        if (removed || tickFailed) return;
        if (holder.getPipe() instanceof PipeExecutionContext context && context.isActive()) {
            try { attachment.tick(context); }
            catch (RuntimeException ex) { tickFailed = true; report("tick", ex); }
        }
    }

    @Override public InteractionResult onPluggableActivate(Player player, BlockHitResult hit, Level level) {
        if (removed || !(holder.getPipe() instanceof PipeExecutionContext context)) return InteractionResult.PASS;
        try {
            PipeActivationResult result = Objects.requireNonNull(attachment.activate(new PipeActivationContext(
                context, side, player.getMainHandItem(),
                AutomationActor.player(player.getUUID(), player.getName().getString()),
                level.isClientSide() ? OperationMode.SIMULATE : OperationMode.EXECUTE)), "activation result");
            return switch (result) {
                case PASS -> InteractionResult.PASS;
                case SUCCESS -> InteractionResult.SUCCESS;
                case DENIED, FAILED -> InteractionResult.FAIL;
            };
        } catch (RuntimeException ex) {
            report("activate", ex);
            return InteractionResult.FAIL;
        }
    }

    @Override public void onRemove() {
        if (removed) return;
        removed = true;
        try { attachment.removed(); }
        catch (RuntimeException ex) { report("remove", ex); }
    }

    @Override public CompoundTag writeToNbt() {
        if (attachmentType.persistence().isEmpty()) return new CompoundTag();
        try {
            EncodedPayload<OpaqueData> state = codec().encode(attachmentType.id(), attachment).valueOrThrow();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(sink)) {
                output.writeInt(MAGIC);
                output.writeUTF(state.typeId().toString());
                output.writeInt(state.schemaVersion());
                output.writeUTF(state.payload().format().toString());
                byte[] data = state.payload().bytes();
                if (data.length > MAX_BYTES) throw new IOException("Attachment data too large");
                output.writeInt(data.length);
                output.write(data);
            }
            if (sink.size() > MAX_BYTES) throw new IOException("Attachment envelope too large");
            CompoundTag tag = new CompoundTag();
            tag.putByteArray("apiAttachment", sink.toByteArray());
            lastSaved = tag.copy();
            return tag;
        } catch (RuntimeException | IOException ex) {
            report("save", ex);
            if (lastSaved != null) return lastSaved.copy();
            throw new IllegalStateException("Cannot preserve initial attachment state", ex);
        }
    }

    public static ApiPipePluggable load(PluggableDefinition definition, IPipeHolder holder, Direction side,
        PipeAttachmentType<?> type, CompoundTag state) {
        PipeAttachment attachment = decode(holder, side, type, state);
        ApiPipePluggable result = new ApiPipePluggable(definition, holder, side, type, attachment);
        result.lastSaved = state.copy();
        if (holder.getPipe() instanceof PipeExecutionContext context) attachment.restored(context);
        return result;
    }

    private static PipeAttachment decode(IPipeHolder holder, Direction side, PipeAttachmentType<?> type, CompoundTag tag) {
        if (type.persistence().isEmpty()) return createDefault(holder, side, type);
        if (!(tag.get("apiAttachment") instanceof ByteArrayTag bytes)) {
            throw new IllegalArgumentException("Missing persisted attachment data: " + type.id());
        }
        byte[] frame = bytes.getAsByteArray();
        if (frame.length > MAX_BYTES) throw new IllegalArgumentException("Oversized attachment data");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid attachment magic");
            ResourceLocation id = ResourceLocation.tryParse(input.readUTF());
            if (!type.id().equals(id) && !type.persistence().orElseThrow().aliases().contains(id)) {
                throw new IOException("Attachment type mismatch");
            }
            int schema = input.readInt();
            ResourceLocation format = ResourceLocation.tryParse(input.readUTF());
            int size = input.readInt();
            if (schema < 0 || format == null || size < 0 || size > MAX_BYTES || size > input.available()) {
                throw new IOException("Invalid attachment envelope");
            }
            byte[] data = new byte[size];
            input.readFully(data);
            if (input.available() != 0) throw new IOException("Trailing attachment data");
            PayloadResolution<PipeAttachment, OpaqueData> restored = codec(type).decode(
                new EncodedPayload<>(id, schema, new OpaqueData(format, data)),
                value -> new OpaqueData(value.format(), value.bytes()));
            if (restored instanceof KnownPayload<PipeAttachment, OpaqueData> known) {
                return known.value();
            }
            throw new IOException("Attachment codec or migration rejected " + id);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot restore attachment " + type.id(), ex);
        }
    }

    private static PipeAttachment createDefault(IPipeHolder holder, Direction side, PipeAttachmentType<?> type) {
        if (!(holder.getPipe() instanceof PipeExecutionContext pipe)) {
            throw new IllegalStateException("Attachment requires a live pipe");
        }
        return type.create(new PipeAttachmentPlacementContext(pipe, side, ItemStack.EMPTY,
            AutomationActor.unknown(), OperationMode.EXECUTE));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PersistenceRegistrySnapshot<PipeAttachment, OpaqueData> codec(PipeAttachmentType<?> type) {
        PersistentType<PipeAttachment, OpaqueData> persistence = (PersistentType) type.persistence().orElseThrow();
        return new PersistenceRegistryBuilder<PipeAttachment, OpaqueData>()
            .register(persistence, () -> type.id().getNamespace()).freeze();
    }

    private PersistenceRegistrySnapshot<PipeAttachment, OpaqueData> codec() { return codec(attachmentType); }

    @Override public void writeCreationPayload(FriendlyByteBuf buffer) { buffer.writeNbt(writeToNbt()); }

    public static ApiPipePluggable fromBuffer(PluggableDefinition definition, IPipeHolder holder,
        Direction side, PipeAttachmentType<?> type, FriendlyByteBuf buffer) throws InvalidInputDataException {
        CompoundTag tag = buffer.readNbt();
        try { return load(definition, holder, side, type, tag == null ? new CompoundTag() : tag); }
        catch (RuntimeException ex) {
            throw new InvalidInputDataException("Invalid attachment " + type.id() + ": " + ex.getMessage());
        }
    }

    @Override public CompoundTag writeSyncState(BCNetworkSide side) {
        return side == BCNetworkSide.SERVER ? writeToNbt() : null;
    }

    @Override public void readSyncState(CompoundTag state, BCNetworkSide side, BCPacketContext ctx)
        throws IOException {
        if (side != BCNetworkSide.CLIENT) return;
        try {
            PipeAttachment next = decode(holder, this.side, attachmentType, state);
            requireMatching(next);
            attachment = next;
            lastSaved = state.copy();
            holder.scheduleRenderUpdate();
        } catch (RuntimeException ex) {
            throw new IOException("Invalid attachment sync", ex);
        }
    }

    private void report(String callback, Exception ex) {
        BCLog.logger.warn("Attachment {} failed {} at {}", attachmentType.id(), callback, holder.getPipePos(), ex);
    }
}
