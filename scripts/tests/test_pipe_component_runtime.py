#!/usr/bin/env python3
"""Compile the pipe component runtime with the production codecs and exercise persistence and sync."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = (
    "source-shared/src/main/java/buildcraft/api/v2/persistence/ApiCodec.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/CodecResult.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/EncodedPayload.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/KnownPayload.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/OpaqueData.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/PayloadCopier.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/PayloadResolution.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/PersistentType.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/SchemaMigration.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/UnknownPayload.java",
    "source-shared/src/main/java/buildcraft/api/v2/persistence/UnknownPayloadReason.java",
    "source-shared/src/main/java/buildcraft/api/v2/registry/RegistrationContext.java",
    "source-shared/src/main/java/buildcraft/lib/internal/api/v2/persistence/PersistenceRegistryBuilder.java",
    "source-shared/src/main/java/buildcraft/lib/internal/api/v2/persistence/PersistenceRegistrySnapshot.java",
    "source-shared/src/main/java/buildcraft/lib/internal/api/v2/runtime/RuntimeExtensions.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeComponent.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeComponentFactory.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeComponentType.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeComponentLifecycle.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeComponentSync.java",
    "source-shared/src/main/java/buildcraft/api/v2/pipe/PipeSyncChannel.java",
    "source-shared/src/main/java/buildcraft/transport/api2/PipeComponentRuntime.java",
)

STUBS = {
    "net/minecraft/resources/ResourceLocation.java": r'''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String name) {
        String[] parts = name.split(":", 2);
        return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()
            ? new ResourceLocation(parts[0], parts[1]) : null;
    }
    public String toString() { return namespace + ":" + path; }
}''',
    "net/minecraft/world/item/ItemStack.java": r'''package net.minecraft.world.item;
public class ItemStack {
    public boolean isEmpty() { return false; }
    public ItemStack copy() { return this; }
}''',
    "buildcraft/api/v2/pipe/PipeConnectionChange.java": r'''package buildcraft.api.v2.pipe;
public record PipeConnectionChange(String side, String before, String after) { }
''',
    "net/minecraft/network/FriendlyByteBuf.java": r'''package net.minecraft.network;
import java.io.*;
public class FriendlyByteBuf {
    private final ByteArrayOutputStream writer = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(writer);
    private final DataInputStream in;
    public FriendlyByteBuf() { in = null; }
    public FriendlyByteBuf(byte[] bytes) { in = new DataInputStream(new ByteArrayInputStream(bytes)); }
    public void writeByteArray(byte[] bytes) {
        try { out.writeInt(bytes.length); out.write(bytes); } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    public byte[] readByteArray(int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("Oversized frame");
        byte[] result = new byte[n];
        in.readFully(result);
        return result;
    }
    public byte[] toByteArray() { return writer.toByteArray(); }
}''',
    "buildcraft/api/v2/pipe/PipeView.java": r'''package buildcraft.api.v2.pipe;
public interface PipeView { String position(); }''',
    "buildcraft/api/v2/pipe/PipeMutationContext.java": r'''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public interface PipeMutationContext extends PipeView {
    void markChanged();
    void requestSync(ResourceLocation id);
}''',
    "buildcraft/api/v2/pipe/PipeTickComponent.java": r'''package buildcraft.api.v2.pipe;
public interface PipeTickComponent extends PipeComponent { void tick(PipeMutationContext pipe); }''',
    "buildcraft/api/v2/pipe/PipeType.java": r'''package buildcraft.api.v2.pipe;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
public record PipeType(ResourceLocation id, Set<ResourceLocation> defaultComponents) { }''',
    "buildcraft/api/v2/BuildCraftRegistries.java": r'''package buildcraft.api.v2;
import buildcraft.api.v2.pipe.*;
public class BuildCraftRegistries {
    public static final Key<PipeComponentType<?>> PIPE_COMPONENT_TYPES = new Key<>();
    public static final Key<PipeSyncChannel<?>> PIPE_SYNC_CHANNELS = new Key<>();
    public record Key<T>() { }
}''',
    "buildcraft/api/v2/BuildCraftApi.java": r'''package buildcraft.api.v2;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.pipe.*;
public class BuildCraftApi {
    private static final Registry<PipeComponentType<?>> COMPONENTS = new Registry<>();
    private static final Registry<PipeSyncChannel<?>> CHANNELS = new Registry<>();
    @SuppressWarnings("unchecked")
    public static <T> Registry<T> registry(BuildCraftRegistries.Key<T> key) {
        return (Registry<T>) (key == BuildCraftRegistries.PIPE_COMPONENT_TYPES ? COMPONENTS : CHANNELS);
    }
    public static class Registry<T> {
        private final Map<ResourceLocation, T> entries = new LinkedHashMap<>();
        public T get(ResourceLocation id) { return entries.get(id); }
        public Collection<T> values() { return entries.values(); }
        public void put(ResourceLocation id, T value) { entries.put(id, value); }
        public void clear() { entries.clear(); }
    }
}''',
    "buildcraft/lib/internal/debug/BCLog.java": r'''package buildcraft.lib.internal.debug;
public class BCLog {
    public static final Log logger = new Log();
    public static class Log { public void warn(String message, Object... arguments) { } }
}''',
}

FIXTURE = r'''package test;
import buildcraft.api.v2.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.PipeComponentRuntime;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class PipeComponentFixture {
    private static ResourceLocation id(String s) { return Objects.requireNonNull(ResourceLocation.tryParse(s)); }
    private static final ResourceLocation ID = id("addon:counter");
    private static final ResourceLocation FORMAT = id("addon:int");
    private static final ResourceLocation CHANNEL_ID = id("addon:counter_sync");
    private static final ResourceLocation LEGACY = id("addon:old_counter");
    private static void check(boolean c, String description) { if (!c) throw new AssertionError(description); }
    private static class Pipe implements PipeMutationContext {
        public String position() { return "test:0,0,0"; }
        public void markChanged() { }
        public void requestSync(ResourceLocation id) { }
    }
    private static final AtomicInteger attached = new AtomicInteger();
    private static final AtomicInteger restored = new AtomicInteger();
    private static final AtomicInteger invalidated = new AtomicInteger();
    private static final AtomicInteger placed = new AtomicInteger();
    private static final AtomicInteger removed = new AtomicInteger();
    private static final AtomicInteger topology = new AtomicInteger();
    private static final AtomicInteger drops = new AtomicInteger();
    private static class Counter implements PipeComponentLifecycle, PipeTickComponent {
        private int value;
        private PipeView pipe;
        Counter(int value) { this.value = value; }
        public ResourceLocation typeId() { return ID; }
        public void attached(PipeView pipe) { this.pipe = pipe; attached.incrementAndGet(); }
        public void restored() { restored.incrementAndGet(); }
        public void invalidated() { invalidated.incrementAndGet(); }
        public void placed() { placed.incrementAndGet(); }
        public void removed() { removed.incrementAndGet(); }
        public void connectionsChanged(PipeConnectionChange change) { topology.incrementAndGet(); }
        public void addDrops(List<net.minecraft.world.item.ItemStack> collected, int fortune) {
            collected.add(new net.minecraft.world.item.ItemStack());
            drops.incrementAndGet();
        }
        public void tick(PipeMutationContext context) { check(pipe == context, "Component lost its pipe context"); value++; }
    }
    private static final ApiCodec<Integer, OpaqueData> INTS = new ApiCodec<>() {
        public CodecResult<OpaqueData> encode(Integer i) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(i).array()));
        }
        public CodecResult<Integer> decode(OpaqueData state) {
            if (!FORMAT.equals(state.format()) || state.size() != 4) return CodecResult.failure("Invalid counter");
            return CodecResult.success(ByteBuffer.wrap(state.bytes()).getInt());
        }
    };
    private static final ApiCodec<Counter, OpaqueData> STATE = new ApiCodec<>() {
        public CodecResult<OpaqueData> encode(Counter c) { return INTS.encode(c.value); }
        public CodecResult<Counter> decode(OpaqueData state) { return INTS.decode(state).map(Counter::new); }
    };
    private static final PipeSyncChannel<Integer> CHANNEL = new PipeSyncChannel<>(CHANNEL_ID, INTS, 16);
    private static final PersistentType<Counter, OpaqueData> PERSIST =
        PersistentType.<Counter, OpaqueData>builder(ID, 1, STATE)
        .alias(LEGACY)
        .migration(new SchemaMigration<>() {
            public int fromVersion() { return 0; }
            public int toVersion() { return 1; }
            public CodecResult<OpaqueData> migrate(OpaqueData data) {
                return INTS.decode(data).map(n -> INTS.encode(n + 1).valueOrThrow());
            }
        }).build();
    private static final PipeComponentType<Counter> TYPE = new PipeComponentType<>(ID,
        pipe -> new Counter(10), PERSIST,
        List.of(new PipeComponentSync<>(CHANNEL, counter -> counter.value,
            (counter, value) -> counter.value = value, PipeComponentSync.Policy.WHEN_CHANGED)));
    private static final PipeType PIPE_TYPE = new PipeType(id("addon:pipe"), Set.of(ID));
    private static final PipeType EMPTY_PIPE = new PipeType(id("addon:pipe"), Set.of());
    private static PipeComponentRuntime host(PipeType type, boolean client, byte[] save) {
        return new PipeComponentRuntime(new Pipe(), type, client, save);
    }
    private static Counter counter(PipeComponentRuntime runtime) {
        return (Counter) runtime.components().stream().filter(c -> c.typeId().equals(ID)).findFirst().orElseThrow();
    }
    private static void register() {
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).put(ID, TYPE);
        BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).put(CHANNEL_ID, CHANNEL);
    }
    private static byte[] frame(PipeComponentRuntime server, boolean full) {
        FriendlyByteBuf out = new FriendlyByteBuf();
        if (full) server.writeFullSync(out); else server.writeChangedSync(out);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        register();
        PipeComponentRuntime server = host(PIPE_TYPE, false, null);
        server.placed();
        server.placed();
        check(placed.get() == 1, "Placement callback duplicated");
        server.onLoad();
        server.connectionsChanged(new PipeConnectionChange("NORTH", "NONE", "PIPE"));
        check(topology.get() == 1, "Connection callback missing");
        List<net.minecraft.world.item.ItemStack> collected = new ArrayList<>();
        server.addDrops(collected, 0);
        check(drops.get() == 1 && collected.size() == 1, "Component drops missing");
        server.tick();
        server.tick();
        check(counter(server).value == 12, "Server ticking failed");
        byte[] oldFrame = frame(server, true);
        PipeComponentRuntime client = host(PIPE_TYPE, true, null);
        client.readSync(new FriendlyByteBuf(oldFrame));
        check(counter(client).value == 12, "Full client sync failed");
        check(client.presentation(CHANNEL_ID, Integer.class).orElseThrow() == 12,
            "Client presentation did not use decoded channel data");
        check(client.presentation(CHANNEL_ID, String.class).isEmpty(), "Client snapshot type check missing");
        check(server.presentation(CHANNEL_ID, Integer.class).isEmpty(), "Server leaked presentation snapshot");
        check(server.requestSync(CHANNEL_ID), "Registered channel rejected");
        server.tick();
        check(server.stateChanged(), "Dirty policy did not queue its channel");
        client.readSync(new FriendlyByteBuf(frame(server, false)));
        check(counter(client).value == 13, "Incremental client sync failed");
        check(client.presentation(CHANNEL_ID, Integer.class).orElseThrow() == 13,
            "Client presentation did not receive delta");
        client.readSync(new FriendlyByteBuf(oldFrame));
        check(counter(client).value == 13, "Stale client update rolled state back");
        check(client.presentation(CHANNEL_ID, Integer.class).orElseThrow() == 13,
            "Stale sync changed presentation");
        // A valid envelope with an incompatible channel payload must not alter client state.
        FriendlyByteBuf corruptBuffer = new FriendlyByteBuf();
        java.io.ByteArrayOutputStream corruptBytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream corruptOut = new java.io.DataOutputStream(corruptBytes);
        corruptOut.writeLong(100L);
        corruptOut.writeBoolean(false);
        corruptOut.writeShort(1);
        corruptOut.writeUTF(ID.toString());
        corruptOut.writeUTF(CHANNEL_ID.toString());
        corruptOut.writeUTF(id("addon:wrong_format").toString());
        corruptOut.writeInt(4);
        corruptOut.writeInt(111);
        corruptBuffer.writeByteArray(corruptBytes.toByteArray());
        try {
            client.readSync(new FriendlyByteBuf(corruptBuffer.toByteArray()));
            throw new AssertionError("Bad channel data was accepted");
        } catch (java.io.IOException expected) { }
        check(counter(client).value == 13, "Corrupt client frame changed component state");
        check(client.presentation(CHANNEL_ID, Integer.class).orElseThrow() == 13,
            "Corrupt sync changed presentation");
        byte[] saved = server.save();
        server.onChunkUnloaded();
        server.onLoad();
        server.tick();
        check(counter(server).value == 14, "Resume after chunk unload failed");
        server.removed();
        server.removed();
        check(removed.get() == 1, "Removal callback duplicated");
        server.invalidate();
        PipeComponentRuntime restarted = host(PIPE_TYPE, false, saved);
        check(counter(restarted).value == 13, "Saved component not restored");
        check(counter(restarted).pipe != null && restored.get() == 1, "Restored component has no runtime context");
        restarted.invalidate();
        client.invalidate();
        check(client.presentation(CHANNEL_ID, Integer.class).isEmpty(), "Invalidated snapshot still visible");
        check(invalidated.get() >= 3, "Lifecycle invalidation not dispatched");

        BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).clear();
        PipeComponentRuntime missing = host(EMPTY_PIPE, false, saved);
        check(Arrays.equals(saved, missing.save()), "Unknown addon component changed on save");
        missing.invalidate();
        register();
        PipeComponentRuntime reappeared = host(EMPTY_PIPE, false, saved);
        check(counter(reappeared).value == 13, "Absent addon was not recovered");
        reappeared.invalidate();

        byte[] damaged = new byte[] {1, 3, 3, 7, 9, 2};
        PipeComponentRuntime unreadable = host(PIPE_TYPE, false, damaged);
        check(Arrays.equals(damaged, unreadable.save()), "Corrupt NBT was silently erased");
        int oldAttached = attached.get();
        int oldDrops = drops.get();
        unreadable.placed();
        unreadable.onLoad();
        unreadable.tick();
        List<net.minecraft.world.item.ItemStack> unsafeDrops = new ArrayList<>();
        unreadable.addDrops(unsafeDrops, 0);
        check(unreadable.components().isEmpty(), "Invalid state activated default addon components");
        check(attached.get() == oldAttached && drops.get() == oldDrops && unsafeDrops.isEmpty(),
            "Invalid state ran addon callbacks while preserving different saved data");
        unreadable.invalidate();

        var newer = new RuntimeStateBuffer(LEGACY, 8, INTS.encode(99).valueOrThrow());
        PipeComponentRuntime future = host(PIPE_TYPE, false, newer.bytes());
        check(Arrays.equals(newer.bytes(), future.save()), "Future schema state lost or modified");
        future.invalidate();

        var legacy = new RuntimeStateBuffer(LEGACY, 0, INTS.encode(4).valueOrThrow());
        PipeComponentRuntime migrated = host(PIPE_TYPE, false, legacy.bytes());
        check(counter(migrated).value == 5, "Schema migration not applied");
        migrated.invalidate();
    }
    private record RuntimeStateBuffer(ResourceLocation id, int version, OpaqueData payload) {
        byte[] bytes() throws Exception {
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(data);
            out.writeInt(0x42435043);
            out.writeByte(1);
            out.writeShort(1);
            out.writeUTF(id.toString());
            out.writeInt(version);
            out.writeUTF(payload.format().toString());
            out.writeInt(payload.size());
            out.write(payload.bytes());
            return data.toByteArray();
        }
    }
}'''


class PipeComponentRuntimeTests(unittest.TestCase):
    def test_server_persistence_and_client_sync(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")
        with tempfile.TemporaryDirectory(prefix="bcce-pipe-components-") as folder:
            tmp = Path(folder)
            files = []
            for relative in SOURCES:
                path = ROOT / relative
                self.assertTrue(path.is_file(), relative)
                files.append(str(path))
            for name, source in STUBS.items():
                path = tmp / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
                files.append(str(path))
            fixture = tmp / "test/PipeComponentFixture.java"
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(FIXTURE, encoding="utf-8")
            files.append(str(fixture))
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(tmp / "classes"), *files], check=True,
                           cwd=ROOT, capture_output=True, text=True)
            subprocess.run(["java", "-cp", str(tmp / "classes"), "test.PipeComponentFixture"], check=True,
                           cwd=ROOT, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
