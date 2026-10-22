package buildcraft.lib.internal.tiles;

import java.util.List;

import net.minecraft.core.Direction;

public interface IDebuggable {
    /** Get the debug information from a tile entity as a list of strings, used for the F3 debug menu. The left and
     * right parameters correspond to the sides of the F3 screen.
     * 
     * @param side The side the block was clicked on, may be null if we don't know, or is the "centre" side */
    void getDebugInfo(List<String> left, List<String> right, Direction side);

    /** Same as {@link #getDebugInfo(List, List, Direction)}, but only for client
     *
     * @param side same as for {@link #getDebugInfo(List, List, Direction)} */
    default void getClientDebugInfo(List<String> left, List<String> right, Direction side) {
    }
}
