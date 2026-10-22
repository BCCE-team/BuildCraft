/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.help;

import java.util.List;

import buildcraft.lib.expression.api.IExpressionNode.INodeBoolean;
import buildcraft.lib.expression.node.value.NodeConstantBoolean;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.pos.IGuiArea;

/** A simple, non-drawing, gui element that holds an {@link ElementHelpInfo}. */
public class DummyHelpElement implements IGuiElement {
    public final IGuiArea area;
    public final ElementHelpInfo help;
    private final INodeBoolean visible;

    public DummyHelpElement(IGuiArea area, ElementHelpInfo help) {
        this(area, help, NodeConstantBoolean.TRUE);
    }

    public DummyHelpElement(IGuiArea area, ElementHelpInfo help, INodeBoolean visible) {
        this.area = area;
        this.help = help;
        this.visible = visible;
    }

    public double getX() {
        return area.getX();
    }

    public double getY() {
        return area.getY();
    }

    public double getWidth() {
        return area.getWidth();
    }

    public double getHeight() {
        return area.getHeight();
    }

    public void addHelpElements(List<HelpPosition> elements) {
        if (visible.evaluate()) {
            elements.add(help.target(area));
        }
    }
}
