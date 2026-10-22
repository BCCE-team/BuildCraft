package buildcraft.builders.internal.schematic.api2;

import buildcraft.api.v2.library.LibraryEntry;
import buildcraft.api.v2.library.LibraryFormatHandler;
import buildcraft.api.v2.library.LibraryFormatRegistration;
import buildcraft.api.v2.library.LibraryService;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** The Electronic Library remains the sole owner of snapshot files and their content-addressed cache. */
public final class LibraryServiceImpl implements LibraryService {
    public static final LibraryServiceImpl INSTANCE = new LibraryServiceImpl();
    public static final int MAX_FORMAT_BYTES = 8 * 1024 * 1024;

    private final Map<ResourceLocation, LibraryFormatRegistration> registered = new LinkedHashMap<>();
    private volatile List<LibraryFormatRegistration> snapshot = List.of();

    private LibraryServiceImpl() {}

    @Override
    public synchronized void register(ResourceLocation id, String extension, int priority, LibraryFormatHandler handler) {
        LibraryFormatRegistration registration = new LibraryFormatRegistration(id, extension, priority, handler);
        if (registered.containsKey(id)) throw new IllegalStateException("Duplicate library format: " + id);
        for (LibraryFormatRegistration existing : snapshot) {
            if (existing.extension().equals(registration.extension())) {
                throw new IllegalStateException("Duplicate library file extension: " + extension);
            }
        }
        registered.put(id, registration);
        List<LibraryFormatRegistration> ordered = new ArrayList<>(registered.values());
        ordered.sort(Comparator.comparingInt(LibraryFormatRegistration::priority).reversed()
            .thenComparing(entry -> entry.id().toString()));
        snapshot = List.copyOf(ordered);
    }

    @Override public List<LibraryFormatRegistration> formats() { return snapshot; }

    @Override
    public Optional<LibraryFormatRegistration> formatForFilename(String filename) {
        if (filename == null) return Optional.empty();
        String normalized = filename.toLowerCase(Locale.ROOT);
        return snapshot.stream().filter(entry -> normalized.endsWith("." + entry.extension())).findFirst();
    }

    @Override
    public Optional<CompoundTag> decode(String filename, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        Optional<LibraryFormatRegistration> registration = formatForFilename(filename);
        if (registration.isEmpty()) return Optional.empty();
        requireSize(content.length);
        try {
            CompoundTag document = registration.get().handler().decode(content.clone());
            if (document == null) throw new IOException("Library format returned a null snapshot: " + filename);
            return Optional.of(document.copy());
        } catch (RuntimeException e) {
            throw new IOException("Library format failed to decode " + filename, e);
        }
    }

    @Override
    public byte[] encode(ResourceLocation formatId, CompoundTag snapshotTag) throws IOException {
        Objects.requireNonNull(formatId, "formatId");
        Objects.requireNonNull(snapshotTag, "snapshotTag");
        LibraryFormatRegistration registration = snapshot.stream()
            .filter(entry -> entry.id().equals(formatId)).findFirst()
            .orElseThrow(() -> new IOException("Unregistered library format: " + formatId));
        try {
            byte[] content = registration.handler().encode(snapshotTag.copy());
            if (content == null) throw new IOException("Library format returned null bytes: " + formatId);
            requireSize(content.length);
            return content.clone();
        } catch (RuntimeException e) {
            throw new IOException("Library format failed to encode " + formatId, e);
        }
    }

    private static void requireSize(int size) throws IOException {
        if (size > MAX_FORMAT_BYTES) throw new IOException("Library format exceeds " + MAX_FORMAT_BYTES + " bytes");
    }

    @Override
    public List<LibraryEntry> clientEntries() {
        List<LibraryEntry> entries = new ArrayList<>();
        for (Snapshot.Key key : GlobalSavedDataSnapshots.getClientSnapshotList()) {
            Snapshot value = GlobalSavedDataSnapshots.getClientSnapshot(key);
            if (value != null) entries.add(entry(value));
        }
        return List.copyOf(entries);
    }

    @Override
    public Optional<CompoundTag> clientDocument(String contentId) {
        return clientSnapshot(contentId).map(snapshot -> Snapshot.writeToNBT(snapshot).copy());
    }

    @Override
    public LibraryEntry importClient(String filename, byte[] content) throws IOException {
        CompoundTag tag = decode(filename, content).orElseThrow(() -> new IOException("Unknown library format: " + filename));
        Snapshot imported = normalizedSnapshot(filename, tag);
        GlobalSavedDataSnapshots.saveClientSnapshot(imported);
        if (clientSnapshot(imported.key.toString()).isEmpty()) {
            throw new IOException("Electronic Library could not persist imported snapshot: " + filename);
        }
        return entry(imported);
    }

    @Override
    public byte[] exportClient(String contentId, ResourceLocation formatId) throws IOException {
        Snapshot value = clientSnapshot(contentId).orElseThrow(() -> new IOException("Unknown library content: " + contentId));
        return encode(formatId, Snapshot.writeToNBT(value));
    }

    public static Snapshot normalizedSnapshot(String filename, CompoundTag tag) throws IOException {
        try {
            Snapshot snapshot = Snapshot.readFromNBT(tag.copy());
            Snapshot.Header header = snapshot.key.header;
            snapshot.computeKey();
            if (header == null) {
                String name = filename == null ? "Imported blueprint" : filename.replaceFirst("(?i)\\.[^.]+$", "");
                if (name.length() > 80) name = name.substring(0, 80);
                header = new Snapshot.Header(snapshot.key, new UUID(0L, 0L), new Date(0L), name);
            } else {
                header = new Snapshot.Header(snapshot.key, header.owner, header.ownerName, header.created,
                    header.name, header.allowCreative, header.canRotate, header.canExcavate);
            }
            snapshot.key = new Snapshot.Key(snapshot.key, header);
            return snapshot;
        } catch (IOException | RuntimeException e) {
            throw new IOException("Invalid imported blueprint or template: " + filename, e);
        }
    }

    private static Optional<Snapshot> clientSnapshot(String contentId) {
        if (contentId == null) return Optional.empty();
        for (Snapshot.Key key : GlobalSavedDataSnapshots.getClientSnapshotList()) {
            if (key.toString().equals(contentId)) return Optional.ofNullable(GlobalSavedDataSnapshots.getClientSnapshot(key));
        }
        return Optional.empty();
    }

    private static LibraryEntry entry(Snapshot snapshot) {
        Snapshot.Header header = snapshot.key.header;
        return new LibraryEntry(snapshot.key.toString(), header == null ? "" : header.name,
            Objects.requireNonNull(ResourceLocation.tryParse("buildcraft:" + snapshot.getType().name().toLowerCase(Locale.ROOT))));
    }
}
