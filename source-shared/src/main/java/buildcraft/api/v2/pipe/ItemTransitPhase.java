package buildcraft.api.v2.pipe;

/** Stages of one travelling item's lifetime in an item pipe. */
public enum ItemTransitPhase {
    ENTER, CENTER, ROUTING, EXIT, TRANSFERRED, REJECTED, CANCELLED, EJECTED
}
