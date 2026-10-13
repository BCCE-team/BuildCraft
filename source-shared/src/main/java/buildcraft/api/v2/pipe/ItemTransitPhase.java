package buildcraft.api.v2.pipe;

/** Stages of one travelling item's lifetime in an item pipe. */
public enum ItemTransitPhase {
    ENTER, CENTER, SIDE_CHECK, SPLIT, FIND_DEST, MODIFY_SPEED, ROUTING, EXIT, TRANSFERRED, REJECTED, CANCELLED, EJECTED
}
