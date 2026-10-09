package buildcraft.lib.internal.statement.containers;

import buildcraft.lib.internal.statement.IStatementContainer;

import net.minecraft.core.Direction;

/** Created by asie on 3/14/15. */
public interface ISidedStatementContainer extends IStatementContainer {
    Direction getSide();
}
