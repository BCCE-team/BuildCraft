/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.statement;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.statement.IAction;
import buildcraft.lib.internal.statement.IActionExternal;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IActionInternalSided;
import buildcraft.lib.internal.statement.IActionReceptor;
import buildcraft.lib.internal.statement.IActionSingle;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

public abstract class ActionWrapper extends StatementWrapper implements IActionInternal {

    protected boolean isActive = false;

    public ActionWrapper(IAction delegate, EnumPipePart sourcePart) {
        super(delegate, sourcePart);
    }

    public IAction getDelegate() {
        return (IAction) delegate;
    }

    public static ActionWrapper wrap(IStatement statement, Direction side) {
        if (statement == null) {
            return null;
        } else if (statement instanceof ActionWrapper) {
            return (ActionWrapper) statement;
        } else if (statement instanceof IActionInternal && side == null) {
            return new ActionWrapperInternal((IActionInternal) statement);
        } else if (statement instanceof IActionInternalSided) {
            if (side == null) {
                throw new NullPointerException("side");
            }
            return new ActionWrapperInternalSided((IActionInternalSided) statement, side);
        } else if (statement instanceof IActionExternal) {
            if (side == null) {
                throw new NullPointerException("side");
            }
            return new ActionWrapperExternal((IActionExternal) statement, side);
        } else {
            throw new IllegalArgumentException("Unknown class or interface " + statement.getClass());
        }
    }

    public ActionWrapper[] getPossible() {
        IStatement[] possible = delegate.getPossible();
        boolean andSides = sourcePart != EnumPipePart.CENTER;
        List<ActionWrapper> list = new ArrayList<>(possible.length + 5);
        for (int i = 0; i < possible.length; i++) {
            list.add(wrap(possible[i], sourcePart.face));
        }
        if (andSides) {
            EnumPipePart part = sourcePart;
            for (int j = 0; j < 5; j++) {
                int i = j + possible.length;
                part = part.next();
                ActionWrapper action = wrap(delegate, part.face);
                // StatementContext owns availability: GuiElementStatement filters these generated variants against
                // ctx.getAllPossible() before presenting them, while this wrapper remains context-free.
                list.add(action);
            }
        }
        return list.toArray(new ActionWrapper[0]);
    }

    /**
     * Returns whether two actions write the same logical setting and therefore should not both be committed in one
     * gate resolution pass. Related variants are identified through the statement's getPossible() family.
     */
    public boolean targetsSameSetting(ActionWrapper other) {
        if (other == null || sourcePart != other.sourcePart) {
            return false;
        }
        IStatement mine = getDelegate();
        IStatement theirs = other.getDelegate();
        if (mine == theirs || mine.getUniqueTag().equals(theirs.getUniqueTag())) {
            return true;
        }
        return containsVariant(mine.getPossible(), theirs) && containsVariant(theirs.getPossible(), mine);
    }

    private static boolean containsVariant(IStatement[] variants, IStatement candidate) {
        String tag = candidate.getUniqueTag();
        for (IStatement variant : variants) {
            if (variant == candidate || variant.getUniqueTag().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    public void actionDeactivated(IStatementContainer source, IStatementParameter[] parameters) {
        isActive = false;
    }

    public static class ActionWrapperInternal extends ActionWrapper {
        public final IActionInternal action;

        public ActionWrapperInternal(IActionInternal action) {
            super(action, EnumPipePart.CENTER);
            this.action = action;
        }

        public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
            if (isActive && action instanceof IActionSingle) {
                if (((IActionSingle) action).singleActionTick()) {
                    return;
                }
            }
            this.action.actionActivate(source, parameters);
            isActive = true;
        }
    }

    public static class ActionWrapperInternalSided extends ActionWrapper {
        public final IActionInternalSided action;

        public ActionWrapperInternalSided(IActionInternalSided action, @Nonnull Direction side) {
            super(action, EnumPipePart.fromFacing(side));
            this.action = action;
        }

        public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
            if (isActive && action instanceof IActionSingle) {
                if (((IActionSingle) action).singleActionTick()) {
                    return;
                }
            }
            action.actionActivate(sourcePart.face, source, parameters);
            isActive = true;
        }
    }

    public static class ActionWrapperExternal extends ActionWrapper {
        public final IActionExternal action;

        public ActionWrapperExternal(IActionExternal action, @Nonnull Direction side) {
            super(action, EnumPipePart.fromFacing(side));
            this.action = action;
        }

        public void actionActivate(IStatementContainer source, IStatementParameter[] parameters) {
            if (isActive && action instanceof IActionSingle) {
                if (((IActionSingle) action).singleActionTick()) {
                    return;
                }
            }
            BlockEntity neighbourTile = getNeighbourTile(source);
            if (neighbourTile == null) {
                return;
            }
            action.actionActivate(neighbourTile, sourcePart.face, source, parameters);
            if (neighbourTile instanceof IActionReceptor) {
                IActionReceptor receptor = (IActionReceptor) neighbourTile;
                receptor.actionActivated(action, parameters);
            }
            isActive = true;
        }
    }
}
