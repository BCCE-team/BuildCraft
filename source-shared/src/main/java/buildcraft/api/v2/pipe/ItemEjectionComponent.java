package buildcraft.api.v2.pipe;

/** Typed hook invoked immediately before a travelling item is ejected into the world. */
public interface ItemEjectionComponent extends PipeComponent {
    default ItemEjectionDecision onEject(ItemEjectionContext context) {
        return ItemEjectionDecision.PASS;
    }
}
