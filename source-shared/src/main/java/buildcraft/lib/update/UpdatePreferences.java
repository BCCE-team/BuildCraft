package buildcraft.lib.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Client-local update preferences and successful-check cache. No account or server data is stored. */
public final class UpdatePreferences {
    private static final Path FILE = Path.of("config", "buildcraft-update-checker.json");

    private UpdatePreferences() {}

    public static synchronized boolean isEnabled() {
        try {
            JsonObject root = readRoot();
            return !root.has("enabled") || root.get("enabled").getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    public static synchronized void setEnabled(boolean enabled) {
        JsonObject root = readRoot();
        root.addProperty("schema", 1);
        root.addProperty("enabled", enabled);
        writeRoot(root);
    }

    public static synchronized long failureRetryAt() {
        try {
            JsonObject root = readRoot();
            return root.has("failureRetryAt") ? Math.max(0L, root.get("failureRetryAt").getAsLong()) : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    public static synchronized void setFailureRetryAt(long timestamp) {
        JsonObject root = readRoot();
        root.addProperty("schema", 1);
        root.addProperty("failureRetryAt", Math.max(0L, timestamp));
        writeRoot(root);
    }

    public static synchronized void clearFailureRetryAt() {
        JsonObject root = readRoot();
        root.remove("failureRetryAt");
        writeRoot(root);
    }

    public static synchronized String ignoredVersion(String target) {
        try {
            JsonObject root = readRoot();
            if (!root.has("ignoredVersions") || !root.get("ignoredVersions").isJsonObject()) return "";
            JsonObject ignored = root.getAsJsonObject("ignoredVersions");
            return ignored.has(target) ? ignored.get(target).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static synchronized void clearIgnoredVersions() {
        JsonObject root = readRoot();
        root.remove("ignoredVersions");
        root.addProperty("schema", 1);
        writeRoot(root);
    }

    public static synchronized void ignore(String target, String version) {
        JsonObject root = readRoot();
        JsonObject ignored = root.has("ignoredVersions") && root.get("ignoredVersions").isJsonObject()
            ? root.getAsJsonObject("ignoredVersions") : new JsonObject();
        ignored.addProperty(target, version);
        root.add("ignoredVersions", ignored);
        root.addProperty("schema", 1);
        writeRoot(root);
    }

    public static synchronized Cache readCache(String currentVersion, String target) {
        try {
            JsonObject root = readRoot();
            if (!root.has("cache") || !root.get("cache").isJsonObject()) return null;
            JsonObject cache = root.getAsJsonObject("cache");
            if (!currentVersion.equals(cache.get("currentVersion").getAsString())
                || !target.equals(cache.get("target").getAsString())) return null;
            UpdateCheckResult.Status status = UpdateCheckResult.Status.valueOf(cache.get("status").getAsString());
            String latest = cache.has("latestVersion") ? cache.get("latestVersion").getAsString() : null;
            String release = cache.has("releaseUrl") ? cache.get("releaseUrl").getAsString() : null;
            if (release != null) {
                java.net.URI uri = java.net.URI.create(release);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
                    return null;
                }
            }
            String etag = cache.has("etag") ? cache.get("etag").getAsString() : null;
            long checkedAt = cache.get("checkedAt").getAsLong();
            return new Cache(currentVersion, target, checkedAt, etag, status, latest, release);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void storeCache(String currentVersion, String target, String etag,
        UpdateCheckResult result, long checkedAt) {
        JsonObject root = readRoot();
        JsonObject cache = new JsonObject();
        cache.addProperty("currentVersion", currentVersion);
        cache.addProperty("target", target);
        cache.addProperty("checkedAt", checkedAt);
        if (etag != null && !etag.isBlank()) cache.addProperty("etag", etag);
        cache.addProperty("status", result.status.name());
        if (result.latestVersion != null) cache.addProperty("latestVersion", result.latestVersion);
        if (result.releaseUrl != null) cache.addProperty("releaseUrl", result.releaseUrl.toString());
        root.addProperty("schema", 1);
        root.add("cache", cache);
        writeRoot(root);
    }

    public static synchronized void touchCache(Cache cache, long checkedAt) {
        if (cache == null) return;
        JsonObject root = readRoot();
        JsonObject stored = root.has("cache") && root.get("cache").isJsonObject()
            ? root.getAsJsonObject("cache") : new JsonObject();
        stored.addProperty("currentVersion", cache.currentVersion);
        stored.addProperty("target", cache.target);
        stored.addProperty("checkedAt", checkedAt);
        if (cache.etag != null && !cache.etag.isBlank()) stored.addProperty("etag", cache.etag);
        stored.addProperty("status", cache.status.name());
        if (cache.latestVersion != null) stored.addProperty("latestVersion", cache.latestVersion);
        if (cache.releaseUrl != null) stored.addProperty("releaseUrl", cache.releaseUrl);
        root.add("cache", stored);
        writeRoot(root);
    }

    private static JsonObject readRoot() {
        try {
            if (Files.isRegularFile(FILE)) {
                JsonObject parsed = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                return parsed;
            }
        } catch (Exception ignored) {
            // A malformed local preference file is treated as an empty configuration.
        }
        return new JsonObject();
    }

    private static void writeRoot(JsonObject root) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, root.toString() + "\n");
            try {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Update notifications must never prevent the game from starting.
        }
    }

    public static final class Cache {
        public final String currentVersion;
        public final String target;
        public final long checkedAt;
        public final String etag;
        public final UpdateCheckResult.Status status;
        public final String latestVersion;
        public final String releaseUrl;

        private Cache(String currentVersion, String target, long checkedAt, String etag,
            UpdateCheckResult.Status status, String latestVersion, String releaseUrl) {
            this.currentVersion = currentVersion;
            this.target = target;
            this.checkedAt = checkedAt;
            this.etag = etag;
            this.status = status;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
        }

        public boolean isFresh(long now, long maxAgeMillis) {
            return checkedAt > 0 && now >= checkedAt && now - checkedAt < maxAgeMillis;
        }

        public UpdateCheckResult toResult(String current) {
            java.net.URI uri = releaseUrl == null ? null : java.net.URI.create(releaseUrl);
            return UpdateCheckResult.of(status, current, latestVersion, uri);
        }
    }
}
