/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package buildcraft.silicon.plug;

import net.minecraft.network.FriendlyByteBuf;

/** The same persistence contract for freshly discovered and restored cache entries. */
final class FacadeStateRoundTrip {
    private FacadeStateRoundTrip() {}

    static void verify(FacadeBlockStateInfo info, FriendlyByteBuf buffer) {
        try {
            FacadePhasedState phase = info.createPhased(null);
            if (FacadePhasedState.readFromNbt(phase.writeToNbt()).stateInfo != info) {
                throw new IllegalStateException("Facade NBT round trip changed " + info.state);
            }
            phase.writeToBuffer(buffer);
            if (FacadePhasedState.readFromBuffer(buffer).stateInfo != info || buffer.readableBytes() != 0) {
                throw new IllegalStateException("Facade network round trip changed " + info.state);
            }
        } finally {
            buffer.clear();
        }
    }
}
