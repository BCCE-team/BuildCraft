/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui;

import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.client.gui.GuiGraphicsExtractor;

@FunctionalInterface
public interface ISimpleDrawable {
    void drawAt(GuiGraphicsExtractor guiGraphics, double x, double y);

    default void drawAt(GuiGraphicsExtractor guiGraphics, IGuiPosition element) {
        drawAt(guiGraphics, element.getX(), element.getY());
    }

    default ISimpleDrawable andThen(ISimpleDrawable after) {
        ISimpleDrawable current = this;
        return (guiGraphics, x, y) -> {
            current.drawAt(guiGraphics, x, y);
            after.drawAt(guiGraphics, x, y);
        };
    }
}
