package buildcraft.api.v2.pipe;

/** Typed hook for stable transit metadata such as speed and colour. */
public interface ItemTransitModifierComponent extends PipeComponent {
    default ItemTransitData modifyTransit(ItemTransitContext context, ItemTransitData current) {
        return current;
    }
}
