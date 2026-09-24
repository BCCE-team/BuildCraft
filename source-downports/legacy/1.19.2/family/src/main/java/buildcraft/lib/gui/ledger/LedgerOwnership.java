/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.ledger;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;

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

    public LedgerOwnership(BuildCraftGui gui, TileBC_Neptune tile, boolean expandPositive) {
        super(gui, 0xFF_E0_F0_FF, expandPositive);
        this.title = Component.translatable("gui.ledger.ownership");
        this.tile = tile;

        appendText(this::getOwnerComponent, 0);

        calculateMaxSize();
        setOpenProperty(GuiConfigManager.getOrAddBoolean(new ResourceLocation("buildcraftlib:base"),
            "ledger.owner.is_open", false));
    }

    @Override
    protected void drawIcon(PoseStack pose, double x, double y) {
        ISprite sprite = SpriteUtil.getFaceSprite(tile.getOwner());
        GuiIcon.draw(pose, sprite, x, y, x + 16, y + 16);
        sprite = SpriteUtil.getFaceOverlaySprite(tile.getOwner());
        if (sprite != null) {
            GuiIcon.draw(pose, sprite, x - 0.5, y - 0.5, x + 17, y + 17);
        }
    }

    private Component getOwnerComponent() {
        GameProfile owner = tile.getOwner();
        if (owner != null && FakePlayerProvider.NULL_PROFILE.getId().equals(owner.getId())) {
            return Component.translatable("gui.ledger.ownership.unknown");
        }
        String name = owner == null ? null : owner.getName();
        if (name == null || name.isBlank()) {
            return Component.translatable("gui.ledger.ownership.none");
        }
        return Component.literal(name);
    }
}
