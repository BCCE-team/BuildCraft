package buildcraft.lib.client.config;

import buildcraft.lib.client.gui.ClientGuiModeNavigation;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Fabric registers the category through the native Minecraft category registry. */
@Environment(EnvType.CLIENT)
public final class ConfigurationKey {
    private ConfigurationKey() {}

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("buildcraft", "controls"));
    public static final KeyMapping OPEN = new KeyMapping("key.buildcraft.open_configuration", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    public static void tick() {
        Minecraft client=Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if(client.level!=null && ClientGuiModeNavigation.currentScreen()==null && !OPEN.isUnbound()) {
                ClientGuiModeNavigation.show(new BuildCraftConfigurationScreen(null));
            }
        }
    }
}
