/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.ledger;

import net.minecraft.client.gui.GuiGraphics;

import com.mojang.authlib.GameProfile;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.config.GuiConfigManager;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class LedgerOwnership extends Ledger_Neptune {

    private final TileBC_Neptune tile;
    private String lastOwnerText;

    public LedgerOwnership(BuildCraftGui gui, TileBC_Neptune tile, boolean expandPositive) {
        super(gui, 0xFF_E0_F0_FF, expandPositive);
        this.title = Component.translatable("gui.ledger.ownership");
        this.tile = tile;

        appendText(this::getOwnerComponent, 0);

        calculateMaxSize();
        setOpenProperty(GuiConfigManager.getOrAddBoolean(ResourceLocation.parse("buildcraftlib:base"),
            "ledger.owner.is_open", false));
    }

    @Override
    public void tick() {
        super.tick();
        String text = getOwnerComponent().getString();
        if (!text.equals(lastOwnerText)) {
            lastOwnerText = text;
            calculateMaxSize();
        }
    }

    @Override
    protected void drawIcon(GuiGraphics guiGraphics, double x, double y) {
        ISprite sprite = SpriteUtil.getFaceSprite(tile.getKnownOwner());
        GuiIcon.draw(guiGraphics, sprite, x, y, x + 16, y + 16);
        sprite = SpriteUtil.getFaceOverlaySprite(tile.getKnownOwner());
        if (sprite != null) {
            GuiIcon.draw(guiGraphics, sprite, x - 0.5, y - 0.5, x + 17, y + 17);
        }
    }

    private Component getOwnerComponent() {
        GameProfile owner = tile.getKnownOwner();
        if (owner != null && FakePlayerProvider.NULL_PROFILE.getId().equals(owner.getId())) {
            return Component.translatable("gui.ledger.ownership.unknown");
        }
        if (owner == null) {
            return Component.translatable("gui.ledger.ownership.none");
        }
        String name = owner.getName();
        if (name != null && !name.isBlank()) {
            return Component.literal(name);
        }
        return owner.getId() == null
            ? Component.translatable("gui.ledger.ownership.unknown")
            : Component.literal(owner.getId().toString());
    }
}
