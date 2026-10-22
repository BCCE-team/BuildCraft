/** Internal compatibility view for classic BuildCraft boxes. */
package buildcraft.lib.internal.area;

import buildcraft.api.v2.area.BlockBox;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Built-in mutable box contract. Public consumers should snapshot it as {@link BlockBox}. */
public interface IBox extends IZone {
    IBox expand(int amount);
    IBox contract(int amount);
    BlockPos min();
    BlockPos max();

    default BlockPos size() {
        return max().subtract(min()).offset(1, 1, 1);
    }

    default Optional<BlockBox> bounds() {
        return Optional.of(new BlockBox(min(), max()));
    }
}
