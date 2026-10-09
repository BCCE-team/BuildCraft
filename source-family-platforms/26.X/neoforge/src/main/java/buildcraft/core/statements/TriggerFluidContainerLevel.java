/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.statements;

import java.util.Locale;

import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.internal.statement.ITriggerExternal;
import buildcraft.lib.internal.statement.StatementParameterItemStack;
import buildcraft.core.BCCoreSprites;
import buildcraft.compat.CompatCapTransfromer;
import buildcraft.core.BCCoreStatements;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.PlatformStorage;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.fluid.FuelApiBridge;

public class TriggerFluidContainerLevel extends BCStatement implements ITriggerExternal {
    public final TriggerType type;

    public TriggerFluidContainerLevel(TriggerType type) {
        super(
            "buildcraft:fluid." + type.name().toLowerCase(Locale.ROOT),
            "buildcraft.fluid." + type.name().toLowerCase(Locale.ROOT)
        );
        this.type = type;
    }

    public SpriteHolder getSprite() {
        return BCCoreSprites.TRIGGER_FLUID_LEVEL.get(type);
    }

    public int maxParameters() {
        return 1;
    }

    public Component getDescription() {
        return Component.translatable("gate.trigger.fluidlevel.below", (int) (type.level * 100));
    }

    public boolean isTriggerActive(BlockEntity tile, Direction side, IStatementContainer statementContainer, IStatementParameter[] parameters) {
        FluidStorage<FluidStack> handler = PlatformStorage.fluids(
            tile.getLevel(), tile.getBlockPos(), side.getOpposite());
        if (handler == null) {
            return false;
        }
        FluidStack searchedFluid = FluidStack.EMPTY;

        if (parameters != null && parameters.length >= 1 && parameters[0] != null && !parameters[0].getItemStack() .isEmpty()) {
            searchedFluid = FuelApiBridge.stackOf(BuildCraftApi.service(BuildCraftServices.FLUID_ITEMS).fluid(parameters[0].getItemStack()));
            if (!searchedFluid.isEmpty()) {
                searchedFluid.setAmount(1);
            }
        }

        int tanks = handler.getTanks();
        if (tanks == 0) {
            return false;
        }

        for (int i = 0; i < tanks ; i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            if (fluid.isEmpty()) {
                return searchedFluid.isEmpty() || handler.fill(searchedFluid, true) > 0;
            }

            if (searchedFluid.isEmpty() || FluidCompatRegistry.areEquivalent(searchedFluid, fluid)) {
                float percentage = fluid.getAmount() / (float) handler.getTankCapacity(i);
                return percentage < type.level;
            }
        }
        return false;
    }

    public IStatementParameter createParameter(int index) {
        return new StatementParameterItemStack();
    }

    public IStatement[] getPossible() {
        return BCCoreStatements.TRIGGER_FLUID_ALL;
    }

    public enum TriggerType {
        BELOW25(0.25F),
        BELOW50(0.5F),
        BELOW75(0.75F);

        TriggerType(float level) {
            this.level = level;
        }

        public static final TriggerType[] VALUES = values();

        public final float level;
    }
}
