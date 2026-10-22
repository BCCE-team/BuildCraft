/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.statements;

import java.util.Collection;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.statement.IActionExternal;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IActionInternalSided;
import buildcraft.lib.internal.statement.IActionProvider;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.containers.IRedstoneStatementContainer;
import buildcraft.lib.internal.tiles.IControllable;
import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.core.BCCoreStatements;
import buildcraft.lib.misc.CapUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

public enum CoreActionProvider implements IActionProvider {
    INSTANCE;

    @Override
    public void addInternalActions(Collection<IActionInternal> res, IStatementContainer container) {
        if (container instanceof IRedstoneStatementContainer) {
            res.add(BCCoreStatements.ACTION_REDSTONE);
        }
    }

    @Override
    public void addInternalSidedActions(Collection<IActionInternalSided> actions, IStatementContainer container, @Nonnull Direction side) { }

    @Override
    public void addExternalActions(Collection<IActionExternal> res, @Nonnull Direction side, BlockEntity tile) {
        IControllable controllable = CapUtil.getCapability(tile.getLevel(), tile.getBlockPos(), TilesAPI.CAP_CONTROLLABLE, side.getOpposite());
        if (controllable != null) {
            for (ActionMachineControl action : BCCoreStatements.ACTION_MACHINE_CONTROL) {
                if (controllable.acceptsControlMode(action.mode)) {
                    res.add(action);
                }
            }
        }
    }
}
