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

    @Override
    public byte[] encode(CompoundTag snapshot) throws IOException {
        String type = snapshot.getString("type");
        if (!type.equals("BLUEPRINT") && !type.equals("TEMPLATE")) throw new IOException("Invalid type");
        return (snapshot.getString("name") + "|" + type).getBytes(StandardCharsets.UTF_8);
    }
}
