package buildcraft.lib.internal.core.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Holds information on some sort of sprite. These might not be part of the main texture atlas (they might be from a
 * GUI texture), in which case {@link #bindTexture()} should be called before using the results from
 * {@link #getInterpU(double)} or {@link #getInterpV(double)}
 * <p>
 * <b>IMPORTANT:</b> Coordinates are normalized to the 0..1 range, matching Minecraft 1.21.1's
 * {@link TextureAtlasSprite#getU(float)} and {@link TextureAtlasSprite#getV(float)} methods. */
public interface ISprite {
    /** Binds this sprites backing texture so that this sprite will be referenced when you use the results of
     * {@link #getInterpU(double)} and {@link #getInterpV(double)} */
    void bindTexture();

    /** @param u A value between 0 and 1
     * @return */
 //   @Deprecated
    float getInterpU(double u);

    /** @param v A value between 0 and 1
     * @return */
 //   @Deprecated
    float getInterpV(double v);
}
