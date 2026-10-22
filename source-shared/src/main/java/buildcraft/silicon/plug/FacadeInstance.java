package buildcraft.silicon.plug;

import java.util.Arrays;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.DyeColor;

public class FacadeInstance {
    public final FacadePhasedState[] phasedStates;
    public final FacadeType type;
    public final boolean isHollow;

    public FacadeInstance(FacadePhasedState[] phasedStates, boolean isHollow) {
        if (phasedStates == null) throw new NullPointerException("phasedStates");
        if (phasedStates.length == 0) throw new IllegalArgumentException("phasedStates.length was 0");
        // Maximum of 17 states - 16 for each colour, 1 for no colour
        if (phasedStates.length > 17) throw new IllegalArgumentException("phasedStates.length was > 17");
        this.phasedStates = phasedStates;
        if (phasedStates.length == 1) {
            type = FacadeType.Basic;
        } else {
            type = FacadeType.Phased;
        }
        this.isHollow = isHollow;
    }

    public static FacadeInstance createSingle(FacadeBlockStateInfo info, boolean isHollow) {
        return new FacadeInstance(new FacadePhasedState[] { new FacadePhasedState(info, null) }, isHollow);
    }

    public static FacadeInstance readFromNbt(CompoundTag nbt) {
        ListTag list = nbt.getList("states", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return FacadeInstance.createSingle(FacadeStateManager.defaultState, false);
        }
        if (list.size() > 17) {
            throw new IllegalArgumentException("Too many facade phases: " + list.size());
        }
        FacadePhasedState[] states = new FacadePhasedState[list.size()];
        for (int i = 0; i < list.size(); i++) {
            states[i] = FacadePhasedState.readFromNbt(list.getCompound(i));
        }
        boolean hollow = nbt.getBoolean("isHollow");
        return new FacadeInstance(states, hollow);
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();
        for (FacadePhasedState state : phasedStates) {
            list.add(state.writeToNbt());
        }
        nbt.put("states", list);
        nbt.putBoolean("isHollow", isHollow);
        return nbt;
    }

    public static FacadeInstance readFromBuffer(FriendlyByteBuf buf) {
        boolean isHollow = buf.readBoolean();
        int count = buf.readShort();
        if (count < 1 || count > 17) {
            throw new IllegalArgumentException("Invalid facade phase count: " + count);
        }
        FacadePhasedState[] states = new FacadePhasedState[count];
        for (int i = 0; i < count; i++) {
            states[i] = FacadePhasedState.readFromBuffer(buf);
        }
        return new FacadeInstance(states, isHollow);
    }

    public void writeToBuffer(FriendlyByteBuf buf) {
        if (phasedStates.length < 1 || phasedStates.length > 17) {
            throw new IllegalStateException("Invalid facade phase count: " + phasedStates.length);
        }
        buf.writeBoolean(isHollow);
        buf.writeShort(phasedStates.length);
        for (FacadePhasedState phasedState : phasedStates) {
            phasedState.writeToBuffer(buf);
        }
    }

    public boolean canAddColour(DyeColor colour) {
        for (FacadePhasedState state : phasedStates) {
            if (state.activeColour == colour) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public FacadeInstance withState(FacadePhasedState state) {
        if (phasedStates.length < 17 && canAddColour(state.activeColour)) {
            FacadePhasedState[] newStates = Arrays.copyOf(phasedStates, phasedStates.length + 1);
            newStates[newStates.length - 1] = state;
            return new FacadeInstance(newStates, isHollow);
        } else {
            return null;
        }
    }

    public FacadePhasedState getCurrentStateForStack() {
        int count = phasedStates.length;
        if (count == 1) {
            return phasedStates[0];
        } else {
            int now = (int) (System.currentTimeMillis() % 100_000);
            return phasedStates[(now / 500) % count];
        }
    }

    public FacadeInstance withSwappedIsHollow() {
        return new FacadeInstance(phasedStates, !isHollow);
    }

    public boolean areAllStatesSolid(Direction side) {
        for (FacadePhasedState state : phasedStates) {
            if (!state.isSideSolid(side)) {
                return false;
            }
        }
        return true;
    }

    public FacadeType getType() {
        return type;
    }

    public boolean isHollow() {
        return isHollow;
    }

    public FacadePhasedState[] getPhasedStates() {
        return phasedStates;
    }
}
