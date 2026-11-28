package buildcraft.fabric.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric bridge: vanilla GUI extraction exposes its render state to the renderer, not addons. */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsStateAccess {
    @Accessor("guiRenderState")
    GuiRenderState bcce$getGuiRenderState();
}
