package buildcraft.lib.client.config;

import buildcraft.lib.client.gui.ClientGuiModeNavigation;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Legacy entry point; the old TOML editor no longer owns settings. */
@Deprecated
public final class LoggingSettingsScreen extends Screen {
    private final Screen parent;
    public LoggingSettingsScreen(Screen parent) { super(Component.translatable("gui.buildcraft.configuration.title")); this.parent=parent; }
    @Override protected void init() { ClientGuiModeNavigation.show(new BuildCraftConfigurationScreen(parent)); }
}
