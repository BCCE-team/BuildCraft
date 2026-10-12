package buildcraft.api.v2.pipe;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Immutable registered pipe definition. Runtime pipe objects remain implementation-private. */
public final class PipeType {
    private final ResourceLocation id;
    private final Set<PipeMedium> media;
    private final Set<ResourceLocation> defaultComponents;
    private final ItemTransportProfile itemProfile;
    private final FluidTransportProfile fluidProfile;
    private final PowerTransportProfile mjProfile;
    private final ExternalEnergyTransportProfile externalEnergyProfile;
    private final boolean colorable;
    private final ResourceLocation archetypeId;

    private PipeType(Builder b) {
        id = b.id;
        media = Collections.unmodifiableSet(new LinkedHashSet<>(b.media));
        defaultComponents = Collections.unmodifiableSet(new LinkedHashSet<>(b.defaultComponents));
        itemProfile = b.itemProfile;
        fluidProfile = b.fluidProfile;
        mjProfile = b.mjProfile;
        externalEnergyProfile = b.externalEnergyProfile;
        colorable = b.colorable;
        archetypeId = b.archetypeId;
    }

    public static Builder builder(ResourceLocation id) { return new Builder(id); }
    public static Builder variant(ResourceLocation id, PipeType base) { return new Builder(id).copyFrom(base).archetype(base.id()); }
    public Builder copyAs(ResourceLocation newId) { return variant(newId, this); }
    public ResourceLocation id() { return id; }
    public Set<PipeMedium> media() { return media; }
    public Set<ResourceLocation> defaultComponents() { return defaultComponents; }
    public Optional<ItemTransportProfile> itemProfile() { return Optional.ofNullable(itemProfile); }
    public Optional<FluidTransportProfile> fluidProfile() { return Optional.ofNullable(fluidProfile); }
    public Optional<PowerTransportProfile> mjProfile() { return Optional.ofNullable(mjProfile); }
    public Optional<ExternalEnergyTransportProfile> externalEnergyProfile() { return Optional.ofNullable(externalEnergyProfile); }
    public boolean colorable() { return colorable; }
    /** Runtime archetype whose stable behaviour/rendering is reused by this variant. */
    public Optional<ResourceLocation> archetypeId() { return Optional.ofNullable(archetypeId); }

    public static final class Builder {
        private final ResourceLocation id;
        private final Set<PipeMedium> media = new LinkedHashSet<>();
        private final Set<ResourceLocation> defaultComponents = new LinkedHashSet<>();
        private ItemTransportProfile itemProfile;
        private FluidTransportProfile fluidProfile;
        private PowerTransportProfile mjProfile;
        private ExternalEnergyTransportProfile externalEnergyProfile;
        private boolean colorable;
        private ResourceLocation archetypeId;

        private Builder(ResourceLocation id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder copyFrom(PipeType base) {
            Objects.requireNonNull(base, "base");
            media.clear();
            media.addAll(base.media);
            defaultComponents.clear();
            defaultComponents.addAll(base.defaultComponents);
            itemProfile = base.itemProfile;
            fluidProfile = base.fluidProfile;
            mjProfile = base.mjProfile;
            externalEnergyProfile = base.externalEnergyProfile;
            colorable = base.colorable;
            archetypeId = base.archetypeId;
            return this;
        }
        public Builder medium(PipeMedium medium) { media.add(Objects.requireNonNull(medium, "medium")); return this; }
        public Builder component(ResourceLocation componentId) { defaultComponents.add(Objects.requireNonNull(componentId, "componentId")); return this; }
        public Builder removeComponent(ResourceLocation componentId) { defaultComponents.remove(Objects.requireNonNull(componentId, "componentId")); return this; }
        public Builder clearComponents() { defaultComponents.clear(); return this; }
        public Builder itemProfile(ItemTransportProfile profile) { itemProfile = Objects.requireNonNull(profile, "profile"); return medium(PipeMedium.ITEM); }
        public Builder fluidProfile(FluidTransportProfile profile) { fluidProfile = Objects.requireNonNull(profile, "profile"); return medium(PipeMedium.FLUID); }
        public Builder mjProfile(PowerTransportProfile profile) { mjProfile = Objects.requireNonNull(profile, "profile"); return medium(PipeMedium.MJ); }
        public Builder externalEnergyProfile(ExternalEnergyTransportProfile profile) { externalEnergyProfile = Objects.requireNonNull(profile, "profile"); return medium(PipeMedium.EXTERNAL_ENERGY); }
        public Builder colorable(boolean value) { colorable = value; return this; }
        public Builder archetype(ResourceLocation value) { archetypeId = Objects.requireNonNull(value, "value"); return this; }
        public PipeType build() {
            if (media.isEmpty()) throw new IllegalStateException("Pipe type must declare at least one medium");
            if (media.contains(PipeMedium.ITEM) && itemProfile == null) {
                itemProfile = new ItemTransportProfile(64, 1);
            }
            return new PipeType(this);
        }
    }
}
