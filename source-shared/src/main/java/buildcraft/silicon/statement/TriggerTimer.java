/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.statement;

import java.util.Locale;

import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;
import buildcraft.silicon.BCSiliconSprites;
import buildcraft.silicon.BCSiliconStatements;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class TriggerTimer extends BCStatement implements ITriggerInternal {
    public enum Duration {
        SHORT(5),
        MEDIUM(10),
        LONG(15);

        public final int seconds;

        Duration(int seconds) {
            this.seconds = seconds;
        }
    }

    private final Duration duration;

    public TriggerTimer(Duration duration) {
        super("buildcraft:timer_" + duration.name().toLowerCase(Locale.ROOT));
        this.duration = duration;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.timer", duration.seconds);
    }

    @Override
    public boolean isTriggerActive(IStatementContainer source, IStatementParameter[] parameters) {
        BlockEntity tile = source.getTile();
        if (tile == null) {
            return false;
        }
        Level level = tile.getLevel();
        if (level == null) {
            return false;
        }
        return level.getGameTime() % (20L * duration.seconds) == 0;
    }

    @Override
    public IStatement[] getPossible() {
        return BCSiliconStatements.TRIGGER_TIMER;
    }

    @Override
    public SpriteHolder getSprite() {
        switch (duration) {
            case SHORT:
                return BCSiliconSprites.TRIGGER_TIMER_SHORT;
            case MEDIUM:
                return BCSiliconSprites.TRIGGER_TIMER_MEDIUM;
            case LONG:
            default:
                return BCSiliconSprites.TRIGGER_TIMER_LONG;
        }
    }
}
