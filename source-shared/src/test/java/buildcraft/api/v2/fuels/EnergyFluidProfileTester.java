package buildcraft.api.v2.fuels;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnergyFluidProfileTester {
    @Test
    public void fuelCodecRoundTripsCleanAndDirtyProfiles() {
        FuelProfile clean = FuelProfile.clean(FluidSelector.fluid(id("fuel")), 4_000_000L, 12_000);
        FuelProfile decodedClean = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(clean).valueOrThrow()).valueOrThrow();
        assertEquals(clean.powerPerTickMicroMj(), decodedClean.powerPerTickMicroMj());
        assertEquals(clean.powerPerTickMicroMj(), decodedClean.heatPerTickMicroMj());
        assertEquals(clean.burnTicksPerBucket(), decodedClean.burnTicksPerBucket());
        assertFalse(decodedClean.hasResidue());

        FuelProfile dirty = FuelProfile.dirty(
            FluidSelector.tag(id("heavy_oil")),
            2_000_000L,
            8_000,
            FluidVolume.of(FluidVariant.of(id("residue")), FluidAmount.of(250))
        );
        FuelProfile decodedDirty = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(dirty).valueOrThrow()).valueOrThrow();
        assertTrue(decodedDirty.hasResidue());
        assertEquals(250, decodedDirty.residuePerBucket().amount().milliBuckets());
        assertEquals(id("residue"), decodedDirty.residuePerBucket().requireVariant().fluidId());
    }

    @Test
    public void temperatureSensitiveFuelPreservesSeparateHeatingAcrossCodec() {
        FuelProfile hot = FuelProfile.clean(FluidSelector.fluid(id("hot_fuel")), 5_000_000L, 12_000, 6_000_000L);
        FuelProfile restoredHot = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(hot).valueOrThrow()).valueOrThrow();
        assertEquals(5_000_000L, restoredHot.powerPerTickMicroMj());
        assertEquals(6_000_000L, restoredHot.heatPerTickMicroMj());
        assertEquals(12_000, restoredHot.burnTicksPerBucket());

        FluidVolume residue = FluidVolume.of(FluidVariant.of(id("residue")), FluidAmount.of(250));
        FuelProfile searing = FuelProfile.dirty(FluidSelector.fluid(id("searing_fuel")),
            6_000_000L, 12_000, 8_000_000L, residue);
        FuelProfile restoredSearing = FuelProfile.CODEC.decode(FuelProfile.CODEC.encode(searing).valueOrThrow()).valueOrThrow();
        assertEquals(6_000_000L, restoredSearing.powerPerTickMicroMj());
        assertEquals(8_000_000L, restoredSearing.heatPerTickMicroMj());
        assertEquals(residue, restoredSearing.residuePerBucket());

        // Existing API users and older serialized definitions must retain heat == power by default.
        FuelProfile oldData = FuelProfile.CODEC.decode(new FuelProfileData(
            FuelProfile.CODEC.encode(hot).valueOrThrow().selector(), 5_000_000L, 12_000, null, 0
        )).valueOrThrow();
        assertEquals(oldData.powerPerTickMicroMj(), oldData.heatPerTickMicroMj());
        FuelProfile oldAddon = FuelProfile.clean(FluidSelector.fluid(id("legacy")), 3_000_000L, 12_000);
        assertEquals(3_000_000L, oldAddon.heatPerTickMicroMj());
    }

    @Test
    public void coolantCodecRoundTripsConstantRule() {
        CoolantProfile profile = CoolantProfile.constant(FluidSelector.fluid(id("water")), 0.0023);
        CoolantProfile decoded = CoolantProfile.CODEC.decode(CoolantProfile.CODEC.encode(profile).valueOrThrow()).valueOrThrow();
        assertEquals(0.0023, decoded.degreesPerMilliBucket(FluidVariant.of(id("water")), 900), 0.0000001);
    }

    @Test
    public void programmaticProfilesRefuseLossyDataEncoding() {
        FuelProfile custom = FuelProfile.clean((variant, context) -> true, 1, 1);
        assertFalse(FuelProfile.CODEC.encode(custom).successful());
        CoolantProfile dynamic = new CoolantProfile((variant, context) -> true, (variant, heat) -> heat / 100.0);
        assertFalse(CoolantProfile.CODEC.encode(dynamic).successful());
    }

    private static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryParse("test:" + path));
    }
}
