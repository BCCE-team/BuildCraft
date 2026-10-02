package buildcraft.lib.gui.json;

import buildcraft.lib.client.sprite.SpriteAtlas;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.internal.core.render.ISprite;

public class SpriteDelegate implements ISprite {
    public ISprite delegate;

    public SpriteDelegate(ISprite delegate) {
        this.delegate = delegate;
    }

    public SpriteDelegate() {
        this(new SpriteAtlas(SpriteUtil.missingSprite()));
    }

    public void bindTexture() {
        if (delegate != null) {
            delegate.bindTexture();
        }
    }

    public float getInterpU(double u) {
        return delegate == null ? 0 : delegate.getInterpU(u);
    }

    public float getInterpV(double v) {
        return delegate == null ? 0 : delegate.getInterpV(v);
    }
}
