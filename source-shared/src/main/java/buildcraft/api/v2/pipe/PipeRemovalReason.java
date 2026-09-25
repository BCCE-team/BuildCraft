package buildcraft.api.v2.pipe;

/** Lifecycle reason kept distinct so chunk unload never looks like block destruction. */
public enum PipeRemovalReason {
    CHUNK_UNLOAD,
    LEVEL_UNLOAD,
    INVALIDATED,
    DESTROYED,
    REPLACED
}
