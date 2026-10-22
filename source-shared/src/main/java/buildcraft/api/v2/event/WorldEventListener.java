package buildcraft.api.v2.event;

/** Third-party callbacks are invoked in priority order on the server thread. */
@FunctionalInterface
public interface WorldEventListener {
    WorldEventDecision onWorldEvent(WorldEventContext event);
}
