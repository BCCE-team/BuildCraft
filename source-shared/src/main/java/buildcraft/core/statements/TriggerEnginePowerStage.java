package buildcraft.core.statements;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.internal.enums.EnumPowerStage;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerExternal;
import buildcraft.core.BCCoreSprites;
import buildcraft.core.BCCoreStatements;
import buildcraft.lib.engine.TileEngineBase_BC8;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

public class TriggerEnginePowerStage extends BCStatement implements ITriggerExternal {

    public final EnumPowerStage stage;

    public TriggerEnginePowerStage(EnumPowerStage stage) {
        super("buildcraft:engine.stage." + stage.getModelName());
        this.stage = stage;
    }

    public static boolean isTriggeringTile(BlockEntity tile) {
        return tile instanceof TileEngineBase_BC8;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.engine." + stage.getModelName());
    }

    @Override
    public ISprite getSprite() {
        return BCCoreSprites.TRIGGER_POWER_STAGE.get(stage);
    }

    @Override
    public boolean isTriggerActive(BlockEntity target, Direction side, IStatementContainer source,
        IStatementParameter[] parameters) {
        if (target instanceof TileEngineBase_BC8) {
            return ((TileEngineBase_BC8) target).getPowerStage() == stage;
        }
        return false;
    }

    @Override
    public IStatement[] getPossible() {
        return BCCoreStatements.TRIGGER_POWER_STAGES;
    }
}
