/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.net.MessageManager;

public enum ClientSnapshots {
    INSTANCE;

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final List<Snapshot.Key> pending = new ArrayList<>();

    public Snapshot getSnapshot(Snapshot.Key key) {
        Snapshot found = snapshots.stream()
            .filter(snapshot -> Arrays.equals(snapshot.key.hash, key.hash))
            .findFirst()
            .orElse(null);
        if (found == null) {
            found = GlobalSavedDataSnapshots.getClientSnapshot(key);
            if (found != null) {
                onSnapshotReceived(found);
            }
        }
        if (found == null && !pending.contains(key)) {
            pending.add(key);
            MessageManager.sendToServer(new MessageSnapshotRequest(key));
        }
        return found;
    }

    public void onSnapshotReceived(Snapshot snapshot) {
        pending.removeIf(key -> Arrays.equals(key.hash, snapshot.key.hash));
        snapshots.removeIf(existing -> Arrays.equals(existing.key.hash, snapshot.key.hash));
        snapshots.add(snapshot);
    }

    /**
     * Legacy PoseStack-only API kept for binary compatibility with older consumers.
     * Modern GUI preview rendering is provided by BlueprintRenderer via GuiGraphics/PiP,
     * which supplies the render-state context absent from these old signatures.
     */
    public void renderSnapshot(PoseStack pose, Snapshot.Header header, int offsetX, int offsetY, int sizeX, int sizeY) {
        // Requires GUI-scoped graphics; old callers may still invoke this stub safely.
    }
    public void renderSnapshot(PoseStack pose, Snapshot snapshot, int offsetX, int offsetY, int sizeX, int sizeY) {
        // Requires GUI-scoped graphics; old callers may still invoke this stub safely.
    }
}
