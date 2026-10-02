package buildcraft.lib.compat.minecraft.render;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import buildcraft.lib.compat.RenderCompat;

/** The render-type factory/package cliff belongs here, never in machine geometry. */
public final class BCRenderTypes {
    private BCRenderTypes() {}
    public static RenderType solid() { return RenderTypes.solidMovingBlock(); }
    public static RenderType cutout() { return RenderTypes.cutoutMovingBlock(); }
    public static RenderType cutoutMipped() { return RenderTypes.cutoutMovingBlock(); }
    public static RenderType translucent() { return RenderCompat.translucent(); }
    public static RenderType entityCutout(Identifier texture) { return RenderTypes.entityCutout(texture); }
    public static RenderType entityCutoutNoCull(Identifier texture) { return RenderTypes.entityCutoutNoCull(texture); }
    public static RenderType entityTranslucent(Identifier texture) { return RenderTypes.entityTranslucent(texture); }
}
