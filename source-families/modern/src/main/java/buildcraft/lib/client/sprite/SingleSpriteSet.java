//? source if >=1.21.11
package buildcraft.lib.client.sprite;

import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;

/** 1.21.11 SpriteSet bridge with the new first() method. */
public class SingleSpriteSet implements SpriteSet {
    public TextureAtlasSprite texture;

    public SingleSpriteSet(TextureAtlasSprite texture) {
        this.texture = texture;
    }

    public TextureAtlasSprite get(int age, int lifetime) {
        return texture;
    }

    public TextureAtlasSprite get(RandomSource random) {
        return texture;
    }

    public TextureAtlasSprite first() {
        return texture;
    }
}
