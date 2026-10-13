package buildcraft.api.v2.event;

public enum WorldEventDecision {
    PASS, ALLOW, DENY;
    public WorldEventDecision merge(WorldEventDecision next) {
        if (this == DENY || next == DENY) return DENY;
        return this == ALLOW || next == ALLOW ? ALLOW : PASS;
    }
}
