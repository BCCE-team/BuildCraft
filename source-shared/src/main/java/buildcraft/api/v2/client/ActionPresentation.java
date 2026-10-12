package buildcraft.api.v2.client;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** An action identifier and label; menu actions still require server-side authorization. */
public record ActionPresentation(ResourceLocation actionId, String titleKey, ResourceLocation icon) {
    public ActionPresentation {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(titleKey, "titleKey");
    }
}
