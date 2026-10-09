package buildcraft.lib.internal.statement;

import java.util.List;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.IConvertable;
import buildcraft.lib.internal.core.render.ISprite;
import com.google.common.collect.ImmutableList;

import net.minecraft.network.chat.Component;

public interface IGuiSlot extends IConvertable {
    /** Every statement needs a unique tag, it should be in the format of "&lt;modid&gt;:&lt;name&gt;".
     *
     * @return the unique id */
    String getUniqueTag();

    /** Return the description in the UI. Note that this should NEVER be called directly, instead this acts as a bridge
     * for {@link #getTooltip()}. (As such this might return null or throw an exception) */
    Component getDescription();

    /** @return The full tooltip for the UI. */
    default List<Component> getTooltip() {
    	Component desc = getDescription();
        if (desc == null) {
            return ImmutableList.of();
        }
        return ImmutableList.of(desc);
    }

    /** @return A sprite to show in a GUI, or null if this should not render a sprite. */
    @Nullable
    ISprite getSprite();
}
