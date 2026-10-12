#!/usr/bin/env python3
"""Exercise the extension runtime against the production codec and migration classes."""
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
)

RESOURCE_LOCATION = """package net.minecraft.resources;
import java.util.Objects;
public record ResourceLocation(String namespace, String path) {
    public ResourceLocation {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(path);
    }
    public static ResourceLocation tryParse(String source) {
        String[] parts = source.split(":", 2);
        return parts.length == 2 ? new ResourceLocation(parts[0], parts[1]) : null;
    }
    public String toString() { return namespace + ":" + path; }
}
"""

FIXTURE = r"""package test;
import buildcraft.api.v2.persistence.*;
import buildcraft.lib.internal.api.v2.runtime.RuntimeExtensions;
import buildcraft.lib.internal.api.v2.runtime.RuntimeExtensions.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;

public final class RuntimeExtensionsFixture {
    private static final ResourceLocation ID = id("test:counter");
    private static final ResourceLocation FORMAT = id("test:int");
    private static final ResourceLocation LEGACY = id("test:legacy_counter");
    private static ResourceLocation id(String key) { return Objects.requireNonNull(ResourceLocation.tryParse(key)); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static record Value(int number) { }
    private static record View(int number) { }
    private static final ApiCodec<Value, OpaqueData> CODEC = new ApiCodec<>() {
        public CodecResult<Value> decode(OpaqueData data) {
            if (!FORMAT.equals(data.format()) || data.size() != 4) return CodecResult.failure("Unexpected payload");
            return CodecResult.success(new Value(ByteBuffer.wrap(data.bytes()).getInt()));
        }
        public CodecResult<OpaqueData> encode(Value value) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(value.number()).array()));
        }
    };
    private static final ApiCodec<View, OpaqueData> VIEW_CODEC = new ApiCodec<>() {
        public CodecResult<View> decode(OpaqueData data) {
            if (!FORMAT.equals(data.format()) || data.size() != 4) return CodecResult.failure("Unexpected view payload");
            return CodecResult.success(new View(ByteBuffer.wrap(data.bytes()).getInt()));
        }
        public CodecResult<OpaqueData> encode(View value) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(value.number()).array()));
        }
    };
    private static PersistentType<Value, OpaqueData> codec() {
        return PersistentType.<Value, OpaqueData>builder(ID, 1, CODEC)
            .alias(LEGACY)
            .migration(new SchemaMigration<>() {
                public int fromVersion() { return 0; }
                public int toVersion() { return 1; }
                public CodecResult<OpaqueData> migrate(OpaqueData payload) {
                    return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4)
                        .putInt(ByteBuffer.wrap(payload.bytes()).getInt() + 1).array()));
                }
            }).build();
    }
    private static PersistentType<View, OpaqueData> viewCodec() {
        return PersistentType.<View, OpaqueData>builder(ID, 1, VIEW_CODEC).build();
    }
    private static EncodedPayload<OpaqueData> payload(ResourceLocation id, int version, int number) {
        return new EncodedPayload<>(id, version, CODEC.encode(new Value(number)).valueOrThrow());
    }
    private static Definition<Value, View> definition(Callbacks<Value> callbacks) {
        return new Definition<>(ID, () -> new Value(10), codec(), viewCodec(),
            value -> new View(value.number()), callbacks);
    }
    private static RuntimeExtensions<Value, View> host(Definition<Value, View> definition, Side side, List<String> errors) {
        return new RuntimeExtensions<>(List.of(definition), side, errors::add);
    }
    public static void main(String[] args) throws Exception {
        List<String> errors = new ArrayList<>();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger activation = new AtomicInteger();
        AtomicInteger removal = new AtomicInteger();
        Callbacks<Value> callbacks = new Callbacks<>() {
            public void tick(Value value) { ticks.incrementAndGet(); }
            public void activate(Value value) { activation.incrementAndGet(); }
            public void invalidate(Value value) { removal.incrementAndGet(); }
        };
        RuntimeExtensions<Value, View> server = host(definition(callbacks), Side.SERVER, errors);
        check(server.create(ID), "Factory did not create the instance");
        check(server.component(ID).orElseThrow().number() == 10, "Wrong initial instance");
        server.activate();
        server.tick();
        check(ticks.get() == 1 && activation.get() == 1, "Lifecycle not dispatched");
        List<EncodedPayload<OpaqueData>> save = server.save();
        check(save.size() == 1 && save.get(0).schemaVersion() == 1, "Persistence not encoded");
        SyncFrame first = server.fullSync();
        check(server.changedSync().isEmpty(), "Full sync did not acknowledge dirty state");
        RuntimeExtensions<Value, View> client = host(definition(callbacks), Side.CLIENT, errors);
        check(client.applySync(first) && client.view(ID).orElseThrow().number() == 10, "Full sync failed");
        check(!client.applySync(first), "Duplicate frame accepted");
        server.markDirty(ID);
        SyncFrame second = server.changedSync().orElseThrow();
        check(!second.full() && second.revision() > first.revision(), "Delta sync failed");
        check(client.applySync(second), "Client delta sync failed");
        check(!client.applySync(new SyncFrame(second.revision() + 1, false,
            List.of(new EncodedPayload<>(ID, 1, new OpaqueData(id("test:wrong"), new byte[]{8}))))),
            "Corrupt sync was accepted");
        check(client.view(ID).orElseThrow().number() == 10, "Corrupt sync changed client state");
        server.suspend();
        rejected(server::tick);
        server.activate();
        check(activation.get() == 2, "Resume did not reactivate");
        server.invalidate();
        server.invalidate();
        check(removal.get() == 1, "Invalidation was repeated");
        rejected(server::save);

        RuntimeExtensions<Value, View> restored = host(definition(callbacks), Side.SERVER, errors);
        restored.restore(List.of(payload(LEGACY, 0, 2), payload(id("test:absent"), 8, 5)));
        check(restored.component(ID).orElseThrow().number() == 3, "Schema migration or alias failed");
        List<EncodedPayload<OpaqueData>> savedAgain = restored.save();
        check(savedAgain.size() == 2, "Unknown payload was dropped");
        check(savedAgain.stream().anyMatch(p -> p.typeId().equals(id("test:absent")) && p.schemaVersion() == 8),
            "Unknown payload was changed");
        check(savedAgain.stream().anyMatch(p -> p.typeId().equals(ID) && p.schemaVersion() == 1),
            "Resolved payload did not use canonical id");
        restored.activate();
        EncodedPayload<OpaqueData> futureData = payload(LEGACY, 9, 33);
        RuntimeExtensions<Value, View> future = host(definition(callbacks), Side.SERVER, errors);
        future.restore(List.of(futureData));
        check(!future.create(ID), "Newer schema was overwritten through an alias");
        check(future.save().equals(List.of(futureData)), "Newer schema was not preserved");
        RuntimeExtensions<Value, View> corrupt = host(definition(callbacks), Side.SERVER, errors);
        EncodedPayload<OpaqueData> invalid = new EncodedPayload<>(ID, 1, new OpaqueData(id("test:wrong"), new byte[]{5}));
        corrupt.restore(List.of(invalid));
        check(corrupt.save().equals(List.of(invalid)), "Undecodable bytes were lost");

        AtomicInteger brokenTicks = new AtomicInteger();
        RuntimeExtensions<Value, View> bad = host(definition(new Callbacks<>() {
            public void tick(Value value) { brokenTicks.incrementAndGet(); throw new IllegalStateException("bad addon"); }
        }), Side.SERVER, errors);
        bad.restore(List.of(payload(ID, 1, 20)));
        bad.activate();
        bad.tick();
        bad.tick();
        check(brokenTicks.get() == 1 && !errors.isEmpty(), "Failing callback was not isolated");
        check(bad.save().get(0).equals(payload(ID, 1, 20)), "Failed instance lost its previous state");
        RuntimeExtensions<Value, View> brokenFresh = host(definition(new Callbacks<>() {
            public void tick(Value value) { throw new IllegalStateException("broken new component"); }
        }), Side.SERVER, errors);
        check(brokenFresh.create(ID), "Could not create stateful extension");
        brokenFresh.activate();
        brokenFresh.tick();
        check(brokenFresh.save().equals(List.of(payload(ID, 1, 10))), "Failed new instance lost its initial state");
        rejected(() -> bad.restore(List.of(payload(ID, 1, 20))));
        AtomicInteger cleaned = new AtomicInteger();
        RuntimeExtensions<Value, View> failedAttach = host(definition(new Callbacks<>() {
            public void attach(Value value) { throw new IllegalStateException("broken setup"); }
            public void invalidate(Value value) { cleaned.incrementAndGet(); }
        }), Side.SERVER, errors);
        check(!failedAttach.create(ID) && cleaned.get() == 1, "Failed attachment was not cleaned up");
        rejected(() -> new RuntimeExtensions<>(List.of(definition(callbacks), definition(callbacks)), Side.SERVER, errors::add));
        rejected(() -> {
            RuntimeExtensions<Value, View> oversized = host(definition(callbacks), Side.SERVER, errors);
            oversized.restore(List.of(new EncodedPayload<>(id("test:absent"), 1,
                new OpaqueData(FORMAT, new byte[64 * 1024 + 1]))));
        });
        AtomicInteger wrongThread = new AtomicInteger();
        Thread thread = new Thread(() -> {
            try { restored.save(); } catch (IllegalStateException expected) { wrongThread.incrementAndGet(); }
        });
        thread.start();
        thread.join();
        check(wrongThread.get() == 1, "Host accepted a cross-thread call");
        System.out.println("Runtime extension lifecycle, persistence, migrations, unknown state, sync and isolation OK");
    }
}
"""


class RuntimeExtensionTests(unittest.TestCase):
    def test_runtime_lifecycle_and_payloads(self) -> None:
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK is not installed")
        with tempfile.TemporaryDirectory(prefix="bc-runtime-extensions-") as temp:
            destination = Path(temp)
            stub = destination / "src/net/minecraft/resources/ResourceLocation.java"
            fixture = destination / "src/test/RuntimeExtensionsFixture.java"
            stub.parent.mkdir(parents=True)
            fixture.parent.mkdir(parents=True)
            stub.write_text(RESOURCE_LOCATION, encoding="utf-8")
            fixture.write_text(FIXTURE, encoding="utf-8")
            sources = [ROOT / name for name in SOURCES]
            missing = [str(path) for path in sources if not path.is_file()]
            self.assertFalse(missing, f"Missing Java sources: {missing}")
            compiled = subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(destination / "classes"),
                                       str(stub), str(fixture), *(str(path) for path in sources)],
                                      capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", str(destination / "classes"),
                                     "test.RuntimeExtensionsFixture"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
            self.assertIn("OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
