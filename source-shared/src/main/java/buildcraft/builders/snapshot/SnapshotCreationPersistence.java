/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.snapshot;

import buildcraft.lib.net.MessageManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Keeps newly-created snapshot content in the stores that actually own it. */
public final class SnapshotCreationPersistence {
    private SnapshotCreationPersistence() {
    }

    public static void persist(Level level, Snapshot snapshot, Snapshot.Header header) {
        if (level == null || level.isClientSide || snapshot == null || header == null) {
            return;
        }

        Snapshot persistedSnapshot = snapshot.copy();
        persistedSnapshot.key = new Snapshot.Key(persistedSnapshot.key, header);
        GlobalSavedDataSnapshots.cacheServerSnapshot(level, persistedSnapshot);

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(header.owner);
        if (owner != null) {
            MessageManager.sendTo(new MessageSnapshotResponse(persistedSnapshot), owner);
        }
    }
}
