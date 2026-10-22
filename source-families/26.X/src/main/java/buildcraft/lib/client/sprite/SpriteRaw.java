/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.sprite;

import com.mojang.blaze3d.systems.RenderSystem;

import buildcraft.lib.internal.core.render.ISprite;

import net.minecraft.resources.Identifier;
import buildcraft.lib.compat.RenderCompat;

/** Defines a sprite that is taken directly from the given resource location. */
public class SpriteRaw implements ISprite {
    public final Identifier location;
    public final double uMin, vMin, width, height;

    public SpriteRaw(Identifier location, double xMin, double yMin, double width, double height, double textureSize) {
        this.location = location;
        this.uMin = xMin / textureSize;
        this.vMin = yMin / textureSize;
        this.width = width / textureSize;
        this.height = height / textureSize;
    }

    public SpriteRaw(Identifier location, double xMin, double yMin, double width, double height) {
        this.location = location;
        this.uMin = xMin;
        this.vMin = yMin;
        this.width = width;
        this.height = height;
    }

    public void bindTexture() {
            RenderCompat.setShaderTexture(0, location);
    }

    public float getInterpU(double u) {
        return (float)(uMin + u * width);
    }

    public float getInterpV(double v) {
        return (float)(vMin + v * height);
    }
}
