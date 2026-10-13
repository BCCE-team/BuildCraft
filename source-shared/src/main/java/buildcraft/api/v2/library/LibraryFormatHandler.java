package buildcraft.api.v2.library;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;

/** Reads and writes a serialized blueprint or template without depending on Builder implementation classes. */
public interface LibraryFormatHandler {
    CompoundTag decode(byte[] bytes) throws IOException;
    byte[] encode(CompoundTag snapshot) throws IOException;
}
