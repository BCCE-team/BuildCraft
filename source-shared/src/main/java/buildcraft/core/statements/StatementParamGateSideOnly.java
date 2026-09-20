/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.statements;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementMouseClick;
import buildcraft.core.BCCoreSprites;
import buildcraft.lib.misc.StackUtil;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public enum StatementParamGateSideOnly implements IStatementParameter {
    ANY(false),
    SPECIFIC(true);

    public final boolean isSpecific;

    private static final StatementParamGateSideOnly[] POSSIBLE_ANY = { ANY, SPECIFIC };
    private static final StatementParamGateSideOnly[] POSSIBLE_SPECIFIC = { SPECIFIC, ANY };

    StatementParamGateSideOnly(boolean isSpecific) {
        this.isSpecific = isSpecific;
    }

    public static StatementParamGateSideOnly readFromNbt(CompoundTag nbt) {
        if (nbt.getBoolean("isOn")) {
            return SPECIFIC;
        }
        return ANY;
    }

    @Override
    public void writeToNbt(CompoundTag compound) {
        compound.putBoolean("isOn", isSpecific);
    }

    @Nonnull
    @Override
    public ItemStack getItemStack() {
        return StackUtil.EMPTY;
    }

    @Override
    public ISprite getSprite() {
        if (isSpecific) {
            return BCCoreSprites.PARAM_GATE_SIDE_ONLY;
        } else {
            return null;
        }
    }

    @Override
    public DrawType getDrawType() {
        return DrawType.SPRITE_ONLY;
    }

    @Override
    public StatementParamGateSideOnly onClick(IStatementContainer source, IStatement stmt, ItemStack stack,
        StatementMouseClick mouse) {
        return null;
    }

    @Override
    public Component getDescription() {
        return Component.translatable(isSpecific ? ("gate.parameter.redstone.gateSideOnly") : "");
    }

    @Override
    public String getUniqueTag() {
        return "buildcraft:redstoneGateSideOnly";
    }

    @Override
    public IStatementParameter rotateLeft() {
        return this;
    }

    @Override
    public IStatementParameter[] getPossible(IStatementContainer source) {
        return isSpecific ? POSSIBLE_SPECIFIC : POSSIBLE_ANY;
    }
}
