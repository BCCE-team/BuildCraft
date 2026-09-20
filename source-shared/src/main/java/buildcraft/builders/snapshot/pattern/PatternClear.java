/** Copyright (c) 2011-2015, SpaceToad and the BuildCraft Team http://www.mod-buildcraft.com
 * <p/>
 * BuildCraft is distributed under the terms of the Minecraft Mod Public License 1.0, or MMPL. Please check the contents
 * of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt */
package buildcraft.builders.snapshot.pattern;

import buildcraft.builders.internal.filler.legacy.IFilledTemplate;
import buildcraft.builders.internal.filler.legacy.IFillerPatternShape;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.builders.BCBuildersSprites;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;

public class PatternClear extends Pattern implements IFillerPatternShape {
    public PatternClear() {
        super("clear");
    }

    @Override
    public SpriteHolder getSprite() {
        return BCBuildersSprites.FILLER_CLEAR;
    }

    @Override
    public boolean fillTemplate(IFilledTemplate filledTemplate, IStatementParameter[] params) {
        return true;
    }
}
