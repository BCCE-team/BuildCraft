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
import buildcraft.transport.pipe.behaviour.PipeBehaviourEmzuli.SlotIndex;
import net.minecraft.network.chat.Component;

public class ActionExtractionPreset extends BCStatement implements IActionInternal {

    public final SlotIndex index;

    public ActionExtractionPreset(SlotIndex index) {
        super("buildcraft:extraction.preset." + index.colour.getName(), "buildcraft.extraction.preset." + index.colour.getName());

        this.index = index;
    }

    @Override
    public Component getDescription() {
    	return Component.translatable("gate.action.extraction", LocaleUtil.localizeColourComponent(index.colour));
    }

    @Override
    public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
        // The pipe handles this
    }

    @Override
    public IStatement[] getPossible() {
        return BCTransportStatements.ACTION_EXTRACTION_PRESET;
    }

    @Override
    public SpriteHolder getSprite() {
        return BCTransportSprites.ACTION_EXTRACTION_PRESET.get(index);
    }
}
