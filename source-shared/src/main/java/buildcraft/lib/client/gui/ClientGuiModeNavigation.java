/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.lib.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Screen switching shared by Forge, NeoForge, Fabric and old Minecraft targets. */
public final class ClientGuiModeNavigation {
    private ClientGuiModeNavigation() { }

    public static void show(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
//? if >=26.2 {
        mc.gui.setScreen(screen);
//?} else {
        mc.setScreen(screen);
//?}
    }
}
