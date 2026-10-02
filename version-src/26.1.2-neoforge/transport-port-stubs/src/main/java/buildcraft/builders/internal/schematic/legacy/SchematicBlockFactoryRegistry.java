package buildcraft.builders.internal.schematic.legacy;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Compile-only builders bridge; excluded from the production artifact. */
public final class SchematicBlockFactoryRegistry {
    private SchematicBlockFactoryRegistry() {}

    public static <S extends ISchematicBlock> void registerFactory(
        String name, int priority, Predicate<SchematicBlockContext> predicate, Supplier<S> supplier
    ) {
    }
}
