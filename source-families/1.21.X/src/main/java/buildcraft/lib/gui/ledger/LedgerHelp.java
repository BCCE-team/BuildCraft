//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.ledger;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.BCLibSprites;
import buildcraft.lib.client.sprite.SpriteNineSliced;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.config.GuiConfigManager;
import buildcraft.lib.gui.elem.GuiElementContainerHelp;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.misc.GuiUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class LedgerHelp extends Ledger_Neptune {

    private static final SpriteNineSliced[][] SPRITE_HELP_SPLIT = new SpriteNineSliced[2][2];

    static {
        SPRITE_HELP_SPLIT[0][0] =
            GuiUtil.slice(GuiUtil.subRelative(BCLibSprites.HELP_SPLIT, 0, 0, 8, 8, 16), 2, 2, 6, 6, 8);
        SPRITE_HELP_SPLIT[0][1] =
            GuiUtil.slice(GuiUtil.subRelative(BCLibSprites.HELP_SPLIT, 0, 8, 8, 8, 16), 2, 2, 6, 6, 8);
        SPRITE_HELP_SPLIT[1][0] =
            GuiUtil.slice(GuiUtil.subRelative(BCLibSprites.HELP_SPLIT, 8, 0, 8, 8, 16), 2, 2, 6, 6, 8);
        SPRITE_HELP_SPLIT[1][1] =
            GuiUtil.slice(GuiUtil.subRelative(BCLibSprites.HELP_SPLIT, 8, 8, 8, 8, 16), 2, 2, 6, 6, 8);
    }

    private HelpPosition selected = null;
    private boolean foundAny = false;

    public LedgerHelp(BuildCraftGui gui, boolean expandPositive) {
        super(gui, 0xFF_CC_99_FF, expandPositive);
        title = Component.translatable("gui.ledger.help");
        showHint();
        calculateMaxSize();

        Identifier id = Identifier.parse("buildcraftlib:base");
        setOpenProperty(GuiConfigManager.getOrAddBoolean(id, "ledger.help.is_open", false));
    }

    @Override
    public void tick() {
        super.tick();
        List<HelpPosition> positions = collectHelpPositions();
        foundAny = !positions.isEmpty();

        if (currentWidth == CLOSED_WIDTH && currentHeight == CLOSED_HEIGHT) {
            clearSelected();
            return;
        }

        if (selected != null) {
            HelpPosition replacement = positions.stream().filter(this::sameTargetAsSelected).findFirst().orElse(null);
            if (replacement == null) {
                clearSelected();
            } else if (!sameContent(replacement, selected)) {
                setSelected(replacement);
            } else {
                // Keep the freshest HelpPosition instance so dynamic providers can replace their object safely.
                selected = replacement;
            }
        }
    }

    @Override
    protected void drawIcon(GuiGraphics guiGraphics, double x, double y) {
        foundAny = !collectHelpPositions().isEmpty();
        ISprite sprite = foundAny ? BCLibSprites.HELP : BCLibSprites.WARNING_MINOR;
        GuiIcon.draw(guiGraphics, sprite, x, y, x + 16, y + 16);
    }

    @Override
    public void drawForeground(GuiGraphics guiGraphics, float partialTicks) {
        super.drawForeground(guiGraphics, partialTicks);
        if (!shouldDrawOpen()) {
            return;
        }

        List<HelpPosition> positions = collectHelpPositions();
        foundAny = !positions.isEmpty();

        HelpPosition hovered = null;
        for (HelpPosition info : positions) {
            if (info.target.contains(gui.mouse)) {
                hovered = info;
                break;
            }
        }
        if (hovered != null && (!sameTarget(hovered, selected) || !sameContent(hovered, selected))) {
            setSelected(hovered);
        }

        for (HelpPosition info : positions) {
            IGuiArea rect = info.target;
            boolean isHovered = rect.contains(gui.mouse);
            boolean isSelected = sameTarget(info, selected);
            SpriteNineSliced split = SPRITE_HELP_SPLIT[isHovered ? 1 : 0][isSelected ? 1 : 0];
            split.draw(guiGraphics, rect, 0xFF000000 | (info.info.colour & 0x00FFFFFF));
        }

    }

    private List<HelpPosition> collectHelpPositions() {
        List<HelpPosition> positions = new ArrayList<>();
        for (IGuiElement element : gui.shownElements) {
            if (element != this) {
                element.addHelpElements(positions);
            }
        }
        return positions;
    }

    private void setSelected(HelpPosition info) {
        selected = info;
        GuiElementContainerHelp container = new GuiElementContainerHelp(gui, positionLedgerInnerStart);
        info.info.addGuiElements(container);
        removeSelectedContainer();
        openElements.add(container);
        title = Component.translatable("gui.ledger.help.selected", info.info.getLocalizedTitle());
        calculateMaxSize();
    }

    private void clearSelected() {
        if (selected == null) {
            return;
        }
        selected = null;
        removeSelectedContainer();
        title = Component.translatable("gui.ledger.help");
        showHint();
        calculateMaxSize();
    }

    private void showHint() {
        GuiElementContainerHelp container = new GuiElementContainerHelp(gui, positionLedgerInnerStart);
        buildcraft.lib.gui.help.ElementHelpInfo hint = new buildcraft.lib.gui.help.ElementHelpInfo(
            "gui.ledger.help", 0xFFCC99FF, "gui.ledger.help.hint");
        hint.addGuiElements(container);
        openElements.add(container);
    }

    private void removeSelectedContainer() {
        while (openElements.size() > 1) {
            openElements.remove(openElements.size() - 1);
        }
    }

    private boolean sameTargetAsSelected(HelpPosition other) {
        return sameTarget(other, selected);
    }

    private static boolean sameTarget(HelpPosition a, HelpPosition b) {
        return a != null && b != null &&
            a.info.title.equals(b.info.title) &&
            a.target.getX() == b.target.getX() &&
            a.target.getY() == b.target.getY() &&
            a.target.getWidth() == b.target.getWidth() &&
            a.target.getHeight() == b.target.getHeight();
    }

    private static boolean sameContent(HelpPosition a, HelpPosition b) {
        return sameTarget(a, b) && a.info.contentSignature().equals(b.info.contentSignature());
    }
}
