package buildcraft.lib.internal.statement.containers;

import javax.annotation.Nullable;

import buildcraft.lib.internal.area.IBox;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public interface IFillerStatementContainer extends IStatementContainer {

    /** Unlike in {@link IStatementContainer} some containers might not be tile based (for example the volume box). */
    @Nullable
    BlockEntity getTile();

    Level getFillerWorld();

    /** @return True if this filler has a non-zero sized box. */
    boolean hasBox();

    /** @return The box that the filler will (default) to building in.
     * @throws IllegalStateException if {@link #hasBox()} returns false. */
    IBox getBox() throws IllegalStateException;

    void setPattern(IFillerPattern pattern, IStatementParameter[] params);
}
