package buildcraft.lib.update.client;

import java.util.concurrent.CompletableFuture;

import buildcraft.lib.net.BuildCraftTarget;
import buildcraft.lib.update.BCVersion;
import buildcraft.lib.update.UpdateCheckResult;
import buildcraft.lib.update.UpdateChecker;
import buildcraft.lib.update.UpdatePreferences;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Client-only coordinator for startup checks and safe main-menu notification timing. */
public final class UpdateNotificationManager {
    private static final int MAIN_MENU_SETTLE_TICKS = 20;

    private static boolean started;
    private static boolean previouslyEnabled;
    private static boolean shownThisSession;
    private static boolean manualCheck;
    private static int titleScreenTicks;
    private static long nextManifestRetryAt;
    private static CompletableFuture<UpdateCheckResult> checkFuture;
    private static UpdateCheckResult result;

    private UpdateNotificationManager() {}

    /** Called from the existing client tick hook. The first call starts one asynchronous check. */
    public static void tick() {
        boolean enabled = UpdatePreferences.isEnabled();
        if ((!started || !previouslyEnabled) && enabled && checkFuture == null) {
            result = null;
            checkFuture = UpdateChecker.check(BuildCraftTarget.MOD_VERSION, BuildCraftTarget.TARGET_ID);
        }
        started = true;
        previouslyEnabled = enabled;
        if (checkFuture != null && checkFuture.isDone()) {
            try {
                result = checkFuture.join();
            } catch (RuntimeException ignored) {
                result = UpdateCheckResult.of(UpdateCheckResult.Status.FAILED,
                    BuildCraftTarget.MOD_VERSION, null, null);
            }
            checkFuture = null;
            if (result.status != UpdateCheckResult.Status.UPDATE_AVAILABLE) manualCheck = false;
            nextManifestRetryAt = result.status == UpdateCheckResult.Status.MANIFEST_PENDING
                ? System.currentTimeMillis() + UpdateChecker.MANIFEST_PENDING_RECHECK_MILLIS : 0L;
        }
        if (checkFuture == null && result != null
            && result.status == UpdateCheckResult.Status.MANIFEST_PENDING
            && nextManifestRetryAt > 0L && System.currentTimeMillis() >= nextManifestRetryAt
            && UpdatePreferences.isEnabled()) {
            result = null;
            nextManifestRetryAt = 0L;
            checkFuture = UpdateChecker.check(BuildCraftTarget.MOD_VERSION, BuildCraftTarget.TARGET_ID, true);
        }
        if (shownThisSession || !UpdatePreferences.notificationsEnabled()) return;
        if (checkFuture != null || result == null
            || result.status != UpdateCheckResult.Status.UPDATE_AVAILABLE
            || result.latestVersion == null || result.releaseUrl == null) return;
        if ((!manualCheck && !UpdatePreferences.isEnabled()) || isIgnored(result.latestVersion)) {
            manualCheck = false;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Screen currentScreen = UpdateScreenCompat.currentScreen();
        if (minecraft.level == null && minecraft.player == null && currentScreen instanceof TitleScreen) {
            titleScreenTicks++;
            if (titleScreenTicks >= MAIN_MENU_SETTLE_TICKS) {
                Screen parent = currentScreen;
                shownThisSession = true;
                manualCheck = false;
                try {
                    UpdateScreenCompat.setScreen(new UpdateWarningScreen(parent, result, BuildCraftTarget.TARGET_ID));
                } catch (RuntimeException | LinkageError ignored) {
                    // A broken third-party screen integration must not crash the client tick.
                }
            }
        } else {
            // Keep the result pending while a world, connection screen, or another menu is active.
            titleScreenTicks = 0;
        }
    }

    /** Read-only status for the configuration editor, even with notifications disabled. */
    public static String statusKey() {
        if (checkFuture != null) return "checking";
        return result == null ? "idle" : result.status.name().toLowerCase(java.util.Locale.ROOT);
    }
    public static String latestVersion() { return result == null || result.latestVersion == null ? "" : result.latestVersion; }

    /** Explicit user action skips the result cache but keeps conditional HTTP validation. */
    public static void checkNow() {
        if (checkFuture != null && !checkFuture.isDone()) return;
        checkFuture = UpdateChecker.check(BuildCraftTarget.MOD_VERSION, BuildCraftTarget.TARGET_ID, true);
        result = null;
        manualCheck = true;
        started = true;
        shownThisSession = false;
        titleScreenTicks = 0;
        nextManifestRetryAt = 0L;
    }

    private static boolean isIgnored(String version) {
        String ignored = UpdatePreferences.ignoredVersion(BuildCraftTarget.TARGET_ID);
        if (ignored.isBlank()) return false;
        try {
            return BCVersion.parse(ignored).compareTo(BCVersion.parse(version)) >= 0;
        } catch (IllegalArgumentException ignoredValue) {
            return false;
        }
    }
}
