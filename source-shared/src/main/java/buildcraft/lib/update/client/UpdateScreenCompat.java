package buildcraft.lib.update.client;

import java.net.URI;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Isolates client screen navigation and external-link APIs across Minecraft generations. */
public final class UpdateScreenCompat {
    private UpdateScreenCompat() {}

    public static Screen currentScreen() {
        Minecraft minecraft = Minecraft.getInstance();
//? if >=26.2 {
        return minecraft.gui.screen();
//?} else {
        return minecraft.screen;
//?}
    }

    public static void setScreen(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
//? if >=26.2 {
        minecraft.gui.setScreen(screen);
//?} else {
        minecraft.setScreen(screen);
//?}
    }

    public static void openUri(URI uri) {
//? if >=26.3 {
        com.mojang.blaze3d.Blaze3D.openUri(uri);
//?} else if >=1.21.11 {
        net.minecraft.util.Util.getPlatform().openUri(uri);
//?} else {
        net.minecraft.Util.getPlatform().openUri(uri);
//?}
    }
}
