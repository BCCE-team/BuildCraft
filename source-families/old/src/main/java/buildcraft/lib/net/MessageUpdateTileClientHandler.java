package buildcraft.lib.net;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Client-only access used by the legacy Forge packet bridge. */
public final class MessageUpdateTileClientHandler {
    private MessageUpdateTileClientHandler() {
    }

    public static Level getClientLevel() {
        return Minecraft.getInstance().level;
    }
}
