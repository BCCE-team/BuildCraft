/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.lib.client.gui;

import buildcraft.api.v2.config.ScreenMode;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Client-instance GUI preferences, independent of the current world or server. */
public final class ClientGuiModePreferences {
    public enum Mode {
        LEGACY, MODERN;

        public static Mode parse(String text) {
            return text != null && "legacy".equalsIgnoreCase(text.trim()) ? LEGACY : MODERN;
        }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final String ELECTRONIC_LIBRARY = "electronic_library";
    public static final String ZONE_PLANNER = "zone_planner";
    private static final Path FILE = Path.of(System.getProperty("user.dir", "."),
        "config", "buildcraft_gui_modes.properties");
    private static final Properties VALUES = new Properties();
    private static boolean loaded;

    private ClientGuiModePreferences() { }

    public static ScreenMode policy(String key) {
        String id = switch (key) {
            case ELECTRONIC_LIBRARY -> "buildcraftcore:display/electronicLibraryMode";
            case ZONE_PLANNER -> "buildcraftcore:display/zonePlannerMode";
            default -> throw new IllegalArgumentException("Unknown screen: " + key);
        };
        return ConfigurationRegistry.INSTANCE.screenMode(id);
    }

    public static boolean canSwitch(String key) { return policy(key).switchable(); }

    public static synchronized Mode get(String key) {
        load();
        return policy(key).useLegacy(Mode.parse(VALUES.getProperty(key)) == Mode.LEGACY) ? Mode.LEGACY : Mode.MODERN;
    }

    public static synchronized void set(String key, Mode mode) {
        if (!canSwitch(key)) return;
        load();
        VALUES.setProperty(key, mode.id());
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = Files.createTempFile(FILE.getParent(), "buildcraft_gui_modes", ".tmp");
            try {
                try (OutputStream stream = Files.newOutputStream(temp)) {
                    VALUES.store(stream, "Client-side BuildCraft GUI preferences");
                }
                try {
                    Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            // Config write failures are non-fatal: the in-memory preference still applies.
        }
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(FILE)) return;
        try (InputStream stream = Files.newInputStream(FILE)) {
            VALUES.load(stream);
        } catch (IOException ex) {
            // A corrupt local preference must not prevent Minecraft from opening a screen.
        }
    }
}
