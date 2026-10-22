/** Internal compatibility view for classic BuildCraft zones. */
package buildcraft.lib.internal.area;

import buildcraft.api.v2.area.Zone;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Built-in mutable zone contract. Addons should use API2 {@link Zone}. */
public interface IZone extends Zone {
    double distanceTo(BlockPos pos);
    double distanceToSquared(BlockPos pos);
    boolean contains(Vec3 point);
    BlockPos getRandomBlockPos(Random rand);

    default boolean contains(BlockPos pos) {
        return contains(Vec3.atCenterOf(pos));
    }
}
