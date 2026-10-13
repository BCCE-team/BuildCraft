package buildcraft.api.v2.pipe;

/** Optional native-fluid callback. A listener cannot introduce fluid, change its variant or exceed the offer. */
public interface FluidMovementComponent extends PipeComponent {
    /** Maximum amount permitted for this movement; zero denies it. No mutation is allowed in SIMULATE. */
    int limitFluidMovement(FluidMovementContext context);
}
