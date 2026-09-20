package buildcraft.builders.internal.schematic.api2;

import com.mojang.authlib.GameProfile;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.registry.RegistryEntry;
import buildcraft.api.v2.schematic.EntitySchematicAdapter;
import buildcraft.api.v2.schematic.SchematicAdapter;
import buildcraft.api.v2.schematic.SchematicCaptureContext;
import buildcraft.api.v2.schematic.SchematicEntityCaptureContext;
import buildcraft.api.v2.schematic.SchematicPlacementContext;
import buildcraft.api.v2.schematic.SchematicResult;
import buildcraft.api.v2.schematic.SchematicService;
import buildcraft.api.v2.schematic.SnapshotElement;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.builders.snapshot.SchematicEntityManager;
import buildcraft.lib.misc.FakePlayerProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Builder-owned runtime backend for the public API2 schematic service. */
public final class SchematicServiceImpl implements SchematicService {
    public static final SchematicServiceImpl INSTANCE = new SchematicServiceImpl();

    private SchematicServiceImpl() {}

    @Override
    public Optional<? extends SnapshotElement> capture(SchematicCaptureContext context) {
        for (SchematicAdapter adapter : blockAdapters()) {
            if (!adapter.supports(context)) continue;
            Optional<? extends SnapshotElement> captured = adapter.capture(context);
            if (captured.isPresent()) {
                requireRegisteredType(captured.get());
                return captured;
            }
        }
        return Optional.of(SchematicBlockManager.getLegacySchematicBlock(new SchematicBlockContext(
            context.level(), context.origin(), context.position(), context.state(), context.state().getBlock()
        )));
    }

    @Override
    public Optional<? extends SnapshotElement> captureEntity(SchematicEntityCaptureContext context) {
        for (EntitySchematicAdapter adapter : entityAdapters()) {
            if (!adapter.supports(context)) continue;
            Optional<? extends SnapshotElement> captured = adapter.capture(context);
            if (captured.isPresent()) {
                requireRegisteredType(captured.get());
                return captured;
            }
        }
        return Optional.ofNullable(SchematicEntityManager.getLegacySchematicEntity(new SchematicEntityContext(
            context.level(), context.origin(), context.entity()
        )));
    }

    @Override
    public SchematicResult place(SnapshotElement element, SchematicPlacementContext context) {
        if (element instanceof ISchematicBlock block) {
            boolean success = context.mode().isSimulation()
                ? block.canBuild(context.level(), context.position())
                : block.build(context.level(), context.position(), automationPlayer(context));
            return result(success, "block");
        }
        if (element instanceof ISchematicEntity entity) {
            if (context.mode().isSimulation()) return new SchematicResult(SchematicResult.Status.SUCCESS, "entity placement can execute");
            return result(entity.build(context.level(), context.origin()) != null, "entity");
        }
        SchematicAdapter blockAdapter = findBlockAdapter(element).orElse(null);
        if (blockAdapter != null) return blockAdapter.place(element, context);
        EntitySchematicAdapter entityAdapter = findEntityAdapter(element).orElse(null);
        if (entityAdapter != null) return entityAdapter.place(element, context);
        return new SchematicResult(SchematicResult.Status.FAILED, "No schematic adapter for " + element.typeId());
    }

    public Optional<SchematicAdapter> findBlockAdapter(SnapshotElement element) {
        return blockAdapters().stream().filter(adapter -> adapter.supportsElement(element)).findFirst();
    }

    public Optional<EntitySchematicAdapter> findEntityAdapter(SnapshotElement element) {
        return entityAdapters().stream().filter(adapter -> adapter.supportsElement(element)).findFirst();
    }

    private static List<SchematicAdapter> blockAdapters() {
        List<RegistryEntry<SchematicAdapter>> entries = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ADAPTERS).entries());
        entries.sort(Comparator.<RegistryEntry<SchematicAdapter>>comparingInt(entry -> entry.value().priority()).reversed()
            .thenComparing(entry -> entry.id().toString()));
        List<SchematicAdapter> result = new ArrayList<>(entries.size());
        for (RegistryEntry<SchematicAdapter> entry : entries) result.add(entry.value());
        return result;
    }

    private static List<EntitySchematicAdapter> entityAdapters() {
        List<RegistryEntry<EntitySchematicAdapter>> entries = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.SCHEMATIC_ENTITY_ADAPTERS).entries());
        entries.sort(Comparator.<RegistryEntry<EntitySchematicAdapter>>comparingInt(entry -> entry.value().priority()).reversed()
            .thenComparing(entry -> entry.id().toString()));
        List<EntitySchematicAdapter> result = new ArrayList<>(entries.size());
        for (RegistryEntry<EntitySchematicAdapter> entry : entries) result.add(entry.value());
        return result;
    }

    private static void requireRegisteredType(SnapshotElement element) {
        if (BuildCraftApi.registry(BuildCraftRegistries.SNAPSHOT_ELEMENT_TYPES).get(element.typeId()) == null) {
            throw new IllegalStateException("Schematic adapter captured unregistered SnapshotElementType " + element.typeId());
        }
    }

    private static Player automationPlayer(SchematicPlacementContext context) {
        if (!(context.level() instanceof ServerLevel serverLevel)) return null;
        GameProfile profile = context.actor().playerId()
            .map(id -> new GameProfile(id, context.actor().playerName().orElse("[BuildCraft]")))
            .orElse(FakePlayerProvider.NULL_PROFILE);
        return FakePlayerProvider.INSTANCE.getFakePlayer(serverLevel, profile, context.position());
    }

    private static SchematicResult result(boolean success, String what) {
        return new SchematicResult(success ? SchematicResult.Status.SUCCESS : SchematicResult.Status.FAILED,
            success ? what + " placed" : what + " placement failed");
    }
}
