package buildcraft.api.v2.pipe;

/** Item transit observer and optional routing/claim/transfer controller. */
public interface ItemTransitComponent extends PipeComponent {
    void onTransit(ItemTransitContext context);
}
