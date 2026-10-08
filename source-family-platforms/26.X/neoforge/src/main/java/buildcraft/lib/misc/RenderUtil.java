//? source if >=26.2
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import java.util.List;

import javax.annotation.Nullable;


import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import buildcraft.lib.compat.mc2612.client.color.item.ItemColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import buildcraft.lib.compat.RenderCompat;

public class RenderUtil {

    public static void registerBlockColour(@Nullable Block block, BlockTintSource colour) {
        if (block != null) {
            Minecraft.getInstance().getBlockColors().register(List.of(colour), block);
        }
    }

    public static void registerItemColour(@Nullable Item item, ItemColor colour) {
        if (item != null) {
            
        }
    }

    /** Takes _RGB (alpha is set to 1) */
    public static void setGLColorFromInt(int color) {
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;

        RenderCompat.setShaderColor(red, green, blue, 1.0f);
    }

    /** Takes ARGB */
    public static void setGLColorFromIntPlusAlpha(int color) {
        float alpha = (color >>> 24 & 255) / 255.0F;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;

        RenderCompat.setShaderColor(red, green, blue, alpha);
    }

    public static int swapARGBforRGBA(int argb) {
        int a = (argb >>> 24) & 255;
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = (argb >> 0) & 255;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

}
