package buildcraft.api.v2.statement;

/** Placement of a statement in the BuildCraft gate runtime. */
public enum StatementScope {
    /** The gate's own state, without a selected face. */
    INTERNAL,
    /** State of the pipe or gate on a selected face. */
    INTERNAL_SIDED,
    /** State of the adjacent block entity on a selected face. */
    EXTERNAL
}
