package buildcraft.robotics.statements;

import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.core.statements.StatementParameterItemStackExact;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class ActionStationRequestItems extends ActionStationInputItems {

    public ActionStationRequestItems() {
        super("buildcraft:station.request_items");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.station.requestItems");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {}

    public static boolean requests(IStatementParameter[] parameters, ItemStack stack) {
        if (parameters == null) return false;
        for (IStatementParameter p : parameters) {
            if (p instanceof StatementParameterItemStackExact e) {
                ItemStack filter = e.getItemStack();
                if (!filter.isEmpty() && ItemStack.isSameItemSameComponents(filter, stack)) return true;
            }
        }
        return false;
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.ACTION_STATION_REQUEST_ITEMS;
    }
}
