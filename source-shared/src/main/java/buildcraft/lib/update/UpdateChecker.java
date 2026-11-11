package buildcraft.lib.update;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Non-blocking GitHub Releases checker. All failures are silent to the game UI. */
public final class UpdateChecker {
    private static final URI RELEASES = URI.create(
        "https://api.github.com/repos/BCCE-team/BuildCraft/releases?per_page=100"
    );
    private static final long UPDATE_CACHE_MAX_AGE_MILLIS = Duration.ofHours(12).toMillis();
    private static final long NO_UPDATE_CACHE_MAX_AGE_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_RELEASES_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    /** Release CI builds all targets and publishes them sequentially before attaching the manifest. */
    private static final long MANIFEST_GRACE_PERIOD_MILLIS = Duration.ofHours(2).toMillis();
    public static final long MANIFEST_PENDING_RECHECK_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final long FAILURE_BACKOFF_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_MANIFESTS_TO_CHECK = 20;
    private static final java.util.regex.Pattern LEGACY_JAR = java.util.regex.Pattern.compile(
        "^BuildCraft-Community-Edition-(.+)\\+([0-9.]+)\\+(forge|neoforge|fabric)\\.jar$"
    );

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "BCCE-Update-Checker");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile HttpClient client;

    private UpdateChecker() {}

    public static CompletableFuture<UpdateCheckResult> check(String currentVersion, String target) {
        return check(currentVersion, target, false);
    }

    /** Force skips the 12-hour result cache but still uses ETag validation. */
    public static CompletableFuture<UpdateCheckResult> check(String currentVersion, String target, boolean force) {
        return CompletableFuture.supplyAsync(() -> checkBlocking(currentVersion, target, force), EXECUTOR);
    }

    private static UpdateCheckResult checkBlocking(String currentVersion, String target, boolean force) {
        final BCVersion current;
        try {
            current = BCVersion.parse(currentVersion);
            if (target == null || !target.matches("[0-9.]+-(forge|neoforge|fabric)")) {
                throw new IllegalArgumentException("Invalid BuildCraft target: " + target);
            }
        } catch (RuntimeException invalidBuild) {
            return failed(currentVersion);
        }

        long now = System.currentTimeMillis();
        UpdatePreferences.Cache cached = UpdatePreferences.readCache(currentVersion, target);
        long cacheAge = cached != null && cached.status == UpdateCheckResult.Status.UPDATE_AVAILABLE
            ? UPDATE_CACHE_MAX_AGE_MILLIS
            : cached != null && cached.status == UpdateCheckResult.Status.MANIFEST_PENDING
                ? MANIFEST_PENDING_RECHECK_MILLIS : NO_UPDATE_CACHE_MAX_AGE_MILLIS;
        if (!force && cached != null && cached.isFresh(now, cacheAge)) {
            return cached.toResult(currentVersion);
        }
        if (!force && now < UpdatePreferences.failureRetryAt()) {
            return failed(currentVersion);
        }

        try {
            String requestEtag = cached == null || cached.status == UpdateCheckResult.Status.MANIFEST_PENDING
                ? null : cached.etag;
            BoundedResponse response = fetchReleaseIndex(requestEtag);
            if (response.statusCode() == 304) {
                if (cached != null) {
                    UpdatePreferences.clearFailureRetryAt();
                    UpdatePreferences.touchCache(cached, now);
                    return cached.toResult(currentVersion);
                }
                response = fetchReleaseIndex(null);
            }
            if (response.statusCode() != 200) {
                UpdatePreferences.setFailureRetryAt(retryAt(response, now));
                return failed(currentVersion);
            }
            if (response.body().length > MAX_RELEASES_BYTES) {
                UpdatePreferences.setFailureRetryAt(now + FAILURE_BACKOFF_MILLIS);
                return failed(currentVersion);
            }
            UpdatePreferences.clearFailureRetryAt();

            JsonArray releases = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonArray();
            List<Candidate> candidates = new ArrayList<>();
            boolean newerStableReleaseSeen = false;

            for (JsonElement element : releases) {
                if (!element.isJsonObject()) continue;
                JsonObject release = element.getAsJsonObject();
                if (bool(release, "draft") || bool(release, "prerelease")) continue;
                String tag = string(release, "tag_name");
                BCVersion version;
                try {
                    version = BCVersion.parse(tag);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (version.compareTo(current) <= 0) continue;
                newerStableReleaseSeen = true;
                String releaseUrl = string(release, "html_url");
                if (!isTrustedGithubUrl(releaseUrl)) continue;
                long publishedAt = parsePublishedAt(string(release, "published_at"));
                if (!release.has("assets") || !release.get("assets").isJsonArray()) {
                    candidates.add(new Candidate(version, URI.create(releaseUrl), null, Set.of(), publishedAt));
                    continue;
                }

                JsonObject manifestAsset = null;
                Set<String> legacyTargets = new HashSet<>();
                for (JsonElement assetElement : release.getAsJsonArray("assets")) {
                    if (!assetElement.isJsonObject()) continue;
                    JsonObject asset = assetElement.getAsJsonObject();
                    String assetName = string(asset, "name");
                    if ("bcce-release-manifest.json".equals(assetName)) {
                        manifestAsset = asset;
                        continue;
                    }
                    java.util.regex.Matcher legacyJar = LEGACY_JAR.matcher(assetName);
                    if (legacyJar.matches()) {
                        legacyTargets.add(legacyJar.group(2) + "-" + legacyJar.group(3));
                    }
                }
                String assetUrl = manifestAsset == null ? "" : string(manifestAsset, "browser_download_url");
                URI manifestUrl = isTrustedGithubUrl(assetUrl) ? URI.create(assetUrl) : null;
                candidates.add(new Candidate(version, URI.create(releaseUrl), manifestUrl,
                    legacyTargets, publishedAt));
            }

            candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.version).reversed());
            UpdateCheckResult result = null;
            boolean manifestPending = false;
            int manifestsChecked = 0;
            for (Candidate candidate : candidates) {
                if (manifestsChecked++ >= MAX_MANIFESTS_TO_CHECK) {
                    manifestPending = true;
                    break;
                }
                if (candidate.manifestUrl == null) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    if (candidate.legacyTargets.contains(target)) {
                        result = UpdateCheckResult.of(UpdateCheckResult.Status.UPDATE_AVAILABLE,
                            currentVersion, candidate.version.display(), candidate.releaseUrl);
                        break;
                    }
                    continue;
                }

                JsonObject manifest = fetchManifest(candidate.manifestUrl);
                if (manifest == null || !manifest.has("version") || !manifest.has("targets")
                    || !manifest.get("version").isJsonPrimitive() || !manifest.get("targets").isJsonArray()) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    if (candidate.legacyTargets.contains(target)) {
                        result = UpdateCheckResult.of(UpdateCheckResult.Status.UPDATE_AVAILABLE,
                            currentVersion, candidate.version.display(), candidate.releaseUrl);
                        break;
                    }
                    continue;
                }

                String manifestVersion = manifest.get("version").getAsString();
                BCVersion parsedVersion;
                try {
                    parsedVersion = BCVersion.parse(manifestVersion);
                } catch (IllegalArgumentException ignored) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    continue;
                }
                if (parsedVersion.compareTo(candidate.version) != 0) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    continue;
                }

                Set<String> targets = new HashSet<>();
                boolean malformed = false;
                for (JsonElement targetElement : manifest.getAsJsonArray("targets")) {
                    if (!targetElement.isJsonPrimitive() || !targetElement.getAsJsonPrimitive().isString()) {
                        malformed = true;
                        break;
                    }
                    targets.add(targetElement.getAsString());
                }
                if (malformed) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    continue;
                }

                ReleaseManifest parsedManifest;
                try {
                    parsedManifest = new ReleaseManifest(parsedVersion.display(), targets);
                } catch (IllegalArgumentException invalidManifest) {
                    if (candidate.isRecentlyPublished(now, MANIFEST_GRACE_PERIOD_MILLIS)) {
                        manifestPending = true;
                        break;
                    }
                    continue;
                }
                if (parsedManifest.supports(target)) {
                    result = UpdateCheckResult.of(UpdateCheckResult.Status.UPDATE_AVAILABLE,
                        currentVersion, parsedVersion.display(), candidate.releaseUrl);
                    break;
                }
            }

            if (result == null) {
                UpdateCheckResult.Status status = manifestPending
                    ? UpdateCheckResult.Status.MANIFEST_PENDING
                    : newerStableReleaseSeen ? UpdateCheckResult.Status.NO_COMPATIBLE_RELEASE
                        : UpdateCheckResult.Status.UP_TO_DATE;
                result = UpdateCheckResult.of(status, currentVersion, null, null);
            }
            String etag = response.headers().firstValue("ETag").orElse(null);
            UpdatePreferences.storeCache(currentVersion, target, etag, result, System.currentTimeMillis());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(currentVersion);
        } catch (Exception exception) {
            UpdatePreferences.setFailureRetryAt(System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS);
            return failed(currentVersion);
        }
    }

    private static long retryAt(BoundedResponse response, long now) {
        long retryAt = now + FAILURE_BACKOFF_MILLIS;
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        if (!retryAfter.isBlank()) {
            try {
                long seconds = Math.max(0L, Long.parseLong(retryAfter.trim()));
                long boundedSeconds = Math.min(seconds, Duration.ofHours(24).toSeconds());
                retryAt = Math.max(retryAt, now + boundedSeconds * 1000L);
            } catch (NumberFormatException ignored) {
                try {
                    long date = java.time.ZonedDateTime.parse(retryAfter,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                    retryAt = Math.max(retryAt, date);
                } catch (RuntimeException ignoredDate) {
                    // Fall back to the bounded local backoff.
                }
            }
        }
        String reset = response.headers().firstValue("X-RateLimit-Reset").orElse("");
        try {
            long resetSeconds = Long.parseLong(reset.trim());
            if (resetSeconds > 0L && resetSeconds <= Long.MAX_VALUE / 1000L) {
                retryAt = Math.max(retryAt, resetSeconds * 1000L);
            }
        } catch (RuntimeException ignored) {
            // Header is absent or malformed.
        }
        return Math.min(retryAt, now + Duration.ofHours(24).toMillis());
    }

    private static HttpClient client() {
        HttpClient value = client;
        if (value != null) return value;
        synchronized (UpdateChecker.class) {
            value = client;
            if (value == null) {
                value = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
                client = value;
            }
        }
        return value;
    }

    private static BoundedResponse fetchReleaseIndex(String etag) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(RELEASES)
            .timeout(Duration.ofSeconds(6))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BCCE-Update-Checker")
            .GET();
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        HttpResponse<InputStream> response = client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream input = response.body()) {
            body = response.statusCode() == 200 ? readBounded(input, MAX_RELEASES_BYTES) : new byte[0];
        }
        if (body == null) throw new IOException("GitHub release index exceeds the configured size limit");
        return new BoundedResponse(response.statusCode(), response.headers(), body);
    }

    private static final class BoundedResponse {
        private final int statusCode;
        private final HttpHeaders headers;
        private final byte[] body;

        BoundedResponse(int statusCode, HttpHeaders headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        int statusCode() { return statusCode; }
        HttpHeaders headers() { return headers; }
        byte[] body() { return body; }
    }

    private static JsonObject fetchManifest(URI url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "BCCE-Update-Checker")
            .GET().build();
        HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) return null;
            byte[] bytes = readBounded(input, MAX_MANIFEST_BYTES);
            if (bytes == null) return null;
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static long parsePublishedAt(String value) {
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean isTrustedGithubUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                && ("github.com".equalsIgnoreCase(uri.getHost())
                    || "api.github.com".equalsIgnoreCase(uri.getHost()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString();
    }

    private static UpdateCheckResult failed(String currentVersion) {
        return UpdateCheckResult.of(UpdateCheckResult.Status.FAILED, currentVersion, null, null);
    }

    private static final class Candidate {
        final BCVersion version;
        final URI releaseUrl;
        final URI manifestUrl;
        final Set<String> legacyTargets;
        final long publishedAt;

        Candidate(BCVersion version, URI releaseUrl, URI manifestUrl, Set<String> legacyTargets, long publishedAt) {
            this.version = version;
            this.releaseUrl = releaseUrl;
            this.manifestUrl = manifestUrl;
            this.legacyTargets = Set.copyOf(legacyTargets);
            this.publishedAt = publishedAt;
        }

        boolean isRecentlyPublished(long now, long gracePeriod) {
            return publishedAt <= 0L || now < publishedAt || now - publishedAt < gracePeriod;
        }
    }
}
