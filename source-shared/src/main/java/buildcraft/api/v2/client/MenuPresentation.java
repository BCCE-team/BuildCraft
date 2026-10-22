package buildcraft.api.v2.client;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** GUI metadata; an addon registers its actual screen/menu using the target Minecraft loader. */
public record MenuPresentation(ResourceLocation menuId, String titleKey, List<ActionPresentation> actions) {
    public MenuPresentation {
        Objects.requireNonNull(menuId, "menuId");
        Objects.requireNonNull(titleKey, "titleKey");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        if (actions.size() > 64) throw new IllegalArgumentException("Too many menu actions");
        for (ActionPresentation action : actions) Objects.requireNonNull(action, "action");
    }
}
