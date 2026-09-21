/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.SingleCache;
import buildcraft.lib.nbt.NbtSquisher;
import buildcraft.lib.net.BCNetworkSide;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Persistent snapshot storage with deliberately different client and server responsibilities.
 *
 * <p>The client library is the player's portable blueprint collection. The dedicated-server store keeps the same
 * complete serialized snapshots, but exposes no user-library semantics: it is persistent machine-side storage keyed
 * by content for builders and other server systems. Integrated servers read the local client library directly and
 * therefore do not create a second server-side copy.</p>
 */
public final class GlobalSavedDataSnapshots {
    private static final String SNAPSHOT_FILE_EXTENSION = ".bcnbt";
    private static final String CLIENT_LIBRARY_DIRECTORY = "blueprints";
    private static final String LEGACY_CLIENT_DIRECTORY = "snapshots-client";
    private static final String SERVER_CACHE_DIRECTORY = "snapshots-server";
    private static final int CLIENT_FILENAME_HASH_LENGTH = 12;
    private static final int CLIENT_FILENAME_NAME_LENGTH = 80;

    private static final Map<BCNetworkSide, GlobalSavedDataSnapshots> INSTANCES = new EnumMap<>(BCNetworkSide.class);

    private final BCNetworkSide side;
    private final File snapshotsFile;
    private final LoadingCache<Snapshot.Key, Optional<Snapshot>> snapshotsCache = CacheBuilder.newBuilder()
        .maximumSize(512)
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build(CacheLoader.from(key -> Optional.ofNullable(readSnapshot(key)).map(Pair::getLeft)));
    private final SingleCache<List<Snapshot.Key>> listCache = new SingleCache<>(this::readClientList, 1, TimeUnit.SECONDS);

    private GlobalSavedDataSnapshots(BCNetworkSide side) {
        this.side = side;
        File gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().toFile();
        snapshotsFile = new File(
            gameDirectory,
            side == BCNetworkSide.CLIENT ? CLIENT_LIBRARY_DIRECTORY : SERVER_CACHE_DIRECTORY
        );
        ensureDirectory(snapshotsFile);
        if (side == BCNetworkSide.CLIENT) {
            migrateLegacyClientLibrary(new File(gameDirectory, LEGACY_CLIENT_DIRECTORY));
        }
    }

    public static void reInit(BCNetworkSide side) {
        INSTANCES.put(side, new GlobalSavedDataSnapshots(side));
    }

    private static GlobalSavedDataSnapshots get(BCNetworkSide side) {
        return INSTANCES.computeIfAbsent(side, GlobalSavedDataSnapshots::new);
    }

    public static void saveClientSnapshot(Snapshot snapshot) {
        get(BCNetworkSide.CLIENT).writeClientSnapshot(snapshot);
    }

    @Nullable
    public static Snapshot getClientSnapshot(@Nullable Snapshot.Key key) {
        return get(BCNetworkSide.CLIENT).getSnapshot(key);
    }

    public static List<Snapshot.Key> getClientSnapshotList() {
        return get(BCNetworkSide.CLIENT).listCache.get();
    }

    public static void removeClientSnapshot(Snapshot.Key key) {
        get(BCNetworkSide.CLIENT).removeClientSnapshotInternal(key);
    }

    /**
     * Stores the complete snapshot in persistent dedicated-server machine storage. This is the same serialized
     * blueprint data the client owns, but the server does not expose it as a browsable user library. Integrated
     * servers intentionally keep no snapshots-server copy because the local client library is available in-process.
     */
    public static void cacheServerSnapshot(Level level, Snapshot snapshot) {
        if (level == null || level.isClientSide) {
            throw new IllegalArgumentException("Server snapshot caching requires a server level");
        }
        if (!level.getServer().isDedicatedServer()) {
            return;
        }
        get(BCNetworkSide.SERVER).writeServerSnapshot(snapshot);
    }

    @Nullable
    public static Snapshot getServerSnapshot(@Nullable Snapshot.Key key) {
        if (key == null) {
            return null;
        }
        GlobalSavedDataSnapshots existing = INSTANCES.get(BCNetworkSide.SERVER);
        if (existing != null) {
            return existing.getSnapshot(key);
        }
        File gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().toFile();
        if (!new File(gameDirectory, SERVER_CACHE_DIRECTORY).isDirectory()) {
            return null;
        }
        return get(BCNetworkSide.SERVER).getSnapshot(key);
    }

    /**
     * Resolves snapshot data for machines. Dedicated servers use their cache; singleplayer uses the local client
     * library so a duplicate snapshots-server directory is never required.
     */
    @Nullable
    public static Snapshot getSnapshotForConstruction(Level level, @Nullable Snapshot.Key key) {
        if (level == null || key == null) {
            return null;
        }
        if (level.isClientSide) {
            return getClientSnapshot(key);
        }
        if (!level.getServer().isDedicatedServer()) {
            Snapshot local = getClientSnapshot(key);
            return local != null ? local : getServerSnapshot(key);
        }
        return getServerSnapshot(key);
    }

