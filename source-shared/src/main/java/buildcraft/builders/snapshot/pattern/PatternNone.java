package buildcraft.builders.snapshot.pattern;

import buildcraft.builders.internal.filler.legacy.IFilledTemplate;
import buildcraft.builders.internal.filler.legacy.IFillerPatternShape;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.builders.BCBuildersSprites;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;

public class PatternNone extends Pattern implements IFillerPatternShape {
    public PatternNone() {
        super("none");
    }

    @Override
    public SpriteHolder getSprite() {
        return BCBuildersSprites.FILLER_NONE;
    }

    @Override
    public boolean fillTemplate(IFilledTemplate filledTemplate, IStatementParameter[] params) {
        return false;
    }
}
