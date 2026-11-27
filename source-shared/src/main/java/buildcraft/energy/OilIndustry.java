package buildcraft.energy;

/** World-restart-bound petroleum policy. IDs stay registered in both modes. */
public final class OilIndustry {
    public enum Mode { MODERN, CLASSIC }
    private static volatile Mode serverMode = Mode.MODERN;
    private static volatile Mode remoteMode;
    private static volatile boolean serverRunning;
    private OilIndustry() {}

    public static void setServerMode(Mode mode) { serverMode = mode; }
    public static void setServerRunning(boolean running) { serverRunning = running; }
    public static void setRemoteMode(Mode mode) { remoteMode = mode; }
    public static Mode mode() { return !serverRunning && remoteMode != null ? remoteMode : serverMode; }
    public static Mode serverMode() { return serverMode; }
    public static boolean classic() { return mode() == Mode.CLASSIC; }

    public static boolean recipeEnabled(String namespace, String path) {
        return recipeEnabled(mode(), namespace, path);
    }
    public static boolean recipeEnabled(Mode mode, String namespace, String path) {
        if (!"buildcraftfactory".equals(namespace)) return true;
        if (path.equals("distiller/classic_oil") || path.equals("distiller/classic_oil_heat_1")
            || path.equals("distiller/classic_oil_heat_2")) return mode == Mode.CLASSIC;
        if (path.startsWith("distiller/buildcraftenergy/")
            || (path.startsWith("heat_exchanger/") && path.contains("/buildcraftenergy/"))) {
            return mode == Mode.MODERN;
        }
        return true;
    }
    /** Diagnostics must consider every possible mode, not just the mode at datapack reload time. */
    public static boolean recipesCanOverlap(String firstNamespace, String firstPath,
        String secondNamespace, String secondPath) {
        for (Mode mode : Mode.values()) {
            if (recipeEnabled(mode, firstNamespace, firstPath)
                && recipeEnabled(mode, secondNamespace, secondPath)) return true;
        }
        return false;
    }
    public static boolean fuelEnabled(String namespace, String path) {
        return fuelEnabled(mode(), namespace, path);
    }
    public static boolean fuelEnabled(Mode mode, String namespace, String path) {
        if (!"buildcraftenergy".equals(namespace)) return true;
        if (path.startsWith("fuel/classic/")) return mode == Mode.CLASSIC;
        if (path.startsWith("fuel/buildcraftenergy/")) return mode == Mode.MODERN;
        return true;
    }
    public static boolean bucketEnabled(String namespace, String path) {
        if (!classic() || !"buildcraftenergy".equals(namespace) || !path.endsWith("_bucket")) return true;
        return path.equals("oil/cool_bucket") || path.equals("fuel_dense/cool_bucket");
    }
    /** True for the built-in petroleum family, including legacy temperature variants. */
    public static boolean isPetroleum(String namespace, String path) {
        if (!"buildcraftenergy".equals(namespace)) return false;
        String base = path.endsWith("_flowing") ? path.substring(0, path.length() - 8) : path;
        if (base.equals("spout_oil")) return true;
        for (String name : new String[]{"oil", "oil_residue", "oil_heavy", "oil_dense", "oil_distilled",
            "fuel_dense", "fuel_mixed_heavy", "fuel_light", "fuel_mixed_light", "fuel_gaseous"}) {
            if (base.equals(name) || base.equals(name + "_heat_1") || base.equals(name + "_heat_2")) return true;
        }
        return false;
    }

    /** Hide only the built-in petroleum family, not unrelated add-on fluids. */
    public static boolean fluidEnabled(String namespace, String path) {
        if (!classic() || !"buildcraftenergy".equals(namespace)) return true;
        String base = path.endsWith("_flowing") ? path.substring(0, path.length() - 8) : path;
        if (base.equals("oil") || base.equals("fuel_dense") || base.equals("spout_oil")) return true;
        for (String name : new String[]{"oil", "oil_residue", "oil_heavy", "oil_dense", "oil_distilled",
            "fuel_dense", "fuel_mixed_heavy", "fuel_light", "fuel_mixed_light", "fuel_gaseous"}) {
            if (base.equals(name) || base.startsWith(name + "_heat_")) return false;
        }
        return true;
    }
    public static boolean bucketEnabled(int index) {
        return !classic() || index == 0 || index == 15;
    }
    /** Display names only: never rename or alias the registered fluid IDs. */
    public static String classicFluidName(String namespace, String path) {
        if (!classic() || !"buildcraftenergy".equals(namespace)) return null;
        return switch (path) {
            case "oil", "oil_flowing", "spout_oil", "spout_oil_flowing" -> "fluid_type.buildcraftenergy.classic.oil";
            case "fuel_dense", "fuel_dense_flowing" -> "fluid_type.buildcraftenergy.classic.fuel";
            default -> null;
        };
    }

    /** FluidType uses the same lookup for the bare type and the stack-aware name. */
    public static String fluidDescriptionId(String normalKey) {
        String prefix = "fluid_type.buildcraftenergy.";
        if (!normalKey.startsWith(prefix)) return normalKey;
        String classicName = classicFluidName("buildcraftenergy", normalKey.substring(prefix.length()));
        return classicName == null ? normalKey : classicName;
    }

    public static String classicBucketName(String fluid, int heat) {
        if (!classic() || heat != 0) return null;
        return switch (fluid) {
            case "oil" -> "item.buildcraftenergy.classic.oil_bucket";
            case "fuel_dense" -> "item.buildcraftenergy.classic.fuel_bucket";
            default -> null;
        };
    }
}
