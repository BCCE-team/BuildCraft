package buildcraft.lib.gui.statement;

import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.gui.ISimpleDrawable;

/** An {@link IStatementParameter} that provides methods to draw itself. */
public interface IDrawingParameter extends IStatementParameter {
    ISimpleDrawable getDrawable();
}
