package buildcraft.api.v2.pipe;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Typed server snapshot and client update for one registered sync channel. */
public record PipeComponentSync<C extends PipeComponent, T>(
    PipeSyncChannel<T> channel,
    Function<? super C, ? extends T> snapshot,
    BiConsumer<? super C, ? super T> apply,
    Policy policy
) {
    public enum Policy { ON_REQUEST, WHEN_CHANGED }

    public PipeComponentSync(PipeSyncChannel<T> channel,
        Function<? super C, ? extends T> snapshot, BiConsumer<? super C, ? super T> apply) {
        this(channel, snapshot, apply, Policy.ON_REQUEST);
    }

    public PipeComponentSync {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(policy, "policy");
    }
}
