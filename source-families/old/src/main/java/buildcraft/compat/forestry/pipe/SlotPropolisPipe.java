/*
 * Genetic-filter GUI behaviour adapted from Forestry Community Edition.
 * Forestry is distributed under the GNU Lesser General Public License v3.0.
 */
package buildcraft.compat.forestry.pipe;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/** Player slot that can be disabled while Forestry's selection overlay is open. */
public final class SlotPropolisPipe extends Slot {
    private boolean enabled = true;

    public SlotPropolisPipe(Container inventory, int index, int x, int y) {
        super(inventory, index, x, y);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }
}