    private static void ensureDirectory(File directory) {
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new RuntimeException("Failed to make the snapshot directory: " + directory);
            }
        } else if (!directory.isDirectory()) {
            throw new IllegalStateException("The snapshot path was not a directory: " + directory);
        }
    }

    @Nullable
    private Snapshot getSnapshot(@Nullable Snapshot.Key key) {
        if (key == null) {
            return null;
        }
        return snapshotsCache.getUnchecked(key).orElse(null);
    }

    @Nullable
    private Pair<Snapshot, File> readSnapshot(Snapshot.Key key) {
        if (side == BCNetworkSide.SERVER) {
            return readServerSnapshot(key);
        }
        return findClientSnapshot(key, false);
    }

    @Nullable
    private Pair<Snapshot, File> readServerSnapshot(Snapshot.Key key) {
        File snapshotFile = new File(snapshotsFile, key.toString() + SNAPSHOT_FILE_EXTENSION);
        Snapshot snapshot = readSnapshotFile(snapshotFile);
        if (snapshot == null || !sameContentKey(snapshot.key, key)) {
            return null;
        }
        return Pair.of(snapshot, snapshotFile);
    }

    @Nullable
    private Pair<Snapshot, File> findClientSnapshot(Snapshot.Key key, boolean exactOnly) {
        File[] files = snapshotFiles(snapshotsFile);
        Pair<Snapshot, File> contentMatch = null;
        for (File file : files) {
            Snapshot snapshot = readSnapshotFile(file);
            if (snapshot == null) {
                continue;
            }
            if (snapshot.key.equals(key)) {
                return Pair.of(snapshot, file);
            }
            if (!exactOnly && contentMatch == null && sameContentKey(snapshot.key, key)) {
                contentMatch = Pair.of(snapshot, file);
            }
        }
        return contentMatch;
    }

    private List<Snapshot.Key> readClientList() {
        if (side != BCNetworkSide.CLIENT) {
            return List.of();
        }
        List<Snapshot.Key> result = new ArrayList<>();
        for (File file : snapshotFiles(snapshotsFile)) {
            Snapshot snapshot = readSnapshotFile(file);
            if (snapshot != null && !result.contains(snapshot.key)) {
                result.add(snapshot.key);
            }
        }
        result.sort(Comparator
            .comparing(GlobalSavedDataSnapshots::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Snapshot.Key::toString));
        // The client library is intentionally user-editable on disk, so a rescan also drops stale hit/miss entries.
        snapshotsCache.invalidateAll();
        return List.copyOf(result);
    }

    private void writeClientSnapshot(Snapshot snapshot) {
        requireSide(BCNetworkSide.CLIENT);
        Snapshot checked = validatedCopy(snapshot);
        if (checked == null) {
            BCLog.logger.warn("Refused to save a client snapshot with an invalid content key");
            return;
        }
        if (findClientSnapshot(checked.key, true) != null) {
            return;
        }
        writeSnapshotFile(nextClientFile(checked), checked);
        invalidate(checked.key);
    }

    private void writeServerSnapshot(Snapshot snapshot) {
        requireSide(BCNetworkSide.SERVER);
        Snapshot checked = validatedCopy(snapshot);
        if (checked == null) {
            BCLog.logger.warn("Refused to cache a server snapshot with an invalid content key");
            return;
        }
        File snapshotFile = new File(snapshotsFile, checked.key.toString() + SNAPSHOT_FILE_EXTENSION);
        Snapshot existing = readSnapshotFile(snapshotFile);
        // Older BCCE versions stored headerless construction-cache entries. Upgrade those lazily when the
        // same blueprint next reaches the server, while otherwise retaining the first complete copy for a hash.
        if (existing == null || (existing.key.header == null && checked.key.header != null)) {
            writeSnapshotFile(snapshotFile, checked);
        }
        invalidate(checked.key);
    }

    private void removeClientSnapshotInternal(Snapshot.Key key) {
        requireSide(BCNetworkSide.CLIENT);
        Pair<Snapshot, File> found = findClientSnapshot(key, true);
        if (found == null) {
            found = findClientSnapshot(key, false);
        }
        if (found != null && !found.getRight().delete()) {
            BCLog.logger.warn("Failed to delete the client blueprint file: " + found.getRight());
        }
        invalidate(key);
    }

    private void invalidate(Snapshot.Key key) {
        snapshotsCache.invalidate(key);
        snapshotsCache.invalidateAll();
        listCache.clear();
    }

    @Nullable
    private static Snapshot validatedCopy(Snapshot snapshot) {
        if (snapshot == null || snapshot.key == null) {
            return null;
        }
        try {
            Snapshot copy = snapshot.copy();
            Snapshot.Key originalKey = copy.key;
            copy.computeKey();
            if (!sameContentKey(originalKey, copy.key)) {
                return null;
            }
            if (originalKey.header != null && !sameContentKey(originalKey.header.key, copy.key)) {
                return null;
            }
            copy.key = new Snapshot.Key(copy.key, originalKey.header);
            return copy;
        } catch (RuntimeException e) {
            BCLog.logger.warn("Failed to validate BuildCraft snapshot", e);
            return null;
        }
    }

    @Nullable
    private static Snapshot readSnapshotFile(File file) {
        if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(SNAPSHOT_FILE_EXTENSION)) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            Snapshot snapshot = Snapshot.readFromNBT(NbtSquisher.expand(input));
            Snapshot checked = validatedCopy(snapshot);
            if (checked == null) {
                BCLog.logger.warn("Ignored blueprint with an invalid content key: " + file);
            }
            return checked;
        } catch (IOException | RuntimeException e) {
            BCLog.logger.warn("Failed to read the snapshot " + file, e);
            return null;
        }
    }

    private static boolean sameContentKey(Snapshot.Key left, Snapshot.Key right) {
        return left != null && right != null && Arrays.equals(left.hash, right.hash);
    }

    private static File[] snapshotFiles(File directory) {
        File[] files = directory.listFiles(file ->
            file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(SNAPSHOT_FILE_EXTENSION)
        );
        return files == null ? new File[0] : files;
    }

    private File nextClientFile(Snapshot snapshot) {
        String displayName = snapshot.key.header == null ? snapshot.getType().name().toLowerCase(Locale.ROOT)
            : snapshot.key.header.name;
        String safeName = sanitizeFileName(displayName);
        String hash = snapshot.key.toString();
        String shortHash = hash.substring(0, Math.min(CLIENT_FILENAME_HASH_LENGTH, hash.length()));
        String base = safeName + "-" + shortHash;
        File candidate = new File(snapshotsFile, base + SNAPSHOT_FILE_EXTENSION);
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(snapshotsFile, base + "-" + suffix++ + SNAPSHOT_FILE_EXTENSION);
        }
        return candidate;
    }

    private static String sanitizeFileName(String raw) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length() && safe.length() < CLIENT_FILENAME_NAME_LENGTH; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                || c == '<' || c == '>' || c == '|') {
                safe.append('_');
            } else {
                safe.append(c);
            }
        }
        while (safe.length() > 0 && (safe.charAt(safe.length() - 1) == '.' || safe.charAt(safe.length() - 1) == ' ')) {
            safe.setLength(safe.length() - 1);
        }
        String result = safe.toString().trim();
        if (result.isEmpty()) {
            result = "blueprint";
        }
        String upper = result.toUpperCase(Locale.ROOT);
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
            || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) {
            result = "_" + result;
        }
        return result;
    }

    private static String displayName(Snapshot.Key key) {
        return key.header == null || key.header.name == null || key.header.name.isBlank() ? key.toString() : key.header.name;
    }

    private static void writeSnapshotFile(File target, Snapshot snapshot) {
        File temp = null;
        try {
            temp = File.createTempFile(".buildcraft-blueprint-", ".tmp", target.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temp)) {
                NbtSquisher.squishVanilla(Snapshot.writeToNBT(snapshot), output);
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            BCLog.logger.warn("Failed to write the snapshot file: " + target, e);
        } finally {
            if (temp != null && temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private void migrateLegacyClientLibrary(File legacyDirectory) {
        if (!legacyDirectory.isDirectory() || legacyDirectory.equals(snapshotsFile)) {
            return;
        }
        int imported = 0;
        for (File file : snapshotFiles(legacyDirectory)) {
            Snapshot snapshot = readSnapshotFile(file);
            if (snapshot == null) {
                continue;
            }
            Pair<Snapshot, File> existing = findClientSnapshot(snapshot.key, true);
            if (existing != null) {
                if (!file.delete()) {
                    BCLog.logger.warn("Failed to remove migrated legacy blueprint file: " + file);
                }
                continue;
            }
            File target = nextClientFile(snapshot);
            writeSnapshotFile(target, snapshot);
            if (target.isFile()) {
                imported++;
                if (!file.delete()) {
                    BCLog.logger.warn("Failed to remove migrated legacy blueprint file: " + file);
                }
            }
        }
        if (imported > 0) {
            snapshotsCache.invalidateAll();
            listCache.clear();
            BCLog.logger.info("Imported {} legacy BuildCraft blueprint file(s) into {}", imported, snapshotsFile);
        }
    }

    private void requireSide(BCNetworkSide expected) {
        if (side != expected) {
            throw new IllegalStateException("Snapshot store is " + side + ", expected " + expected);
        }
    }
}
