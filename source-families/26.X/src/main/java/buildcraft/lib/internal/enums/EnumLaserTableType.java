package buildcraft.lib.internal.enums;

import net.minecraft.util.StringRepresentable;

public enum EnumLaserTableType implements StringRepresentable {
    ASSEMBLY_TABLE,
    ADVANCED_CRAFTING_TABLE,
    INTEGRATION_TABLE,
    CHARGING_TABLE,
    PROGRAMMING_TABLE;

    public String getSerializedName() {
        return name();
    }
}
