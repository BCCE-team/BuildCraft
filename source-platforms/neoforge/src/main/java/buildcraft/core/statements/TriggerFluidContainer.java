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
import net.neoforged.neoforge.fluids.FluidUtil;

public class TriggerFluidContainer extends BCStatement implements ITriggerExternal {
    public State state;

    public TriggerFluidContainer(State state) {
        super(
            "buildcraft:fluid." + state.name().toLowerCase(Locale.ROOT),
            "buildcraft.fluid." + state.name().toLowerCase(Locale.ROOT)
        );
        this.state = state;
    }

    @Override
    public SpriteHolder getSprite() {
        return BCCoreSprites.TRIGGER_FLUID.get(state);
    }

    @Override
    public int maxParameters() {
        return state == State.CONTAINS || state == State.SPACE ? 1 : 0;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.fluid." + state.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean isTriggerActive(BlockEntity tile, Direction side, IStatementContainer statementContainer, IStatementParameter[] parameters) {
        FluidStorage<FluidStack> handler = PlatformStorage.fluids(
            tile.getLevel(), tile.getBlockPos(), side.getOpposite());

        if (handler != null) {
            FluidStack searchedFluid = FluidStack.EMPTY;

            if (parameters != null && parameters.length >= 1 && parameters[0] != null && !parameters[0].getItemStack().isEmpty()) {
                searchedFluid = FluidUtil.getFluidContained(parameters[0].getItemStack()).orElse(searchedFluid);
            }

            if (!searchedFluid.isEmpty()) {
                searchedFluid.setAmount(1);
            }

            int liquids = handler.getTanks();
            if (liquids == 0) {
                return false;
            }

            switch (state) {
                case EMPTY:
                    FluidStack drained = handler.drain(1, true);
                    return drained.isEmpty() || drained.getAmount() <= 0;
                case CONTAINS:
                    for (int i = 0; i < liquids ; i++) {
                        FluidStack fluid = handler.getFluidInTank(i);
                        if (!fluid.isEmpty() && (searchedFluid.isEmpty()|| FluidCompatRegistry.areEquivalent(searchedFluid, fluid))) {
                            return true;
                        }
                    }
                    return false;
                case SPACE:
                    if (searchedFluid.isEmpty()) {
                        for (int i = 0; i < liquids ; i++) {
                            FluidStack fluid = handler.getFluidInTank(i);
                            if ((fluid.isEmpty() || fluid.getAmount() < handler.getTankCapacity(i))) {
                                return true;
                            }
                        }
                        return false;
                    }
                    return handler.fill(searchedFluid, true) > 0;
                case FULL:
                    if (searchedFluid.isEmpty()) {
                        for (int i = 0; i < liquids ; i++) {
                            FluidStack fluid = handler.getFluidInTank(1);
                            if ((fluid.isEmpty() || fluid.getAmount() < handler.getTankCapacity(i))) {
                                return false;
                            }
                        }
                        return true;
                    }
                    return handler.fill(searchedFluid, true) <= 0;
            }
        }

        return false;
    }

    @Override
    public IStatementParameter createParameter(int index) {
        return new StatementParameterItemStack();
    }

    @Override
    public IStatement[] getPossible() {
        return BCCoreStatements.TRIGGER_FLUID_ALL;
    }

    public enum State {
        EMPTY,
        CONTAINS,
        SPACE,
        FULL;

        public static final State[] VALUES = values();
    }
}
