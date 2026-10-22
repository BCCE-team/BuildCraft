package dev.bcce.apifixture;

import buildcraft.api.v2.library.LibraryFormatHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;

/** Independent example of a custom blueprint library file codec. */
public final class BlueprintLibraryFixtureAddon implements LibraryFormatHandler {
    @Override
    public CompoundTag decode(byte[] bytes) throws IOException {
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 2 || !parts[1].equals("BLUEPRINT") && !parts[1].equals("TEMPLATE")) {
            throw new IOException("Invalid example blueprint");
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("name", parts[0]);
        snapshot.putString("type", parts[1]);
        return snapshot;
    }

    private static String readString(CompoundTag snapshot, String key) {
        Object value = snapshot.getString(key);
        if (value instanceof java.util.Optional<?> optional) {
            return optional.map(Object::toString).orElse("");
        }
        return value instanceof String string ? string : "";
    }

    @Override
    public byte[] encode(CompoundTag snapshot) throws IOException {
        String type = readString(snapshot, "type");
        if (!type.equals("BLUEPRINT") && !type.equals("TEMPLATE")) throw new IOException("Invalid type");
        return (readString(snapshot, "name") + "|" + type).getBytes(StandardCharsets.UTF_8);
    }
}
