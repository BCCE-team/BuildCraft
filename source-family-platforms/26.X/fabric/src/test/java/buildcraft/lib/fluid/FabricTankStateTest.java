package buildcraft.lib.fluid;

import static org.junit.jupiter.api.Assertions.*;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.misc.ItemStackUtil;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Gameplay contract regression tests against the NeoForge 26.3 tank semantics. */
public final class FabricTankStateTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        // A plain JUnit JVM does not run Minecraft's bootstrap automatically.
        // FabricTankState/FluidCompat accesses BuiltInRegistries.FLUID and
        // would otherwise fail with "Not bootstrapped" on the first test.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Identifier WATER = Identifier.fromNamespaceAndPath("minecraft", "water");
    private static final Identifier PAYLOAD_FORMAT =
        Identifier.fromNamespaceAndPath("buildcraftlib", "test_fluid_components");

    private static FluidVolume water(long amount) {
        return FluidVolume.of(FluidVariant.of(WATER), FluidAmount.of(amount));
    }
    private static FluidVolume decorated(int marker, long amount) {
        return FluidVolume.of(FluidVariant.of(WATER,
            FluidComponentPayload.of(PAYLOAD_FORMAT, new byte[] {(byte) marker})),
            FluidAmount.of(amount));
    }

    @Test
    void simulationAndCapacityNeverMutateStorage() {
        AtomicInteger notifications = new AtomicInteger();
        FabricTankState tank = new FabricTankState("test", 1000, null, notifications::incrementAndGet);
        assertEquals(1000, tank.fill(water(1250), true));
        assertEquals(0, tank.getFluidAmount());
        assertEquals(0, notifications.get());
        assertEquals(1000, tank.fill(water(1250), false));
        assertEquals(1000, tank.getFluidAmount());
        assertEquals(1, notifications.get());
        assertEquals(600, tank.drain(600, true).amount().milliBuckets());
        assertEquals(1000, tank.getFluidAmount());
        assertEquals(600, tank.drain(600, false).amount().milliBuckets());
        assertEquals(400, tank.getFluidAmount());
        assertEquals(2, notifications.get());
    }

    @Test
    void canonicalFluidAliasesPreserveComponentsAndTankIdentity() {
        Identifier canonicalId = Identifier.fromNamespaceAndPath("minecraft", "water");
        Identifier aliasId = Identifier.fromNamespaceAndPath("minecraft", "lava");
        TagKey<net.minecraft.world.level.material.Fluid> group =
            TagKey.create(Registries.FLUID, Identifier.fromNamespaceAndPath("test", "fluid_equivalence"));
        FabricFluidCompatRegistry.registerCanonical(
            group, Fluids.WATER, aliasId
        );

        FluidComponentPayload payload = FluidComponentPayload.of(PAYLOAD_FORMAT, new byte[] {7, 3, 1});
        buildcraft.api.v2.fluid.FluidVariant alias =
            buildcraft.api.v2.fluid.FluidVariant.of(aliasId, payload);
        FluidVolume source = FluidVolume.of(alias, FluidAmount.of(125));
        FluidVolume canonical = FabricFluidCompatRegistry.canonicalize(source);

        assertEquals(canonicalId, canonical.requireVariant().fluidId());
        assertEquals(payload, canonical.requireVariant().components());
        assertEquals(source.amount(), canonical.amount());
        assertTrue(FabricFluidCompatRegistry.areEquivalent(source, canonical));

        FabricTankState tank = new FabricTankState("canonical", 1000, null, () -> {});
        assertEquals(125, tank.fill(source, false));
        assertEquals(canonicalId, tank.getFluid().requireVariant().fluidId());
        assertEquals(payload, tank.getFluid().requireVariant().components());
    }

    @Test
    void variantsWithDifferentComponentsCannotMix() {
        FabricTankState tank = new FabricTankState("test", 1000, null, () -> {});
        assertEquals(300, tank.fill(decorated(1, 300), false));
        assertEquals(0, tank.fill(decorated(2, 200), false));
        assertEquals(0, tank.fill(water(200), false));
        assertTrue(tank.drain(decorated(2, 100), false).isEmpty());
        assertEquals(300, tank.getFluidAmount());
        assertEquals(100, tank.drain(decorated(1, 100), false).amount().milliBuckets());
        assertEquals(200, tank.getFluidAmount());
    }

    @Test
    void abortRestoresStateAndNeverDeliversDirtyNotifications() {
        AtomicInteger notifications = new AtomicInteger();
        FabricTankState tank = new FabricTankState("test", 1000, null, notifications::incrementAndGet);
        try (Transaction outer = Transaction.openOuter()) {
            assertEquals(400, tank.fill(water(400), false));
            assertEquals(400, tank.getFluidAmount());
        }
        assertEquals(0, tank.getFluidAmount());
        assertEquals(0, notifications.get());
    }

    @Test
    void nestedTransactionsNotifyOnlyAfterOutermostCommit() {
        AtomicInteger notifications = new AtomicInteger();
        FabricTankState tank = new FabricTankState("test", 1000, null, notifications::incrementAndGet);
        try (Transaction outer = Transaction.openOuter()) {
            try (Transaction nested = Transaction.openNested(outer)) {
                tank.fill(water(300), false);
                nested.commit();
            }
            tank.fill(water(100), false);
            assertEquals(0, notifications.get());
            outer.commit();
        }
        assertEquals(400, tank.getFluidAmount());
        assertEquals(1, notifications.get());
    }

    @Test
    void managerPreservesBuildCraftPriorityAndDoesNotMixFluids() {
        FabricTankState fillOnly = new FabricTankState("fill", 1000, null, () -> {});
        fillOnly.setCanDrain(false);
        FabricTankState dual = new FabricTankState("dual", 1000, null, () -> {});
        FabricTankManager manager = new FabricTankManager(dual, fillOnly);
        assertEquals(1500, manager.fill(water(1500), false));
        assertEquals(1000, fillOnly.getFluidAmount());
        assertEquals(500, dual.getFluidAmount());
        assertEquals(500, manager.drain(1000, false).amount().milliBuckets());
        assertEquals(1000, fillOnly.getFluidAmount());
        assertEquals(0, dual.getFluidAmount());
    }

    @Test
    void capacityChangesParticipateInTheSameTransactionJournal() {
        AtomicInteger notifications = new AtomicInteger();
        FabricTankState tank = new FabricTankState("test", 1000, null, notifications::incrementAndGet);
        tank.fill(water(800), false);
        assertEquals(1, notifications.get());
        try (Transaction outer = Transaction.openOuter()) {
            tank.setCapacity(300);
            assertEquals(300, tank.getCapacity());
            assertEquals(300, tank.getFluidAmount());
        }
        assertEquals(1000, tank.getCapacity());
        assertEquals(800, tank.getFluidAmount());
        assertEquals(1, notifications.get());
    }

    @Test
    void unknownPersistedFluidIsRetainedAcrossSaveAndBlocksOverwrites() {
        FabricTankState tank = new FabricTankState("test", 1000, null, () -> {});
        CompoundTag unknown = new CompoundTag();
        unknown.putString("id", "removed_mod:unknown_fluid");
        unknown.putInt("amount", 750);
        tank.readFromNBT(unknown, ItemStackUtil.requireActiveRegistryProvider());
        assertTrue(tank.hasDeferredUnknownFluid());
        assertEquals(0, tank.fill(water(500), false));
        assertEquals(unknown, tank.writeToNBT(ItemStackUtil.requireActiveRegistryProvider()));

        CompoundTag malformed = new CompoundTag();
        malformed.putString("provider_data", "must-survive");
        tank.readFromNBT(malformed, ItemStackUtil.requireActiveRegistryProvider());
        assertTrue(tank.hasDeferredUnknownFluid());
        assertEquals(malformed, tank.writeToNBT(ItemStackUtil.requireActiveRegistryProvider()));
    }
}
