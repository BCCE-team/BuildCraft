package buildcraft.api.v2.pipe;

import java.util.Objects;

/** An insertion or claim result. Only EXECUTE consumes the source item. */
public record ItemTransitTransfer(int offered, int accepted, ItemTransitStatus status) {
    public ItemTransitTransfer {
        if (offered < 0 || accepted < 0 || accepted > offered) {
            throw new IllegalArgumentException("Invalid item transfer counts");
        }
        Objects.requireNonNull(status, "status");
    }

    public static ItemTransitTransfer blocked(int offered, ItemTransitStatus status) {
        return new ItemTransitTransfer(offered, 0, status);
    }

    public int remainder() { return offered - accepted; }
    public boolean succeeded() { return accepted > 0; }
}
