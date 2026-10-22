/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client;

import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.transport.internal.pipe.IPipeHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client-only facade tint bridge for 26.1.x.
 *
 * <p>{@link BlockTintSource#colorInWorld} references the client-only
 * {@code BlockAndTintGetter} type in its method descriptor. Keeping that
 * invocation out of {@code PluggableFacade} is important: pluggable
 * definitions are constructed on a dedicated server as well, so resolving
 * the tint method while verifying the common pluggable class crashes server
 * startup before worlds can load.</p>
 */
public final class FacadeTintClient2612 {
    private FacadeTintClient2612() {
    }

    public static int getBlockColor(FacadePhasedState state, IPipeHolder holder, int tintIndex) {
        BlockTintSource tintSource = Minecraft.getInstance().getBlockColors()
            .getTintSource(state.stateInfo.state, tintIndex);
        if (tintSource == null) {
            return -1;
        }

        // Preserve biome/position-aware tinting for placed facades.
        if (holder.getPipeWorld() instanceof ClientLevel clientLevel) {
            return tintSource.colorInWorld(state.stateInfo.state, clientLevel, holder.getPipePos());
        }
        return tintSource.color(state.stateInfo.state);
    }
}
