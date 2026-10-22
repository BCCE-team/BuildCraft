package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.guide.GuideEntry;
import buildcraft.api.v2.guide.GuideSection;
import buildcraft.api.v2.guide.GuideService;
import buildcraft.api.v2.registry.RegistrationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Internal storage/merge backend for code-owned Guide Book contributions. */
public final class GuideServiceImpl implements GuideService, GuideOwnershipView {
    private final Map<Identifier, GuideSection> sections = new LinkedHashMap<>();
    private final Map<Identifier, GuideEntry> entries = new LinkedHashMap<>();
    private final Map<Identifier, String> sectionOwners = new LinkedHashMap<>();
    private final Map<Identifier, String> entryOwners = new LinkedHashMap<>();

    public synchronized void registerSection(GuideSection section, RegistrationContext context) {
        Objects.requireNonNull(section, "section");
        String owner = owner(context);
        GuideSection previous = sections.putIfAbsent(section.id(), section);
        if (previous != null) {
            throw new IllegalStateException("Duplicate guide section " + section.id() + " from " + owner
                + "; already owned by " + sectionOwners.get(section.id()));
        }
        sectionOwners.put(section.id(), owner);
    }

    public synchronized void registerEntry(GuideEntry entry, RegistrationContext context) {
        Objects.requireNonNull(entry, "entry");
        String owner = owner(context);
        GuideEntry previous = entries.putIfAbsent(entry.id(), entry);
        if (previous != null) {
            throw new IllegalStateException("Duplicate guide entry " + entry.id() + " from " + owner
                + "; already owned by " + entryOwners.get(entry.id()));
        }
        entryOwners.put(entry.id(), owner);
    }

    public synchronized Optional<GuideSection> section(Identifier id) {
        return Optional.ofNullable(sections.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Optional<GuideEntry> entry(Identifier id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized List<GuideSection> sections() {
        List<GuideSection> result = new ArrayList<>(sections.values());
        result.sort(Comparator.comparingInt(GuideSection::order).thenComparing(value -> value.id().toString()));
        return List.copyOf(result);
    }

    public synchronized List<GuideEntry> entries() {
        List<GuideEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparingInt(GuideEntry::order).thenComparing(value -> value.id().toString()));
        return List.copyOf(result);
    }

    public synchronized List<GuideEntry> entries(Identifier sectionId) {
        Objects.requireNonNull(sectionId, "sectionId");
        List<GuideEntry> result = new ArrayList<>();
        for (GuideEntry entry : entries.values()) {
            if (entry.section().equals(sectionId)) result.add(entry);
        }
        result.sort(Comparator.comparingInt(GuideEntry::order).thenComparing(value -> value.id().toString()));
        return List.copyOf(result);
    }

    public synchronized Optional<String> ownerOfSection(Identifier id) {
        return Optional.ofNullable(sectionOwners.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized Optional<String> ownerOfEntry(Identifier id) {
        return Optional.ofNullable(entryOwners.get(Objects.requireNonNull(id, "id")));
    }

    private static String owner(RegistrationContext context) {
        String owner = Objects.requireNonNull(context, "context").owner();
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Guide registration owner must not be blank");
        return owner;
    }
}
