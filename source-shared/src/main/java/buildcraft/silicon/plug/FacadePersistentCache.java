/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.silicon.plug;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.net.BuildCraftTarget;
import com.google.common.collect.ImmutableSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/** Persistent facade discovery index, shared by Forge, NeoForge and Fabric. */
final class FacadePersistentCache {
    // Increment when changing discovery/deduplication semantics or serialized descriptor interpretation.
    private static final String POLICY = "facade-discovery-v2";
    private FacadePersistentCache() {}

    static final class Capture {
        final List<FacadeBlockStateInfo> accepted = new ArrayList<>();
        boolean failed;
        void add(FacadeBlockStateInfo info) { accepted.add(info); }
    }

    /** Writes are deferred until the manager confirms that initialization completed successfully. */
    static Runnable index(Path gameDir, Map<String, String> versions, Iterable<Block> registry,
                          Function<Block, String> blockId, Function<Item, String> itemId,
                          BiConsumer<Block, Capture> scan, Consumer<List<FacadeBlockStateInfo>> register,
                          Predicate<BlockState> validate, boolean debug) {
        long start = System.nanoTime();
        if (debug || Boolean.getBoolean("buildcraft.facades.disableCache") || versions.isEmpty()) {
            int scanned = 0;
            for (Block block : registry) { scan.accept(block, new Capture()); scanned++; }
            BCLog.explicitEvent("[silicon.facade] Cache bypassed: {} blocks scanned; {} ms (debug={}, disabled={}, versions={})",
                scanned, (System.nanoTime() - start) / 1_000_000L, debug,
                Boolean.getBoolean("buildcraft.facades.disableCache"), versions.size());
            return () -> {};
        }
        Path dir = gameDir.resolve(".cache").resolve("buildcraft").resolve("facades")
            .resolve(BuildCraftTarget.MINECRAFT_VERSION + "-" + BuildCraftTarget.LOADER);
        FacadeCacheStorage storage = new FacadeCacheStorage(dir);
        Map<String, List<Block>> sections = new TreeMap<>();
        for (Block block : registry) {
            String id = blockId.apply(block);
            if (id == null || id.indexOf(':') <= 0) {
                scan.accept(block, new Capture());
                continue;
            }
            sections.computeIfAbsent(namespace(id), k -> new ArrayList<>()).add(block);
        }
        Map<String, PendingWrite> writes = new TreeMap<>();
        int hits = 0, scans = 0, sectionHits = 0;
        String allVersions = allVersions(versions);
        String policy = policyFingerprint();
        for (var section : sections.entrySet()) {
            String name = section.getKey();
            List<Block> blocks = section.getValue();
            String fingerprint;
            try {
                fingerprint = fingerprint(name, blocks, versions, allVersions, policy, blockId, itemId);
            } catch (RuntimeException | LinkageError e) {
                BCLog.logger.warn("[silicon.facade] Cannot fingerprint {}; scanning without cache: {}", name, e.toString());
                for (Block block : blocks) { scan.accept(block, new Capture()); scans++; }
                continue;
            }
            Map<String, List<FacadeCacheStorage.State>> previous = null;
            try { previous = storage.read(name, fingerprint); }
            catch (IOException | RuntimeException e) {
                BCLog.logger.warn("[silicon.facade] Ignoring cache section {}: {}", name, e.toString());
            }
            if (previous != null) sectionHits++;
            Map<String, List<FacadeCacheStorage.State>> next = new LinkedHashMap<>();
            for (Block block : blocks) {
                String id = blockId.apply(block);
                // Custom mappings can change independently of mod versions, and clone stacks may
                // contain arbitrary components/NBT. Never use the standard-item cache for either.
                boolean eligible = false;
                try {
                    eligible = block.asItem() != Items.AIR
                        && !BuildCraftApiRuntime.INSTANCE.facadeRules().hasRuleFor(block);
                } catch (RuntimeException | LinkageError e) {
                    // Let the existing scanner handle the third-party failure.
                }
                List<FacadeCacheStorage.State> cached = eligible && previous != null ? previous.get(id) : null;
                if (cached != null) {
                    try {
                        List<FacadeBlockStateInfo> restored = restore(block, cached, validate);
                        // Construct every descriptor before publishing any of this block's entries.
                        register.accept(restored);
                        next.put(id, cached);
                        hits++;
                        continue;
                    } catch (RuntimeException | LinkageError e) {
                        BCLog.logger.warn("[silicon.facade] Rebuilding cached block {}: {}", id, e.toString());
                    }
                }
                scans++;
                Capture capture = new Capture();
                scan.accept(block, capture);
                if (eligible && !capture.failed) {
                    try {
                        if (!BuildCraftApiRuntime.INSTANCE.facadeRules().hasRuleFor(block)) {
                            next.put(id, describe(capture.accepted));
                        }
                    }
                    catch (RuntimeException e) {
                        BCLog.logger.warn("[silicon.facade] Cannot cache {}: {}", id, e.toString());
                    }
                }
            }
            if (!next.equals(previous)) writes.put(name, new PendingWrite(fingerprint, next));
        }
        BCLog.explicitEvent("[silicon.facade] Cache: {}/{} sections reused, {} blocks restored, {} blocks scanned, {} sections to write; {} ms",
            sectionHits, sections.size(), hits, scans, writes.size(), (System.nanoTime() - start) / 1_000_000L);
        if (!writes.isEmpty() && writes.size() <= 20) {
            BCLog.explicitEvent("[silicon.facade] Updated cache namespaces: {}", writes.keySet());
        }
        return () -> {
            long writeStart = System.nanoTime();
            int written = 0;
            for (var write : writes.entrySet()) {
                try { storage.write(write.getKey(), write.getValue().fingerprint(), write.getValue().blocks()); written++; }
                catch (IOException | RuntimeException e) {
                    BCLog.logger.warn("[silicon.facade] Unable to save {}: {}", write.getKey(), e.toString());
                }
            }
            try {
                storage.writeManifest(versions);
                storage.prune(sections.keySet());
            } catch (IOException | RuntimeException e) {
                BCLog.logger.warn("[silicon.facade] Unable to update cache manifest: {}", e.toString());
            }
            BCLog.explicitEvent("[silicon.facade] Cache commit: {}/{} sections written; {} ms",
                written, writes.size(), (System.nanoTime() - writeStart) / 1_000_000L);
        };
    }

