/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.compat.RenderCompat;

public class GuiStack implements ISimpleDrawable {
    private final ItemStack stack;

    public GuiStack(ItemStack stack) {
        this.stack = stack;
    }

    public void drawAt(GuiGraphicsExtractor guiGraphics, double x, double y) {
        RenderCompat.setShaderColor(1, 1, 1, 1);
        guiGraphics.renderItem(stack, (int) x, (int) y);
        guiGraphics.itemDecorations(Minecraft.getInstance().font, stack, (int) x, (int) y);
        RenderCompat.setShaderColor(1, 1, 1, 1);
    }
}
