package buildcraft.api.v2.statement;

@FunctionalInterface
public interface StatementContributor {
    void contribute(StatementContext context, StatementCollector collector);

    /**
     * Declares where a contributed statement can be installed. Override for sided or
     * neighbour-facing statements; existing contributors remain internal by default.
     * Return INTERNAL for IDs that this contributor does not own.
     */
    default StatementScope scope(net.minecraft.resources.ResourceLocation statementId) {
        return StatementScope.INTERNAL;
    }
}
