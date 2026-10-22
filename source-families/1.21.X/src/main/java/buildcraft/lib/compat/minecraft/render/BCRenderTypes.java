//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.render;

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//? }

/** The render-type factory/package cliff belongs here, never in machine geometry. */
public final class BCRenderTypes {
    private BCRenderTypes() {}
//? if >=1.21.11 {
    public static RenderType solid() { return RenderTypes.solidMovingBlock(); }
    public static RenderType cutout() { return RenderTypes.cutoutMovingBlock(); }
    public static RenderType cutoutMipped() { return RenderTypes.cutoutMovingBlock(); }
    public static RenderType translucent() { return RenderTypes.translucentMovingBlock(); }
    public static RenderType entityCutout(Identifier texture) { return RenderTypes.entityCutout(texture); }
    public static RenderType entityCutoutNoCull(Identifier texture) { return RenderTypes.entityCutoutNoCull(texture); }
    public static RenderType entityTranslucent(Identifier texture) { return RenderTypes.entityTranslucent(texture); }
//? } else {
    public static RenderType solid() { return RenderType.solid(); }
    public static RenderType cutout() { return RenderType.cutout(); }
    public static RenderType cutoutMipped() { return RenderType.cutoutMipped(); }
    public static RenderType translucent() { return RenderType.translucent(); }
    public static RenderType entityCutout(ResourceLocation texture) { return RenderType.entityCutout(texture); }
    public static RenderType entityCutoutNoCull(ResourceLocation texture) { return RenderType.entityCutoutNoCull(texture); }
    public static RenderType entityTranslucent(ResourceLocation texture) { return RenderType.entityTranslucent(texture); }
//? }
}
