package buildcraft.robotics.statements;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.StatementMouseClick;
import buildcraft.lib.internal.statement.StatementParameterItemStack;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.misc.ItemStackUtil;

public class StatementParameterMapLocation extends StatementParameterItemStack {

    public static final String TAG = "buildcraft:maplocation";

    public StatementParameterMapLocation() { super(); }
    public StatementParameterMapLocation(ItemStack stack) { super(stack); }
    public StatementParameterMapLocation(CompoundTag nbt) { super(nbt); }

    @Override
    public String getUniqueTag() { return TAG; }

    @Override
    public StatementParameterMapLocation onClick(
            IStatementContainer source, IStatement stmt,
            ItemStack clicked, StatementMouseClick mouse) {
        if (clicked.isEmpty()) return new StatementParameterMapLocation();
        if (BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(clicked).isEmpty()) return this;
        ItemStack copy = clicked.copy();
        copy.setCount(1);
        return new StatementParameterMapLocation(copy);
    }

    public static StatementParameterMapLocation readFromNbt(CompoundTag nbt) {
        return new StatementParameterMapLocation(nbt);
    }

    public static StatementParameterMapLocation readFromBuf(FriendlyByteBuf buffer) {
        return new StatementParameterMapLocation(ItemStackUtil.readOptional(buffer));
    }
}
