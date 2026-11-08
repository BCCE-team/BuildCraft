package buildcraft.api.v2.fuels;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import java.util.Objects;
import java.util.Optional;

/** Immutable combustion-fuel definition. */
public final class FuelProfile implements EnergyFluidDefinition {
    public static final ApiCodec<FuelProfile, FuelProfileData> CODEC = new ApiCodec<>() {
        @Override
        public CodecResult<FuelProfile> decode(FuelProfileData data) {
            if (data == null) return CodecResult.failure("Fuel profile payload is null");
            CodecResult<FluidSelector> selector = FluidSelector.CODEC.decode(data.selector());
            if (!selector.successful()) return CodecResult.failure(String.join("; ", selector.errors()));
            try {
                FluidVolume residue = FluidVolume.empty();
                if (data.residueVariant() != null || data.residueMilliBuckets() != 0) {
                    if (data.residueVariant() == null) return CodecResult.failure("Fuel residue amount exists without residue variant");
                    FluidVariant variant = FluidVariant.CODEC.decode(data.residueVariant()).valueOrThrow();
                    residue = FluidVolume.of(variant, FluidAmount.of(data.residueMilliBuckets()));
                    if (residue.isEmpty()) return CodecResult.failure("Fuel residue must be non-empty when specified");
                }
                long heat = data.heatPerTickMicroMj() == null
                    ? data.powerPerTickMicroMj() : data.heatPerTickMicroMj();
                return CodecResult.success(new FuelProfile(
                    selector.valueOrThrow(), data.powerPerTickMicroMj(), data.burnTicksPerBucket(), heat, residue
                ));
            } catch (RuntimeException ex) {
                return CodecResult.failure(safeMessage(ex));
            }
        }

        @Override
        public CodecResult<FuelProfileData> encode(FuelProfile value) {
            if (value == null) return CodecResult.failure("Fuel profile is null");
            if (!(value.matcher instanceof FluidSelector selector)) {
                return CodecResult.failure("Programmatic fuel matcher is not data-serializable");
            }
            CodecResult<FluidSelectorData> selectorData = FluidSelector.CODEC.encode(selector);
            if (!selectorData.successful()) return CodecResult.failure(String.join("; ", selectorData.errors()));
            if (value.residuePerBucket.isEmpty()) {
                return CodecResult.success(new FuelProfileData(
                    selectorData.valueOrThrow(), value.powerPerTickMicroMj, value.burnTicksPerBucket, null, 0,
                    value.heatPerTickMicroMj
                ));
            }
            FluidVolume residue = value.residuePerBucket;
            CodecResult<buildcraft.api.v2.fluid.FluidVariantData> encodedVariant =
                FluidVariant.CODEC.encode(residue.requireVariant());
            if (!encodedVariant.successful()) return CodecResult.failure(String.join("; ", encodedVariant.errors()));
            return CodecResult.success(new FuelProfileData(
                selectorData.valueOrThrow(), value.powerPerTickMicroMj, value.burnTicksPerBucket,
                encodedVariant.valueOrThrow(), residue.amount().milliBuckets(), value.heatPerTickMicroMj
            ));
        }

        private String safeMessage(RuntimeException ex) {
            String message = ex.getMessage();
            return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
        }
    };

    private final FluidMatcher matcher;
    private final long powerPerTickMicroMj;
    private final long heatPerTickMicroMj;
    private final int burnTicksPerBucket;
    private final FluidVolume residuePerBucket;

    /** Legacy constructor: fuel thermal output used to match its MJ output. */
    public FuelProfile(FluidMatcher matcher, long powerPerTickMicroMj, int burnTicksPerBucket, FluidVolume residuePerBucket) {
        this(matcher, powerPerTickMicroMj, burnTicksPerBucket, powerPerTickMicroMj, residuePerBucket);
    }

    /** Separate energy generation from combustion heat, as in the BC8 fuel registry. */
    public FuelProfile(FluidMatcher matcher, long powerPerTickMicroMj, int burnTicksPerBucket,
            long heatPerTickMicroMj, FluidVolume residuePerBucket) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        if (powerPerTickMicroMj <= 0) throw new IllegalArgumentException("powerPerTickMicroMj must be > 0");
        if (heatPerTickMicroMj <= 0) throw new IllegalArgumentException("heatPerTickMicroMj must be > 0");
        if (burnTicksPerBucket <= 0) throw new IllegalArgumentException("burnTicksPerBucket must be > 0");
        this.powerPerTickMicroMj = powerPerTickMicroMj;
        this.heatPerTickMicroMj = heatPerTickMicroMj;
        this.burnTicksPerBucket = burnTicksPerBucket;
        this.residuePerBucket = Objects.requireNonNull(residuePerBucket, "residuePerBucket");
    }

    public static FuelProfile clean(FluidMatcher matcher, long powerPerTickMicroMj, int burnTicksPerBucket) {
        return clean(matcher, powerPerTickMicroMj, burnTicksPerBucket, powerPerTickMicroMj);
    }

    public static FuelProfile clean(FluidMatcher matcher, long powerPerTickMicroMj,
            int burnTicksPerBucket, long heatPerTickMicroMj) {
        return new FuelProfile(matcher, powerPerTickMicroMj, burnTicksPerBucket, heatPerTickMicroMj, FluidVolume.empty());
    }

    public static FuelProfile dirty(
        FluidMatcher matcher, long powerPerTickMicroMj, int burnTicksPerBucket, FluidVolume residuePerBucket
    ) {
        return dirty(matcher, powerPerTickMicroMj, burnTicksPerBucket, powerPerTickMicroMj, residuePerBucket);
    }

    public static FuelProfile dirty(FluidMatcher matcher, long powerPerTickMicroMj,
            int burnTicksPerBucket, long heatPerTickMicroMj, FluidVolume residuePerBucket) {
        if (Objects.requireNonNull(residuePerBucket, "residuePerBucket").isEmpty()) {
            throw new IllegalArgumentException("Dirty fuel residue must not be empty");
        }
        return new FuelProfile(matcher, powerPerTickMicroMj, burnTicksPerBucket, heatPerTickMicroMj, residuePerBucket);
    }

    @Override
    public Kind kind() { return Kind.FUEL; }
    public FluidMatcher matcher() { return matcher; }
    public long powerPerTickMicroMj() { return powerPerTickMicroMj; }
    /** MJ-equivalent heat produced per tick, independent of generated mechanical energy. */
    public long heatPerTickMicroMj() { return heatPerTickMicroMj; }
    public int burnTicksPerBucket() { return burnTicksPerBucket; }
    public FluidVolume residuePerBucket() { return residuePerBucket; }
    public boolean hasResidue() { return !residuePerBucket.isEmpty(); }

    public Optional<FluidVariant> representativeVariant() {
        return matcher instanceof FluidSelector selector ? selector.representativeVariant() : Optional.empty();
    }
}
