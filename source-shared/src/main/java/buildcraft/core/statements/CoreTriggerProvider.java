/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.statements;

import java.util.Collection;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.ITriggerExternal;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.lib.internal.statement.ITriggerInternalSided;
import buildcraft.lib.internal.statement.ITriggerProvider;
import buildcraft.lib.internal.statement.containers.IRedstoneStatementContainer;
import buildcraft.core.BCCoreStatements;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.platform.capability.PlatformCapabilities;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

public enum CoreTriggerProvider implements ITriggerProvider {
    INSTANCE;

    @Override
    public void addInternalTriggers(Collection<ITriggerInternal> res, IStatementContainer container) {
        res.add(BCCoreStatements.TRIGGER_TRUE);
        if (container instanceof IRedstoneStatementContainer) {
            res.add(BCCoreStatements.TRIGGER_REDSTONE_ACTIVE);
            res.add(BCCoreStatements.TRIGGER_REDSTONE_INACTIVE);
        }

        if (TriggerPower.isTriggeringTile(container.getTile())) {
            res.add(BCCoreStatements.TRIGGER_POWER_HIGH);
            res.add(BCCoreStatements.TRIGGER_POWER_LOW);
        }
    }

    @Override
    public void addInternalSidedTriggers(Collection<ITriggerInternalSided> res, IStatementContainer container,
        @Nonnull Direction side) {}

    @Override
    public void addExternalTriggers(Collection<ITriggerExternal> res, @Nonnull Direction side, BlockEntity tile) {

        if (TriggerPower.isTriggeringTile(tile, side.getOpposite())) {
            res.add(BCCoreStatements.TRIGGER_POWER_HIGH);
            res.add(BCCoreStatements.TRIGGER_POWER_LOW);
        }

        boolean blockInventoryTriggers = false;
        boolean blockFluidHandlerTriggers = false;

        if (tile instanceof IBlockDefaultTriggers) {
            blockInventoryTriggers = ((IBlockDefaultTriggers) tile).blockInventoryTriggers(side);
            blockFluidHandlerTriggers = ((IBlockDefaultTriggers) tile).blockFluidHandlerTriggers(side);
        }

        if (!blockInventoryTriggers) {
            ItemStorage itemHandler = PlatformStorage.items(
                tile.getLevel(), tile.getBlockPos(), side.getOpposite());
            if (itemHandler != null) {
                res.add(BCCoreStatements.TRIGGER_INVENTORY_EMPTY);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_SPACE);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_CONTAINS);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_FULL);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_BELOW_25);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_BELOW_50);
                res.add(BCCoreStatements.TRIGGER_INVENTORY_BELOW_75);
            }
        }

        if (!blockFluidHandlerTriggers) {
            FluidStorage<?> fluidHandler = PlatformStorage.fluids(
                tile.getLevel(), tile.getBlockPos(), side.getOpposite());
            if (fluidHandler != null) {

                int liquids = fluidHandler.getTanks();
                if (/*fluidHandler. != null && */liquids > 0) {
                    res.add(BCCoreStatements.TRIGGER_FLUID_EMPTY);
                    res.add(BCCoreStatements.TRIGGER_FLUID_SPACE);
                    res.add(BCCoreStatements.TRIGGER_FLUID_CONTAINS);
                    res.add(BCCoreStatements.TRIGGER_FLUID_FULL);
                    res.add(BCCoreStatements.TRIGGER_FLUID_BELOW_25);
                    res.add(BCCoreStatements.TRIGGER_FLUID_BELOW_50);
                    res.add(BCCoreStatements.TRIGGER_FLUID_BELOW_75);
                }
            }
        }

        if (PlatformCapabilities.hasWork(tile, null) != null) {
            res.add(BCCoreStatements.TRIGGER_MACHINE_ACTIVE);
            res.add(BCCoreStatements.TRIGGER_MACHINE_INACTIVE);
        }

        if (TriggerEnginePowerStage.isTriggeringTile(tile)) {
            res.add(BCCoreStatements.TRIGGER_POWER_BLUE);
            res.add(BCCoreStatements.TRIGGER_POWER_GREEN);
            res.add(BCCoreStatements.TRIGGER_POWER_YELLOW);
            res.add(BCCoreStatements.TRIGGER_POWER_RED);
            res.add(BCCoreStatements.TRIGGER_POWER_OVERHEAT);
        }
    }
}
