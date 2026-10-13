package buildcraft.api.v2.template;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Ordered template-item handler service. */
public interface TemplateService {
    void register(ResourceLocation id, int priority, TemplateHandler handler);
    List<TemplateRegistration> handlers();

    default boolean acceptsItem(ItemStack stack) {
        for (TemplateRegistration entry : handlers()) {
            try {
                if (entry.handler().acceptsItem(stack)) return true;
            } catch (RuntimeException error) {
                System.getLogger(TemplateService.class.getName()).log(System.Logger.Level.WARNING,
                    "Template item predicate failed: " + entry.id(), error);
            }
        }
        return false;
    }

    default boolean handle(Level level, BlockPos pos, Player actor, ItemStack stack) {
        for (TemplateRegistration entry : handlers()) {
            try {
                if (entry.handler().handle(level, pos, actor, stack)) return true;
            } catch (RuntimeException error) {
                System.getLogger(TemplateService.class.getName()).log(System.Logger.Level.WARNING,
                    "Template handler failed: " + entry.id(), error);
            }
        }
        return false;
    }
}
