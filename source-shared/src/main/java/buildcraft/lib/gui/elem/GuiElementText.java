/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.elem;

import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.expression.node.value.NodeConstantDouble;
import buildcraft.lib.expression.node.value.NodeConstantObject;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

public class GuiElementText extends GuiElementSimple {
    public boolean dropShadow = false;
    public boolean foreground = false;
    public boolean centered = false;

    private final Supplier<Component> text;
    private final IntSupplier colour;
    private final DoubleSupplier scale;

    public GuiElementText(BuildCraftGui gui, IGuiPosition parent, Supplier<Component> text, IntSupplier colour) {
        this(gui, parent, text, colour, NodeConstantDouble.ONE);
    }
    
    public static GuiElementText creat(BuildCraftGui gui, IGuiPosition parent, Supplier<String> text, IntSupplier colour) {
        return new GuiElementText(gui, parent, () -> Component.literal(text.get()), colour, NodeConstantDouble.ONE);
    }

    public GuiElementText(BuildCraftGui gui, IGuiPosition parent, Supplier<Component> text, IntSupplier colour,
        DoubleSupplier scale) {
        super(gui, GuiRectangle.ZERO.offset(parent));
        this.text = text;
        this.colour = colour;
        this.scale = scale;
    }

    public GuiElementText(BuildCraftGui gui, IGuiPosition parent, Supplier<Component> text, int colour) {
        this(gui, parent, text, () -> colour);
    }

    public GuiElementText(BuildCraftGui gui, IGuiPosition parent, Component text, int colour) {
        this(gui, parent, new NodeConstantObject<>(Component.class, text), () -> colour);
    }

    public GuiElementText setDropShadow(boolean value) {
        dropShadow = value;
        return this;
    }

    public GuiElementText setForeground(boolean value) {
        foreground = value;
        return this;
    }

    public GuiElementText setCentered(boolean centered) {
        this.centered = centered;
        return this;
    }

    @Override
    public double getWidth() {
        Minecraft mc = Minecraft.getInstance();
		Font fr = mc.font;
        return fr.width(text.get());
    }

    @Override
    public double getHeight() {
        Minecraft mc = Minecraft.getInstance();
		Font fr = mc.font;
        return fr.lineHeight;
    }

    @Override
    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
        PoseStack pose = guiGraphics.pose();
        if (!foreground) {
            draw(guiGraphics);
        }
    }

    @Override
    public void drawForeground(GuiGraphics guiGraphics, float partialTicks) {
        PoseStack pose = guiGraphics.pose();
        if (foreground) {
            draw(guiGraphics);
        }
    }

    private void draw(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        Component content = text.get();
        double valueScale = scale.getAsDouble();
        int colourValue = colour.getAsInt();
        int textWidth = mc.font.width(content);
        double x = getX() - (centered ? textWidth * valueScale / 2.0 : 0);
        double y = getY();
        if (valueScale != 1.0) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().scale((float) valueScale, (float) valueScale, 1.0F);
            guiGraphics.drawString(mc.font, content, (int) (x / valueScale), (int) (y / valueScale), opaqueColour(colourValue), dropShadow);
            guiGraphics.pose().popPose();
        } else {
            guiGraphics.drawString(mc.font, content, (int) x, (int) y, opaqueColour(colourValue), dropShadow);
        }
    }

    private static int opaqueColour(int colour) {
        return (colour & 0xFF000000) == 0 ? (colour | 0xFF000000) : colour;
    }

    @Override
    public String getDebugInfo(List<String> info) {
        info.add("text = " + text);
        return super.getDebugInfo(info);
    }
}
