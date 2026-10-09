package buildcraft.lib.compat.neoforge2612.client.model;

import com.mojang.math.Transformation;

import net.minecraft.client.resources.model.ModelState;

/** Minimal SimpleModelState facade used by shared BuildCraft model code. */
public class SimpleModelState implements ModelState {
    private final Transformation rotation;
    private final boolean uvLocked;

    public SimpleModelState(Transformation rotation) {
        this(rotation, false);
    }

    public SimpleModelState(Transformation rotation, boolean uvLocked) {
        this.rotation = rotation;
        this.uvLocked = uvLocked;
    }

    public Transformation getRotation() { return rotation; }

    public boolean isUvLocked() { return uvLocked; }
}
