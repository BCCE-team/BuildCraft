package buildcraft.lib.internal.mj;


/** Designates a machine that provides power passively- it does not attempt to manually output its power (like an
 * engine). Power can be extracted from this by powered wooden kinesis pipes, for example. */
public interface IMjPassiveProvider extends IMjConnector {
    /** Attempts to extract power from this provider.
     *
     * @param doExtract false to query the available amount without changing the provider, true to remove the returned
     *                  amount from it
     * @return Either 0, min, max, or a value between min and max. */
    long extractPower(long min, long max, boolean doExtract);
}
