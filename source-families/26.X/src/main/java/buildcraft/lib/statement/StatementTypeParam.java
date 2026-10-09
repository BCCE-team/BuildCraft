package buildcraft.lib.statement;

import java.io.IOException;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.internal.statement.StatementManager.IParamReaderBuf;
import buildcraft.lib.internal.statement.StatementManager.IParameterReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.compat.NbtCompat;

public class StatementTypeParam extends StatementType<IStatementParameter> {
    private static final int MAX_NETWORK_ID_LENGTH = 256;
    public static final StatementTypeParam INSTANCE = new StatementTypeParam();

    public StatementTypeParam() {
        super(IStatementParameter.class, null);
    }

    public IStatementParameter convertToType(Object value) {
        return value instanceof IStatementParameter ? (IStatementParameter) value : null;
    }

    public IStatementParameter readFromNbt(CompoundTag nbt) {
        String kind = NbtCompat.getString(nbt, "kind");
        IParameterReader reader = StatementManager.getParameterReader(kind);
        if (reader == null) {
            return null;
        } else {
            return reader.readFromNbt(nbt);
        }
    }

    public CompoundTag writeToNbt(IStatementParameter slot) {
        CompoundTag nbt = new CompoundTag();
        if (slot != null) {
            slot.writeToNbt(nbt);
            nbt.putString("kind", slot.getUniqueTag());
        }
        return nbt;
    }

    public IStatementParameter readFromBuffer(FriendlyByteBuf buffer) throws IOException {
        if (buffer.readBoolean()) {
            String tag = buffer.readUtf(MAX_NETWORK_ID_LENGTH);
            IParamReaderBuf reader = StatementManager.paramsBuf.get(tag);
            if (reader == null) {
                throw new InvalidInputDataException("Unknown paramater type " + tag);
            }
            return reader.readFromBuf(buffer);
        } else {
            return null;
        }
    }

    public void writeToBuffer(FriendlyByteBuf buffer, IStatementParameter slot) {
        if (slot == null) {
            buffer.writeBoolean(false);
        } else {
            buffer.writeBoolean(true);
            buffer.writeUtf(slot.getUniqueTag(), MAX_NETWORK_ID_LENGTH);
            slot.writeToBuf(buffer);
        }
    }
}
