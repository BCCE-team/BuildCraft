/*
 * Copyright (c) 2026 the BuildCraftUnofficial contributors (adapted for BCCE)
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.client.render.pip;

// Whole-file >=1.21.10: PictureInPictureRenderState is the 1.21.5+ GUI PiP pipeline (absent on
// 1.21.1), so this PiP render-state record is gated out there. On 1.21.1 the same preview is drawn
// directly into the GUI by BlueprintGuiRenderer (no PiP render state), so nothing references this.
import javax.annotation.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

import buildcraft.builders.snapshot.Snapshot;

/**
 * PiP state carrying a {@link Snapshot} (a {@link buildcraft.builders.snapshot.Blueprint} or a
 * {@link buildcraft.builders.snapshot.Template}) to be rendered into an offscreen texture by
 * {@link BlueprintPipRenderer}. Deliberately mirrors the shape of vanilla's
 * {@code GuiBookModelRenderState} / {@code GuiEntityRenderState}: a record whose fields pin down
 * the viewport rectangle and scale, with a convenience constructor that computes the clipped
 * {@code bounds} from the raw coordinates plus optional scissor.
 * <p>
 * <b>Why rotation isn't a field:</b> the yaw animation is sampled from wall-clock time inside
 * {@link BlueprintPipRenderer#renderToTexture}. Keeping it out of the state means
 * {@code canBeReusedFor} (inherited from the base class, matches on texture dimensions) can return
 * {@code true} every frame, so we don't pay the cost of reallocating the GPU texture on every
 * mouse-hover frame just because the blueprint is "animating".
 */
public record BlueprintPipRenderState(
    Snapshot snapshot,
    int x0,
    int y0,
    int x1,
    int y1,
    float scale,
    Matrix3x2fc pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {

    /**
     * Convenience constructor: computes the clipped {@code bounds} from the viewport rectangle and
     * the current scissor (if any), matching the pattern used by vanilla PiP state records. The
     * base class needs a valid bounds so blit-skipping works when the preview is clipped entirely
     * out of the screen.
     */
    public BlueprintPipRenderState(Snapshot snapshot,
                                   int x0, int y0, int x1, int y1,
                                   float scale,
                                   @Nullable ScreenRectangle scissorArea) {
        this(snapshot, x0, y0, x1, y1, scale, new Matrix3x2f(), scissorArea);
    }

    public BlueprintPipRenderState(Snapshot snapshot,
                                   int x0, int y0, int x1, int y1,
                                   float scale,
                                   Matrix3x2fc pose,
                                   @Nullable ScreenRectangle scissorArea) {
        this(snapshot, x0, y0, x1, y1, scale, new Matrix3x2f(pose), scissorArea,
            PictureInPictureRenderState.getBounds(x0, y0, x1, y1, pose, scissorArea));
    }
}
