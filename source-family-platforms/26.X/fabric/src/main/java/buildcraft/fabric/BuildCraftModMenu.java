package buildcraft.fabric;

import buildcraft.lib.client.config.BuildCraftConfigurationScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional integration: only Mod Menu invokes this entrypoint. */
public final class BuildCraftModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BuildCraftConfigurationScreen::new;
    }
}
