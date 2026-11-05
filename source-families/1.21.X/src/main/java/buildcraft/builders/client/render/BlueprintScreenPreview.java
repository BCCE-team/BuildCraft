//? source if >=1.21.10
/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.client.render;

import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.ClientSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Extra blueprint viewport, placed outside the GUI's slots so it never hides controls. */
public final class BlueprintScreenPreview {
    private static final int VIEW = 100;
    private BlueprintScreenPreview() {}

    //? if >=26.1 {
    public static void draw(GuiGraphicsExtractor graphics, ItemStack stack, int left, int top,
    //?} else {
    /*public static void draw(GuiGraphics graphics, ItemStack stack, int left, int top,*/
    //?}
                            int guiWidth, int screenWidth) {
        draw(graphics, stack, null, left, top, guiWidth, screenWidth);
    }

    //? if >=26.1 {
    public static void draw(GuiGraphicsExtractor graphics, ItemStack stack, Snapshot liveFallback,
    //?} else {
    /*public static void draw(GuiGraphics graphics, ItemStack stack, Snapshot liveFallback,*/
    //?}
                            int left, int top, int guiWidth, int screenWidth) {
        if (graphics == null) return;
        Snapshot snapshot = null;
        if (!stack.isEmpty() && stack.getItem() instanceof ItemSnapshot) {
            Snapshot.Header header = ItemSnapshot.getHeader(stack);
            if (header != null) snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);
        }
        if (snapshot == null) snapshot = liveFallback;
        if (snapshot == null || snapshot.size == null) return;
        int right = screenWidth - (left + guiWidth);
        int x;
        if (right >= VIEW + 14) {
            x = left + guiWidth + 9;
        } else if (left >= VIEW + 14) {
            x = left - VIEW - 9;
        } else {
            return; // Tooltip preview remains available at small GUI scales.
        }
        int y = top + 8;
        graphics.fill(x - 3, y - 3, x + VIEW + 3, y + VIEW + 16, 0xDC101720);
        BlueprintRenderer.renderSnapshot(graphics, snapshot, x, y, VIEW, VIEW);
        graphics.drawString(Minecraft.getInstance().font,
            Component.translatable("buildcraft.builders.blueprint.preview"), x + 3,
            y + VIEW + 3, 0xFFE4E7ED);
    }
}
