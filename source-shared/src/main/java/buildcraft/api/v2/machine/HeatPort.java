package buildcraft.api.v2.machine;

import buildcraft.api.v2.OperationMode;

/** Read/write heat endpoint replacing the legacy IHeatable capability. */
public interface HeatPort {
    HeatState heatState();
    HeatProfile profile();
    /** Read-only engines can publish their thermal state without exposing an artificial heat setter. */
    default boolean canSetTemperature() { return true; }

    /** Requests an absolute temperature and returns the resulting state. */
    HeatState setTemperature(double temperature, OperationMode mode);
}
