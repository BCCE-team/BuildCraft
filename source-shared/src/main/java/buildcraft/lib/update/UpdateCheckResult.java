package buildcraft.lib.update;

import java.net.URI;

public final class UpdateCheckResult {
    public enum Status { UPDATE_AVAILABLE, UP_TO_DATE, NO_COMPATIBLE_RELEASE, MANIFEST_PENDING, FAILED }
    public final Status status;
    public final String currentVersion;
    public final String latestVersion;
    public final URI releaseUrl;
    private UpdateCheckResult(Status status, String current, String latest, URI url) {
        this.status = status; this.currentVersion = current; this.latestVersion = latest; this.releaseUrl = url;
    }
    public static UpdateCheckResult of(Status status, String current, String latest, URI url) {
        return new UpdateCheckResult(status, current, latest, url);
    }
}
