/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * SPDX-License-Identifier: MPL-2.0 */
package buildcraft.lib.client.config;

import buildcraft.lib.internal.debug.BCLogProfile;
import buildcraft.lib.internal.debug.BCLogProfile.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small Core/Debug TOML editor for loaders without a generated config screen
 * (Forge 1.19/1.20 and Fabric Mod Menu). Keeps all other Core sections and
 * comments intact. The native ConfigBinding remains responsible for loading.
 */
public final class CoreDebugSettingsFile {
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^]]+)]\\s*(?:#.*)?$");
    private static final Pattern KEY = Pattern.compile("^(\\s*)(loggingProfile|verboseRobotLogging)(\\s*=\\s*)(.*)$");
    private static final Path DEFAULT_PATH = Path.of("config", "buildcraftcore-common.toml");

    private CoreDebugSettingsFile() { }

    public record Settings(Profile profile, boolean verboseRobotLogging) { }

    public static Settings read() {
        return read(DEFAULT_PATH);
    }

    public static Settings read(Path file) {
        Profile profile = Profile.DEFAULT;
        boolean robotTrace = false;
        if (!Files.isRegularFile(file)) return new Settings(profile, robotTrace);
        try {
            boolean debug = false;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher section = SECTION.matcher(line);
                if (section.matches()) { debug = section.group(1).trim().equals("debug"); continue; }
                if (!debug) continue;
                Matcher key = KEY.matcher(line);
                if (!key.matches()) continue;
                String value = key.group(4).split("#", 2)[0].trim();
                if ("loggingProfile".equals(key.group(2))) {
                    value = unquote(value);
                    Profile parsed = BCLogProfile.parse(value);
                    if (parsed != null) profile = parsed;
                } else if ("true".equals(value) || "false".equals(value)) {
                    robotTrace = Boolean.parseBoolean(value);
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Safe defaults on a read-only/temporarily unavailable config.
        }
        return new Settings(profile, robotTrace);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static void save(Profile profile, boolean verboseRobotLogging) throws IOException {
        save(DEFAULT_PATH, profile, verboseRobotLogging);
    }

    public static synchronized void save(Path file, Profile profile, boolean verboseRobotLogging) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(profile, "profile");
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        String original = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(original.split("\\r?\\n", -1)));
        if (lines.size() == 1 && lines.get(0).isEmpty()) lines.clear();
        boolean inDebug = false;
        boolean foundSection = false;
        boolean foundProfile = false;
        boolean foundRobot = false;
        int insertion = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                if (inDebug) { insertion = i; inDebug = false; }
                if (section.group(1).trim().equals("debug")) {
                    inDebug = true;
                    foundSection = true;
                    insertion = lines.size();
                }
                continue;
            }
            if (!inDebug) continue;
            Matcher key = KEY.matcher(line);
            if (!key.matches()) continue;
            String replacement = "loggingProfile".equals(key.group(2))
                ? "\"" + profile.name().toUpperCase(Locale.ROOT) + "\""
                : Boolean.toString(verboseRobotLogging);
            String suffix = "";
            int comment = key.group(4).indexOf('#');
            if (comment >= 0) suffix = " " + key.group(4).substring(comment).trim();
            lines.set(i, key.group(1) + key.group(2) + key.group(3) + replacement + suffix);
            if ("loggingProfile".equals(key.group(2))) foundProfile = true;
            else foundRobot = true;
        }
        if (!foundSection) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add("[debug]");
            insertion = lines.size();
        }
        // Insert before the next section, not at the end of an unrelated section.
        if (!foundProfile) lines.add(insertion++, "loggingProfile = \"" + profile.name() + "\"");
        if (!foundRobot) lines.add(insertion, "verboseRobotLogging = " + verboseRobotLogging);
        // Preserve existing trailing newline when possible.
        String output = String.join(newline, lines);
        if (!output.endsWith("\n")) output += newline;
        Path temp = Files.createTempFile(parent, "buildcraftcore-debug-", ".tmp");
        try {
            Files.writeString(temp, output, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
