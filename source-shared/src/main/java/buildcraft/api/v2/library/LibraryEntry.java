package buildcraft.api.v2.library;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** A client-local blueprint/template identified by the content digest used by Electronic Library. */
public record LibraryEntry(String contentId, String name, ResourceLocation kind) {
    public LibraryEntry {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }
}
