//? source if >=26.3
package buildcraft.transport.internal.pipe;

public enum FluidAction {
    EXECUTE, SIMULATE;

    public boolean execute() { return this == EXECUTE; }
    public boolean simulate() { return this == SIMULATE; }
}
