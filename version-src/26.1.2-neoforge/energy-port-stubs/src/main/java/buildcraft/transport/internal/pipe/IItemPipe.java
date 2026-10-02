package buildcraft.transport.internal.pipe;

/**
 * Compile-only transport boundary for the energy port.
 *
 * <p>Energy only needs this marker for the engine interaction guard. The
 * actual item-pipe implementation remains owned by the later transport port
 * and is never bundled from this source set.</p>
 */
public interface IItemPipe {
}
