#!/usr/bin/env python3
"""Compile the real attachment adapters and exercise placement, persistence, migration and transport."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "source-shared/src/main/java/"
SOURCES = tuple(BASE + name for name in (
    "buildcraft/api/v2/persistence/ApiCodec.java",
    "buildcraft/api/v2/persistence/CodecResult.java",
    "buildcraft/api/v2/persistence/EncodedPayload.java",
    "buildcraft/api/v2/persistence/KnownPayload.java",
    "buildcraft/api/v2/persistence/OpaqueData.java",
    "buildcraft/api/v2/persistence/PayloadCopier.java",
    "buildcraft/api/v2/persistence/PayloadResolution.java",
    "buildcraft/api/v2/persistence/PersistentType.java",
    "buildcraft/api/v2/persistence/SchemaMigration.java",
    "buildcraft/api/v2/persistence/UnknownPayload.java",
    "buildcraft/api/v2/persistence/UnknownPayloadReason.java",
    "buildcraft/api/v2/registry/RegistrationContext.java",
    "buildcraft/api/v2/registry/RegistryEntry.java",
    "buildcraft/api/v2/registry/ApiRegistry.java",
    "buildcraft/lib/internal/api/v2/persistence/PersistenceRegistryBuilder.java",
    "buildcraft/lib/internal/api/v2/persistence/PersistenceRegistrySnapshot.java",
    "buildcraft/api/v2/pipe/PipeView.java",
    "buildcraft/api/v2/pipe/PipeComponent.java",
    "buildcraft/api/v2/pipe/PipeEndpointKind.java",
    "buildcraft/api/v2/pipe/PipeAttachment.java",
    "buildcraft/api/v2/pipe/PipeAttachmentFactory.java",
    "buildcraft/api/v2/pipe/PipeAttachmentPlacementContext.java",
    "buildcraft/api/v2/pipe/PipeAttachmentResult.java",
    "buildcraft/api/v2/pipe/PipeAttachmentType.java",
    "buildcraft/api/v2/pipe/PipeExecutionContext.java",
    "buildcraft/api/v2/pipe/PipeMutationContext.java",
    "buildcraft/api/v2/pipe/PipeActivationResult.java",
    "buildcraft/api/v2/pipe/PipeActivationContext.java",
    "buildcraft/api/v2/pipe/PipeService.java",
    "buildcraft/lib/registry/PluggableRegistry.java",
    "buildcraft/transport/internal/pluggable/IPluggableRegistry.java",
    "buildcraft/transport/internal/pluggable/PluggableDefinition.java",
    "buildcraft/transport/api2/ReadOnlyPipeView.java",
    "buildcraft/transport/api2/ApiPipePluggable.java",
    "buildcraft/transport/api2/PipeAttachmentBridge.java",
    "buildcraft/transport/api2/LegacyPipeAttachmentView.java",
    "buildcraft/transport/api2/PipeServiceImpl.java",
))
STUBS = {
    "javax/annotation/Nullable.java": '''package javax.annotation; public @interface Nullable {}''',
    "net/minecraft/resources/ResourceLocation.java": '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String s) {
        if (s == null) return null;
        String[] parts = s.split(":", 2);
        return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()
            ? new ResourceLocation(parts[0], parts[1]) : null;
    }
    public String getNamespace() { return namespace; }
    public String toString() { return namespace + ":" + path; }
}''',
    "net/minecraft/core/Direction.java": '''package net.minecraft.core;
public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }''',
    "net/minecraft/core/BlockPos.java": '''package net.minecraft.core;
public record BlockPos(int x, int y, int z) {
    public BlockPos relative(Direction face) { return this; }
}''',
    "net/minecraft/core/NonNullList.java": '''package net.minecraft.core;
public class NonNullList<E> extends java.util.ArrayList<E> {
    public static <E> NonNullList<E> create() { return new NonNullList<>(); }
}''',
    "net/minecraft/world/item/DyeColor.java": '''package net.minecraft.world.item;
public enum DyeColor { RED, BLUE }''',
    "net/minecraft/world/item/Item.java": '''package net.minecraft.world.item;
public class Item {}''',
    "net/minecraft/world/item/ItemStack.java": '''package net.minecraft.world.item;
public class ItemStack {
    public static final ItemStack EMPTY = new ItemStack(0);
    private int count;
    public ItemStack(int count) { this.count = count; }
    public boolean isEmpty() { return count <= 0; }
    public int getCount() { return count; }
    public ItemStack copy() { return new ItemStack(count); }
}''',
    "net/minecraft/world/level/block/state/BlockState.java": '''package net.minecraft.world.level.block.state;
public class BlockState {}''',
    "net/minecraft/world/phys/shapes/VoxelShape.java": '''package net.minecraft.world.phys.shapes;
public class VoxelShape {}''',
    "net/minecraft/world/phys/shapes/Shapes.java": '''package net.minecraft.world.phys.shapes;
public class Shapes { public static VoxelShape empty() { return new VoxelShape(); } }''',
    "net/minecraft/nbt/ByteArrayTag.java": '''package net.minecraft.nbt;
public record ByteArrayTag(byte[] value) { public byte[] getAsByteArray() { return value.clone(); } }''',
    "net/minecraft/nbt/CompoundTag.java": '''package net.minecraft.nbt;
public class CompoundTag {
    private final java.util.Map<String,Object> values = new java.util.HashMap<>();
    public void putByteArray(String key, byte[] bytes) { values.put(key, new ByteArrayTag(bytes.clone())); }
    public Object get(String key) { return values.get(key); }
    public CompoundTag copy() {
        CompoundTag other = new CompoundTag();
        for (var entry : values.entrySet()) {
            if (entry.getValue() instanceof ByteArrayTag bytes) other.putByteArray(entry.getKey(), bytes.value());
        }
        return other;
    }
}''',
    "net/minecraft/network/FriendlyByteBuf.java": '''package net.minecraft.network;
import net.minecraft.nbt.CompoundTag;
public class FriendlyByteBuf {
    private CompoundTag tag;
    public void writeNbt(CompoundTag value) { tag = value.copy(); }
    public CompoundTag readNbt() { return tag == null ? null : tag.copy(); }
}''',
    "net/minecraft/world/InteractionResult.java": '''package net.minecraft.world;
public enum InteractionResult { PASS, SUCCESS, FAIL }''',
    "net/minecraft/world/phys/BlockHitResult.java": '''package net.minecraft.world.phys;
public class BlockHitResult {}''',
    "net/minecraft/world/entity/player/Player.java": '''package net.minecraft.world.entity.player;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
public class Player {
    public UUID getUUID() { return new UUID(1,2); }
    public Name getName() { return new Name(); }
    public record Name() { public String getString() { return "tester"; } }
    public ItemStack getMainHandItem() { return new ItemStack(1); }
}''',
    "net/minecraft/world/level/block/entity/BlockEntity.java": '''package net.minecraft.world.level.block.entity;
public class BlockEntity {}''',
    "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
public class Level {
    public final boolean client;
    public BlockEntity tile;
    public Level(boolean client) { this.client = client; }
    public boolean isClientSide() { return client; }
    public boolean hasChunkAt(BlockPos pos) { return true; }
    public BlockEntity getBlockEntity(BlockPos pos) { return tile; }
}''',
    "buildcraft/api/v2/permission/AutomationActor.java": '''package buildcraft.api.v2.permission;
public class AutomationActor { public static AutomationActor unknown() { return new AutomationActor(); } public static AutomationActor player(java.util.UUID id, String name) { return new AutomationActor(); } }''',
    "buildcraft/api/v2/OperationMode.java": '''package buildcraft.api.v2;
public enum OperationMode { SIMULATE, EXECUTE }''',
    "buildcraft/api/v2/pipe/ItemInjectionRequest.java": '''package buildcraft.api.v2.pipe;
import net.minecraft.world.item.ItemStack;
public record ItemInjectionRequest(ItemStack stack, net.minecraft.core.Direction from) {}''',
    "buildcraft/api/v2/item/ItemPort.java": '''package buildcraft.api.v2.item; public interface ItemPort {}''',
    "buildcraft/api/v2/energy/MjPort.java": '''package buildcraft.api.v2.energy; public interface MjPort {}''',
    "buildcraft/api/v2/fluid/FluidPort.java": '''package buildcraft.api.v2.fluid; public interface FluidPort {}''',
    "buildcraft/api/v2/platform/ExternalEnergyPort.java": '''package buildcraft.api.v2.platform; public interface ExternalEnergyPort {}''',
    "buildcraft/api/v2/pipe/ItemPipePort.java": '''package buildcraft.api.v2.pipe;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
public interface ItemPipePort extends buildcraft.api.v2.item.ItemPort { ItemTransferResult inject(ItemInjectionRequest request, OperationMode mode); }''',
    "buildcraft/api/v2/item/ItemTransferResult.java": '''package buildcraft.api.v2.item;
public class ItemTransferResult {
    public static ItemTransferResult nothing(int n) { return new ItemTransferResult(); }
}''',
    "buildcraft/api/v2/pipe/PipeType.java": '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public record PipeType(ResourceLocation id) {}''',
    "buildcraft/api/v2/BuildCraftRegistries.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.*;
public class BuildCraftRegistries {
    public static final Key<PipeAttachmentType<?>> PIPE_ATTACHMENT_TYPES = new Key<>();
    public static final Key<PipeType> PIPE_TYPES = new Key<>();
    public record Key<T>() { }
}''',
    "buildcraft/api/v2/BuildCraftApi.java": '''package buildcraft.api.v2;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.pipe.*;
public class BuildCraftApi {
    private static final Registry<PipeAttachmentType<?>> ATTACHMENTS = new Registry<>();
    private static final Registry<PipeType> PIPES = new Registry<>();
    @SuppressWarnings("unchecked")
    public static <T> Registry<T> registry(BuildCraftRegistries.Key<T> key) {
        return (Registry<T>) (key == BuildCraftRegistries.PIPE_ATTACHMENT_TYPES ? ATTACHMENTS : PIPES);
    }
    public static class Registry<T> implements buildcraft.api.v2.registry.ApiRegistry<T> {
        private final Map<ResourceLocation,T> entries = new LinkedHashMap<>();
        public T get(ResourceLocation id) { return entries.get(id); }
        public void register(ResourceLocation id, T type, buildcraft.api.v2.registry.RegistrationContext ctx) {
            if (entries.putIfAbsent(id, type) != null) throw new IllegalArgumentException("duplicate");
        }
        public void put(ResourceLocation id, T type) { entries.put(id, type); }
        public void register(ResourceLocation id, T type) { entries.put(id, type); }
        public java.util.Collection<T> values() { return entries.values(); }
        public java.util.Collection<buildcraft.api.v2.registry.RegistryEntry<T>> entries() {
            return java.util.List.of();
        }
        public boolean frozen() { return false; }
    }
}''',
    "buildcraft/lib/internal/debug/BCLog.java": '''package buildcraft.lib.internal.debug;
public class BCLog {
    public static final Log logger = new Log();
    public static class Log { public void warn(String s, Object... args) {} }
}''',
    "buildcraft/lib/internal/core/InvalidInputDataException.java": '''package buildcraft.lib.internal.core;
public class InvalidInputDataException extends java.io.IOException {
    public InvalidInputDataException(String message) { super(message); }
}''',
    "buildcraft/lib/net/BCNetworkSide.java": '''package buildcraft.lib.net;
public enum BCNetworkSide { CLIENT, SERVER }''',
    "buildcraft/lib/net/BCPacketContext.java": '''package buildcraft.lib.net;
public class BCPacketContext {}''',
    "buildcraft/transport/internal/pipe/IPipe.java": '''package buildcraft.transport.internal.pipe; public interface IPipe {}''',
    "buildcraft/transport/internal/pipe/IPipeHolder.java": '''package buildcraft.transport.internal.pipe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import buildcraft.transport.pipe.Pipe;
public interface IPipeHolder {
    Level getPipeWorld();
    BlockPos getPipePos();
    IPipe getPipe();
    void scheduleRenderUpdate();
}''',
    "buildcraft/transport/internal/pipe/IItemPipe.java": '''package buildcraft.transport.internal.pipe;
public interface IItemPipe {}''',
    "buildcraft/transport/internal/pluggable/PipePluggable.java": '''package buildcraft.transport.internal.pluggable;
import buildcraft.lib.net.*;
import buildcraft.transport.internal.pipe.IPipeHolder;
import java.io.IOException;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
public abstract class PipePluggable {
    public static final PipePluggable EMPTY = new PipePluggable(null, null, null) {
        public VoxelShape getBoundingBox() { return Shapes.empty(); }
    };
    public final PluggableDefinition definition;
    public final IPipeHolder holder;
    public Direction side;
    public PipePluggable(PluggableDefinition def, IPipeHolder host, Direction side) {
        this.definition = def; holder = host; this.side = side;
    }
    public abstract VoxelShape getBoundingBox();
    public boolean isBlocking() { return false; }
    public ItemStack getPickStack() { return ItemStack.EMPTY; }
    public void addDrops(NonNullList<ItemStack> drops, int fortune) {}
    public void onTick() {}
    public void onRemove() {}
    public InteractionResult onPluggableActivate(Player player, BlockHitResult hit, Level level) { return InteractionResult.PASS; }
    public CompoundTag writeToNbt() { return new CompoundTag(); }
    public void writeCreationPayload(FriendlyByteBuf buf) {}
    public CompoundTag writeSyncState(BCNetworkSide side) { return null; }
    public void readSyncState(CompoundTag data, BCNetworkSide side, BCPacketContext ctx) throws IOException {}
}''',
    "buildcraft/transport/pipe/Pipe.java": '''package buildcraft.transport.pipe;
import java.util.Optional;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.OperationMode;
import buildcraft.transport.internal.pipe.IPipeHolder;
public class Pipe implements PipeExecutionContext, buildcraft.transport.internal.pipe.IPipe {
    public static final Pipe EMPTY = new Pipe(null);
    public int attachmentSyncRequests;
    private final IPipeHolder holder;
    public Pipe(IPipeHolder holder) { this.holder = holder; }
    public IPipeHolder getHolder() { return holder; }
    public ResourceLocation typeId() { return ResourceLocation.tryParse("addon:pipe"); }
    public BlockPos position() { return new BlockPos(1,2,3); }
    public java.util.Set<Direction> connectedSides() { return java.util.Set.of(); }
    public java.util.Collection<PipeComponent> components() { return java.util.List.of(); }
    public java.util.Optional<PipeComponent> component(ResourceLocation id) { return java.util.Optional.empty(); }
    public boolean isServerSide() { return true; }
    public boolean isActive() { return true; }
    public boolean isNeighbourLoaded(Direction face) { return false; }
    public Optional<BlockState> neighbourState(Direction face) { return Optional.empty(); }
    public Optional<PipeView> neighbourPipe(Direction face) { return Optional.empty(); }
    public boolean changeColor(Optional<DyeColor> color) { return true; }
    public void refreshConnections() {}
    public void requestAttachmentSync(Direction side) { attachmentSyncRequests++; }
    public void markChanged() {}
    public void requestSync(ResourceLocation id) {}
    public Optional<ItemPipePort> itemPipePort(Direction side) { return Optional.empty(); }
}''',
    "buildcraft/transport/pipe/PipeRegistry.java": '''package buildcraft.transport.pipe;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.transport.internal.pipe.IItemPipe;
import net.minecraft.world.item.Item;
public class PipeRegistry {
    public static final PipeRegistry INSTANCE = new PipeRegistry();
    public Object ensureRuntimeDefinition(PipeType type) { return null; }
    public IItemPipe getItemForPipe(Object def) { return null; }
    public Item createItemForPipe(Object def) { return new Item(); }
}''',
    "buildcraft/transport/tile/TilePipeHolder.java": '''package buildcraft.transport.tile;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
public class TilePipeHolder extends BlockEntity implements IPipeHolder {
    public final Level level;
    public final BlockPos pos = new BlockPos(1, 2, 3);
    public final Pipe pipe = new Pipe(this);
    public PipePluggable pluggable = PipePluggable.EMPTY;
    public TilePipeHolder(Level level) { this.level = level; level.tile = this; }
    public Level getPipeWorld() { return level; }
    public BlockPos getPipePos() { return pos; }
    public Pipe getPipe() { return pipe; }
    public PipePluggable getPluggable(Direction face) { return pluggable; }
    public PipePluggable replacePluggable(Direction face, PipePluggable newValue) {
        PipePluggable old = pluggable; pluggable = newValue; return old;
    }
    public void scheduleRenderUpdate() {}
}''',
}

FIXTURE = r'''package test;
import java.nio.ByteBuffer;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.permission.*;
import buildcraft.lib.registry.PluggableRegistry;
import buildcraft.lib.net.*;
import buildcraft.transport.api2.*;
import buildcraft.transport.internal.pluggable.*;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

public class Fixture {
    private static void check(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static ResourceLocation id(String s) { return Objects.requireNonNull(ResourceLocation.tryParse(s)); }
    private static final ResourceLocation ID = id("addon:filter");
    private static final ResourceLocation FORMAT = id("addon:int");
    private static final Direction SIDE = Direction.NORTH;
    private static int placed;
    private static int restored;
    private static int removed;
    private static int activations;

    private static final class Attachment implements PipeAttachment {
        final Direction side;
        final int value;
        Attachment(Direction side, int value) { this.side = side; this.value = value; }
        public ResourceLocation typeId() { return ID; }
        public Direction side() { return side; }
        public boolean blocksConnection() { return true; }
        public ItemStack pickStack() { return new ItemStack(1); }
        public void placed(PipeExecutionContext pipe) { placed++; }
        public void restored(PipeExecutionContext pipe) { restored++; }
        public void removed() { removed++; }
        public PipeActivationResult activate(PipeActivationContext ctx) {
            if (ctx.mode() == OperationMode.EXECUTE) activations++;
            return PipeActivationResult.SUCCESS;
        }
    }
    private static final ApiCodec<Attachment,OpaqueData> CODEC = new ApiCodec<>() {
        public CodecResult<OpaqueData> encode(Attachment value) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(value.value).array()));
        }
        public CodecResult<Attachment> decode(OpaqueData data) {
            if (!data.format().equals(FORMAT) || data.size() != 4) return CodecResult.failure("bad state");
            return CodecResult.success(new Attachment(SIDE, ByteBuffer.wrap(data.bytes()).getInt()));
        }
    };
    private static final PersistentType<Attachment,OpaqueData> PERSIST =
        PersistentType.<Attachment,OpaqueData>builder(ID,1,CODEC)
            .migration(new SchemaMigration<>() {
                public int fromVersion() { return 0; }
                public int toVersion() { return 1; }
                public CodecResult<OpaqueData> migrate(OpaqueData data) {
                    return CodecResult.success(new OpaqueData(FORMAT,
                        ByteBuffer.allocate(4).putInt(ByteBuffer.wrap(data.bytes()).getInt() + 1).array()));
                }
            }).build();
    private static final PipeAttachmentType<Attachment> TYPE = new PipeAttachmentType<>(ID,
        ctx -> new Attachment(ctx.side(), 3), PERSIST);

    public static void main(String[] args) throws Exception {
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES).put(ID, TYPE);
        Level server = new Level(false);
        TilePipeHolder tile = new TilePipeHolder(server);
        PipeView view = PipeServiceImpl.INSTANCE.pipe(server, tile.pos).orElseThrow();
        check(!(view instanceof PipeMutationContext) && view.typeId().equals(id("addon:pipe")),
            "Service leaked mutable implementation");
        PipeAttachmentResult simulate = PipeServiceImpl.INSTANCE.placeAttachment(server, tile.pos, SIDE, ID,
            new ItemStack(1), AutomationActor.unknown(), OperationMode.SIMULATE);
        check(simulate.success() && tile.pluggable == PipePluggable.EMPTY, "Simulation placed attachment");
        PipeAttachmentResult placedResult = PipeServiceImpl.INSTANCE.placeAttachment(server, tile.pos, SIDE, ID,
            new ItemStack(1), AutomationActor.unknown(), OperationMode.EXECUTE);
        check(placedResult.success() && tile.pluggable instanceof ApiPipePluggable, "Addon placement failed");
        check(placed == 1, "placed hook was not called exactly once");
        check(tile.pipe.attachmentSyncRequests == 1, "Initial placement state was not scheduled for sync");
        ApiPipePluggable plug = (ApiPipePluggable) tile.pluggable;
        check(plug.isBlocking(), "connection blocking lost");
        check(plug.onPluggableActivate(new Player(), new BlockHitResult(), server) == InteractionResult.SUCCESS
            && activations == 1, "Attachment player activation not routed");
        check(plug.onPluggableActivate(new Player(), new BlockHitResult(), new Level(true)) == InteractionResult.SUCCESS
            && activations == 1, "Client activation executed mutation");
        check(plug.getPickStack().getCount() == 1, "pick stack lost");
        NonNullList<ItemStack> drops = NonNullList.create();
        plug.addDrops(drops, 0);
        check(drops.size() == 1 && drops.get(0).getCount() == 1, "attachment drops lost");
        CompoundTag saved = plug.writeToNbt();
        PluggableDefinition def = PluggableRegistry.INSTANCE.getDefinition(ID);
        check(def != null && def.identifier.equals(ID), "direct addon registry lookup missing");
        ApiPipePluggable restored = (ApiPipePluggable) def.readFromNbt(tile,SIDE,saved);
        check(((Attachment) restored.attachment()).value == 3 && Fixture.restored == 1,
            "world restore lost persisted value or skipped restoration callback");
        FriendlyByteBuf buffer = new FriendlyByteBuf();
        plug.writeCreationPayload(buffer);
        ApiPipePluggable client = (ApiPipePluggable) def.loadFromBuffer(tile,SIDE,buffer);
        check(((Attachment) client.attachment()).value == 3 && Fixture.restored == 2,
            "client full sync lost value or skipped restoration callback");
        client.readSyncState(saved,BCNetworkSide.CLIENT,new BCPacketContext());
        check(((Attachment) client.attachment()).value == 3, "client changed sync lost value");
        byte[] bad = ((ByteArrayTag) saved.get("apiAttachment")).getAsByteArray();
        bad[0] ^= 0x7F;
        CompoundTag damaged = new CompoundTag(); damaged.putByteArray("apiAttachment", bad);
        try { def.readFromNbt(tile,SIDE,damaged); throw new AssertionError("Corrupt data accepted"); }
        catch (IllegalArgumentException expected) { }
        byte[] newer = writeEnvelope(ID,2,17);
        CompoundTag future = new CompoundTag(); future.putByteArray("apiAttachment",newer);
        try { def.readFromNbt(tile,SIDE,future); throw new AssertionError("Future schema accepted"); }
        catch (IllegalArgumentException expected) { }
        byte[] old = writeEnvelope(ID,0,5);
        CompoundTag legacy = new CompoundTag(); legacy.putByteArray("apiAttachment",old);
        ApiPipePluggable migrated = (ApiPipePluggable) def.readFromNbt(tile,SIDE,legacy);
        check(((Attachment) migrated.attachment()).value == 6, "Migration was not applied");
        boolean simulationRemoval = PipeServiceImpl.INSTANCE.removeAttachment(server,tile.pos,SIDE,
            AutomationActor.unknown(), OperationMode.SIMULATE);
        check(simulationRemoval && tile.pluggable == plug, "Simulation removed attachment");
        check(PipeServiceImpl.INSTANCE.removeAttachment(server,tile.pos,SIDE,
            AutomationActor.unknown(), OperationMode.EXECUTE), "Removal failed");
        plug.onRemove();
        check(removed == 1 && tile.pluggable == PipePluggable.EMPTY, "Removal callback duplicated");
        System.out.println("api attachment integration ok");
    }
    private static byte[] writeEnvelope(ResourceLocation id, int schema, int value) throws Exception {
        var sink = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(sink)) {
            out.writeInt(0x42434154); out.writeUTF(id.toString()); out.writeInt(schema);
            out.writeUTF(FORMAT.toString()); out.writeInt(4); out.writeInt(value);
        }
        return sink.toByteArray();
    }
}'''

class PipeAttachmentRuntimeTests(unittest.TestCase):
    def test_api_attachment_bridge_and_persistence(self) -> None:
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            stubs = []
            for name, content in STUBS.items():
                file = root / "src" / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding="utf-8")
                stubs.append(str(file))
            fixture = root / "src" / "test" / "Fixture.java"
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(FIXTURE, encoding="utf-8")
            output = root / "classes"
            output.mkdir()
            command = ["javac", "--release", "17", "-d", str(output)] + stubs + [str(ROOT / src) for src in SOURCES] + [str(fixture)]
            compile_result = subprocess.run(command, capture_output=True, text=True, timeout=50)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            run = subprocess.run(["java", "-cp", str(output), "test.Fixture"], capture_output=True, text=True, timeout=20)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn("api attachment integration ok", run.stdout)

if __name__ == "__main__":
    unittest.main()
