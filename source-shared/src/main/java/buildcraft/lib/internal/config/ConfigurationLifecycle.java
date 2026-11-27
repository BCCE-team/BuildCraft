package buildcraft.lib.internal.config;

import buildcraft.lib.balance.BalanceConfig;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.builders.BCBuildersConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;

/** Only the server owns world configuration, including an integrated server. */
public final class ConfigurationLifecycle {
    private static MinecraftServer server;
    private ConfigurationLifecycle() {}
    public static void start(MinecraftServer next) {
        try {
            BalanceConfig.initialize();
            ConfigurationRegistry.INSTANCE.persistDefaults();
            if (next.isDedicatedServer()) ConfigurationRegistry.INSTANCE.openWorld(next.getWorldPath(LevelResource.ROOT));
            else ConfigurationRegistry.INSTANCE.openLocalWorld();
            server = next;
            buildcraft.energy.OilIndustry.setServerRunning(true);
            refresh();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load BuildCraft world configuration", e);
        }
    }
    public static void stop(MinecraftServer stopped) {
        if (server != stopped) return;
        server = null;
        buildcraft.energy.OilIndustry.setServerRunning(false);
        ConfigurationRegistry.INSTANCE.closeWorld();
        refresh();
    }
    private static void refresh() {
        ConfigBinding.refreshServerValues();
        BCBuildersConfig.reloadConfig("buildcraftbuilders");
    }
    public static boolean canEdit(ServerPlayer player) {
        if (server == null || !ConfigurationRegistry.INSTANCE.hasWorld()) return false;
        if (player.hasPermissions(2)) return true;
        var owner = server.getSingleplayerProfile();
//? if >=1.21.11 {
        return owner != null && player.getUUID().equals(owner.id());
//?} else {
        return owner != null && player.getUUID().equals(owner.getId());
//?}
    }
}
