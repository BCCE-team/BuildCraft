package buildcraft.lib.client.config;

import buildcraft.lib.client.gui.ClientGuiModeNavigation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else if >=1.21.9 {
import net.minecraft.resources.ResourceLocation;
//?}

/** Client only. The key is unbound until the player assigns it in Controls. */
public final class ConfigurationKey {
    private ConfigurationKey() {}
//? if fabric {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("buildcraft", "controls"));
//?} else if >=1.21.11 {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("buildcraft", "controls"));
//?} else if >=1.21.9 {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath("buildcraft", "controls"));
//?} else {
    public static final String CATEGORY = "key.categories.buildcraft";
//?}
//? if >=26.3 {
    public static final KeyMapping OPEN = new KeyMapping("key.buildcraft.open_configuration", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY);
//?} else {
    public static final KeyMapping OPEN = new KeyMapping("key.buildcraft.open_configuration", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
//?}

    public static void tick() {
        Minecraft client=Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if(client.level!=null && ClientGuiModeNavigation.currentScreen()==null && !OPEN.isUnbound()) {
                ClientGuiModeNavigation.show(new BuildCraftConfigurationScreen(null));
            }
        }
    }
}
