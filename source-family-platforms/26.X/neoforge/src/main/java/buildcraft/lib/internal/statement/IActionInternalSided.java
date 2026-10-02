package buildcraft.lib.internal.statement;

import net.minecraft.core.Direction;

public interface IActionInternalSided extends IAction {
    void actionActivate(Direction side, IStatementContainer source, IStatementParameter[] parameters);
}
