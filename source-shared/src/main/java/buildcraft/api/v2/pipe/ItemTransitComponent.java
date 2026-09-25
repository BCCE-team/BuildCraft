package buildcraft.api.v2.pipe;

/** Typed hook invoked when an item is about to enter a pipe. */
public interface ItemTransitComponent extends PipeComponent {
    default ItemTransitDecision onEnter(ItemTransitContext context) {
        return ItemTransitDecision.pass();
    }
}
