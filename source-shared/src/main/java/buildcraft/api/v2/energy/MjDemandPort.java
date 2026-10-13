package buildcraft.api.v2.energy;

/** Optional live, server-side MJ demand query; never call this while discovering client-side topology. */
public interface MjDemandPort extends MjPort {
    /** Current amount the consumer is prepared to accept, expressed in micro-MJ. */
    MjAmount requested();
}
