/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.statements;

import java.util.Locale;

import buildcraft.lib.internal.statement.IActionExternal;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.tiles.IControllable;
import buildcraft.lib.internal.tiles.IControllable.Mode;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCoreSprites;
import buildcraft.core.BCCoreStatements;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ActionMachineControl extends BCStatement implements IActionExternal {
    public final Mode mode;

    public ActionMachineControl(Mode mode) {
        super(
            "buildcraft:machine." + mode.name().toLowerCase(Locale.ROOT),
            "buildcraft.machine." + mode.name().toLowerCase(Locale.ROOT)
        );
        this.mode = mode;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.machine." + mode.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void actionActivate(BlockEntity target, Direction side, IStatementContainer source, IStatementParameter[] parameters) {
        IControllable controllable = CapUtil.getCapability(target.getLevel(), target.getBlockPos(), TilesAPI.CAP_CONTROLLABLE, side.getOpposite());
        if (controllable != null && controllable.acceptsControlMode(mode)) {
            controllable.setControlMode(mode);
        }
    }

    @Override
    public SpriteHolder getSprite() {
        return BCCoreSprites.ACTION_MACHINE_CONTROL.get(mode);
    }

    @Override
    public IStatement[] getPossible() {
        return BCCoreStatements.ACTION_MACHINE_CONTROL;
    }
}
