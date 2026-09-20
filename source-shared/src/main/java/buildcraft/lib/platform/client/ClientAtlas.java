package buildcraft.lib.platform.client;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Reload-generation views. Sprite additions are exposed only during the mutable pre-stitch phase. */
public final class ClientAtlas {
    private ClientAtlas() {}
    public record Before(TextureAtlas getAtlas, Consumer<ResourceLocation> sprites) {
        public Before { Objects.requireNonNull(getAtlas); Objects.requireNonNull(sprites); }
        public void addSprite(ResourceLocation sprite) { sprites.accept(sprite); }
    }
    public record After(TextureAtlas getAtlas) {
        public After { Objects.requireNonNull(getAtlas); }
    }
}
