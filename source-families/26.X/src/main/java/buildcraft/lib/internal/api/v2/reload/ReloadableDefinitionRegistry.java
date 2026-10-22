package buildcraft.lib.internal.api.v2.reload;

import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.api.v2.reload.ReloadGeneration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the last-known-good immutable definition snapshot.
 */
public final class ReloadableDefinitionRegistry<V> {
    private final AtomicReference<DefinitionSnapshot<V>> published = new AtomicReference<>(DefinitionSnapshot.empty());
    private final AtomicLong nextGeneration = new AtomicLong(1);

    public DefinitionSnapshot<V> current() {
        return published.get();
    }

    public ReloadTransaction<V> beginReload() {
        return new ReloadTransaction<>(
            this,
            published.get(),
            new ReloadGeneration(nextGeneration.getAndIncrement())
        );
    }

    boolean publish(DefinitionSnapshot<V> expectedBase, DefinitionSnapshot<V> candidate) {
        return published.compareAndSet(expectedBase, candidate);
    }
}
