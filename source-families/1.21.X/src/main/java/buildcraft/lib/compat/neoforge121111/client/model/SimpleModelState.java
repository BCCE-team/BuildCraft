//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model;

import com.mojang.math.Transformation;

import net.minecraft.client.resources.model.ModelState;

/** Minimal SimpleModelState compatibility facade for NeoForge 1.21.11. */
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