    private record PendingWrite(String fingerprint, Map<String, List<FacadeCacheStorage.State>> blocks) {}

    private static String namespace(String id) { return id.substring(0, id.indexOf(':')); }

    private static String allVersions(Map<String, String> versions) {
        MessageDigest digest = FacadeCacheStorage.digest();
        new TreeMap<>(versions).forEach((id, version) -> {
            FacadeCacheStorage.add(digest, id); FacadeCacheStorage.add(digest, version);
        });
        return FacadeCacheStorage.finish(digest);
    }

    private static String version(String namespace, Map<String, String> versions, String allVersions) {
        if (namespace.equals("minecraft")) return BuildCraftTarget.MINECRAFT_VERSION;
        if (namespace.startsWith("buildcraft")) return versions.getOrDefault("buildcraftlib", BuildCraftTarget.MOD_VERSION);
        // A registry namespace is not necessarily a loader mod ID. For unknown owners, use the
        // complete manifest rather than incorrectly retaining a section after its owner changes.
        return versions.getOrDefault(namespace, "unknown:" + allVersions);
    }

    private static String policyFingerprint() {
        MessageDigest digest = FacadeCacheStorage.digest();
        FacadeCacheStorage.add(digest, POLICY);
        for (Class<?> type : List.of(FacadeStateManager.class, FacadeStateValidator.class,
                FacadeBlockStateInfo.class, FacadePhasedState.class, FacadeStateRoundTrip.class, FacadePersistentCache.class, BlockUtil.class)) {
            try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                if (stream == null) throw new IOException("Class bytes unavailable");
                FacadeCacheStorage.add(digest, type.getName());
                FacadeCacheStorage.add(digest, HexFormat.of().formatHex(FacadeCacheStorage.digest().digest(stream.readAllBytes())));
            } catch (IOException | RuntimeException e) {
                // Development/classloader environments without class resources fall back to release identity.
                FacadeCacheStorage.add(digest, BuildCraftTarget.MOD_VERSION);
                FacadeCacheStorage.add(digest, BuildCraftTarget.GIT_COMMIT_HASH);
            }
        }
        return FacadeCacheStorage.finish(digest);
    }

    private static String fingerprint(String name, List<Block> blocks, Map<String, String> versions,
                                      String allVersions, String policy, Function<Block, String> blockId,
                                      Function<Item, String> itemId) {
        MessageDigest digest = FacadeCacheStorage.digest();
        Consumer<String> add = value -> FacadeCacheStorage.add(digest, value);
        add.accept(policy);
        add.accept(BuildCraftTarget.MINECRAFT_VERSION);
        add.accept(BuildCraftTarget.LOADER);
        add.accept(Boolean.toString(BuildCraftTarget.MOD_VERSION.startsWith("7.99")));
        add.accept(versions.getOrDefault("neoforge", versions.getOrDefault("forge", versions.getOrDefault("fabricloader", ""))));
        add.accept(name);
        add.accept(version(name, versions, allVersions));
        // Sorted, stable registry identity, not numerical state IDs or JVM identity hashes.
        List<Block> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparing(blockId));
        for (Block block : ordered) {
            add.accept("block");
            add.accept(blockId.apply(block));
            add.accept(block.getClass().getName());
            String item = itemId.apply(block.asItem());
            add.accept(Objects.toString(item, ""));
            if (item != null && item.indexOf(':') > 0) add.accept(version(namespace(item), versions, allVersions));
            BlockState state = block.defaultBlockState();
            // The current validator reads these tags. Tag changes from datapacks invalidate only
            // the affected namespaces, including negative results for previously rejected blocks.
            add.accept(Boolean.toString(state.is(BlockTags.FLOWERS)));
            add.accept(Boolean.toString(state.is(BlockTags.LEAVES)));
            Map<String, String> defaults = BlockUtil.getPropertiesStringMap(state);
            add.accept(Integer.toString(defaults.size()));
            for (var property : new TreeMap<>(defaults).entrySet()) {
                add.accept(property.getKey()); add.accept(property.getValue());
            }
            List<Property<?>> properties = new ArrayList<>(block.getStateDefinition().getProperties());
            properties.sort(Comparator.comparing(Property::getName));
            add.accept(Integer.toString(properties.size()));
            for (Property<?> property : properties) addProperty(digest, property);
        }
        return FacadeCacheStorage.finish(digest);
    }

    private static <T extends Comparable<T>> void addProperty(MessageDigest digest, Property<T> property) {
        FacadeCacheStorage.add(digest, property.getName());
        FacadeCacheStorage.add(digest, property.getClass().getName());
        List<String> names = property.getAllValues().map(value -> property.getName(value.value())).sorted().toList();
        FacadeCacheStorage.add(digest, Integer.toString(names.size()));
        names.forEach(name -> FacadeCacheStorage.add(digest, name));
    }

    private static List<FacadeCacheStorage.State> describe(List<FacadeBlockStateInfo> infos) {
        List<FacadeCacheStorage.State> result = new ArrayList<>();
        for (FacadeBlockStateInfo info : infos) {
            List<String> varying = info.varyingProperties.stream().map(Property::getName).sorted().toList();
            result.add(new FacadeCacheStorage.State(BlockUtil.getPropertiesStringMap(info.state), varying));
        }
        return List.copyOf(result);
    }

    private static List<FacadeBlockStateInfo> restore(Block block, List<FacadeCacheStorage.State> entries,
                                                       Predicate<BlockState> validate) {
        List<FacadeBlockStateInfo> result = new ArrayList<>();
        Set<BlockState> seen = new HashSet<>();
        for (FacadeCacheStorage.State entry : entries) {
            BlockState state = block.defaultBlockState();
            if (entry.properties().size() != state.getProperties().size()) throw new IllegalStateException("Changed property count");
            Map<String, Property<?>> properties = new HashMap<>();
            for (Property<?> property : state.getProperties()) {
                properties.put(property.getName(), property);
                String value = entry.properties().get(property.getName());
                if (value == null) throw new IllegalStateException("Missing property " + property.getName());
                state = setProperty(state, property, value);
            }
            if (!seen.add(state) || !validate.test(state)) throw new IllegalStateException("Cached state no longer valid");
            ImmutableSet.Builder<Property<?>> varying = ImmutableSet.builder();
            for (String name : entry.varying()) varying.add(Objects.requireNonNull(properties.get(name)));
            result.add(new FacadeBlockStateInfo(state, new ItemStack(block.asItem(), 1), varying.build()));
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow());
    }
}
