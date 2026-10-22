package buildcraft.lib.internal.enums;

import java.util.Locale;

import net.minecraft.util.StringRepresentable;


public enum EnumPowerStage implements StringRepresentable {
    BLUE,
    GREEN,
    YELLOW,
    RED,
    OVERHEAT,
    BLACK;

    public static final EnumPowerStage[] VALUES = values();

    private final String modelName = name().toLowerCase(Locale.ROOT);

    public String getModelName() {
        return modelName;
    }

	public String getSerializedName() {
		return getModelName();
	}
}
