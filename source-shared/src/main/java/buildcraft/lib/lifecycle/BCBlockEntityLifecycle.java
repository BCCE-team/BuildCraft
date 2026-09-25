package buildcraft.lib.lifecycle;

/**
 * Loader-neutral lifecycle hooks for BuildCraft block entities.
 *
 * <p>Invalidation/removal from the active level is deliberately distinct from gameplay destruction. Chunk unloads
 * must never be treated as block destruction or trigger drops/refunds that belong to an actual block break.</p>
 */
public interface BCBlockEntityLifecycle {
    /** The block entity became active in a loaded level/chunk. */
    default void bcOnLoad() {
    }

    /** The owning chunk is unloading. This is not block destruction. */
    default void bcOnChunkUnload() {
    }

    /** Vanilla invalidated the block entity instance. This may happen for reasons other than destruction. */
    default void bcOnInvalidated() {
    }

    /** A previously invalidated instance became active again. */
    default void bcOnRevived() {
    }

    /** BuildCraft's block-removal path confirmed an actual gameplay removal. */
    default void bcOnDestroyed(boolean dropSelf) {
    }
}
