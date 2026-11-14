package buildcraft.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric bridge: vanilla GUI extraction exposes its render state to the renderer, not addons. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsStateAccess {
    @Accessor("guiRenderState")
    GuiRenderState bcce$getGuiRenderState();
}
