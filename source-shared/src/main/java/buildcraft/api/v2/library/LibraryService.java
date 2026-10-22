package buildcraft.api.v2.library;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Extensible Electronic Library formats and client-local blueprint import/export. */
public interface LibraryService {
    void register(ResourceLocation id, String extension, int priority, LibraryFormatHandler handler);
    List<LibraryFormatRegistration> formats();
    Optional<LibraryFormatRegistration> formatForFilename(String filename);

    /** The returned document uses the stable Snapshot NBT envelope; callers receive their own copy. */
    Optional<CompoundTag> decode(String filename, byte[] content) throws IOException;
    byte[] encode(ResourceLocation formatId, CompoundTag snapshot) throws IOException;

    /** These methods affect only the local player's blueprint library, never server-world storage. */
    List<LibraryEntry> clientEntries();
    Optional<CompoundTag> clientDocument(String contentId);
    LibraryEntry importClient(String filename, byte[] content) throws IOException;
    byte[] exportClient(String contentId, ResourceLocation formatId) throws IOException;
}
