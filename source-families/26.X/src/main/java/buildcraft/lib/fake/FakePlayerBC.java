/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.fake;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;

/** BuildCraft fake player backed by NeoForge's canonical fake-player implementation. */
public class FakePlayerBC extends FakePlayer {
    public FakePlayerBC(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    public void openTextEdit(SignBlockEntity sign, boolean frontText) {
        // Automation must never open a client sign editor.
    }
}
