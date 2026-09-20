/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.statements;

import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.BCTransportStatements;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

public class ActionPipeColor extends BCStatement implements IActionInternal {

    public final DyeColor color;

    public ActionPipeColor(DyeColor color) {
        super("buildcraft:pipe.color." + color.getName(), "buildcraft.pipe." + color.getName());
        this.color = color;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.pipe.item.color", LocaleUtil.localizeColourComponent(color));
    }

    @Override
    public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
        // Pipes listen for this -- we don't need to do anything here
    }

    @Override
    public IStatement[] getPossible() {
        return BCTransportStatements.ACTION_PIPE_COLOUR;
    }

    @Override
    public SpriteHolder getSprite() {
        return BCTransportSprites.ACTION_PIPE_COLOUR[color.ordinal()];
    }
}
