package buildcraft.lib.compat;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import buildcraft.lib.internal.debug.BCLog;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

/** 26.1 compatibility for SavedData's move from flat string ids to namespaced ids. */
public final class SavedDataCompat {
    private SavedDataCompat() {}

    /**
     * Moves an existing pre-26.1 {@code data/<legacyName>.dat} file to the location
     * selected by the 26.1 {@link SavedDataType} id. Existing new-format data wins.
     */
    public static void migrateLegacyFlatFile(ServerLevel level, SavedDataType<?> type, String legacyName) {
        Path dataRoot = dimensionRoot(level).resolve("data");
        Path legacy = dataRoot.resolve(legacyName + ".dat");
        Identifier id = type.id();
        Path current = dataRoot.resolve(id.getNamespace()).resolve(id.getPath() + ".dat");

        if (!Files.isRegularFile(legacy) || Files.exists(current)) return;

        try {
            Files.createDirectories(current.getParent());
            try {
                Files.move(legacy, current, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(legacy, current);
            }
            BCLog.logger.info("Migrated legacy SavedData {} to {}", legacy, current);
        } catch (IOException e) {
            // Do not make a world unopenable because an old optional BuildCraft data file
            // could not be moved. Keeping the legacy file untouched allows manual recovery.
            BCLog.logger.warn("Could not migrate legacy SavedData {} to {}", legacy, current, e);
        }
    }

    private static Path dimensionRoot(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceKey<Level> dimension = level.dimension();
        if (Level.OVERWORLD.equals(dimension)) return root;
        if (Level.NETHER.equals(dimension)) return root.resolve("DIM-1");
        if (Level.END.equals(dimension)) return root.resolve("DIM1");

        Identifier id = dimension.identifier();
        return root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
    }
}
