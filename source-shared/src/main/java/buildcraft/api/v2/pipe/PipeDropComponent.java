package buildcraft.api.v2.pipe;

import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

/** Lets a component contribute owned items when the physical pipe is destroyed. */
public interface PipeDropComponent extends PipeComponent {
    void collectDrops(PipeDropContext context, Consumer<ItemStack> output);
}
