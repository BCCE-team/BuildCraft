package buildcraft.api.v2.event;

/** Events are server-thread only. Placement notifications fire after success and cannot cancel. */
public enum WorldEventKind {
    BLOCK_INTERACT(true),
    BEFORE_BLOCK_PLACE(true),
    BLOCK_PLACED(false),
    PIPE_PLACED(false);

    private final boolean cancellable;
    WorldEventKind(boolean cancellable) { this.cancellable = cancellable; }
    public boolean cancellable() { return cancellable; }
}
