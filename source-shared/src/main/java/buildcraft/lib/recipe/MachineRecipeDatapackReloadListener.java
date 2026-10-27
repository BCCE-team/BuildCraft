package buildcraft.lib.recipe;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.recipe.FluidIngredient;
import buildcraft.api.v2.recipe.HeatExchangeRecipeDefinition;
import buildcraft.api.v2.recipe.RecipeDefinition;
import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
import buildcraft.api.v2.recipe.StaticIntegrationRecipe;
import buildcraft.api.v2.recipe.IntegrationRecipeDefinition;
import buildcraft.api.v2.recipe.CountedIngredient;
import buildcraft.api.v2.recipe.AssemblyIngredients;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.lib.internal.api.v2.MachineRecipeRegistryImpl;
import buildcraft.lib.internal.api.v2.recipe.MachineRecipeRegistration;
import buildcraft.lib.internal.api.v2.recipe.MachineRecipeReloadResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.NonNullList;
//? if >=1.19.3 {
import net.minecraft.core.registries.Registries;
//?}
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data-pack loader for BuildCraft machine recipes and absolute, multi-pack deny rules.
 *
 * <p>The blacklist MUST NOT be loaded through SimpleJsonResourceReloadListener: that
 * listener resolves paths using pack priority and discards overridden, lower-layer bans.
 * We deliberately read every available resource stack for each blacklist path.</p>
 */
public final class MachineRecipeDatapackReloadListener
    extends SimplePreparableReloadListener<MachineRecipeDatapackReloadListener.Prepared> {

    private static final Logger LOG = LoggerFactory.getLogger("BCCE/Recipes");
    private static volatile boolean serverDatapacksReady;
    private static boolean bundledBootstrapped;
    /** Only the winning, validated JSON definitions are eligible for client display sync. */
    private static volatile List<buildcraft.lib.net.MessageMachineRecipes.Recipe> syncedRecipes = List.of();
    private static volatile boolean hasSyncedRecipeSnapshot;

    public static boolean hasSyncedRecipeSnapshot() { return hasSyncedRecipeSnapshot; }

    public static List<buildcraft.lib.net.MessageMachineRecipes.Recipe> syncedMachineRecipes() {
        return syncedRecipes;
    }

    private static final Set<String> MANAGED = Set.of("distiller", "heat_exchanger", "integration_table", "programming_table");
    /** Handler IDs are registered by mods during setup, never instantiated from user-controlled class names. */
    private static final Map<String, Function<JsonObject, ? extends RecipeDefinition>> HANDLERS =
        new ConcurrentHashMap<>();

    public static void registerHandler(String type, Function<JsonObject, ? extends RecipeDefinition> factory) {
        ResourceLocation parsed = ResourceLocation.tryParse(type);
        if (parsed == null || !parsed.toString().equals(type)) throw new IllegalArgumentException("Invalid handler ID " + type);
        if (HANDLERS.putIfAbsent(type, java.util.Objects.requireNonNull(factory, "factory")) != null) {
            throw new IllegalStateException("Duplicate machine recipe handler " + type);
        }
    }
    private static final Set<String> SUPPORTED = Set.of(
        "distiller", "heat_exchanger", "assembly_table", "integration_table",
        "programming_table", "auto_workbench", "advanced_crafting_table"
    );

    public record SourceFile(ResourceLocation location, String pack, JsonObject json) {}
    private record Signature(String machine, JsonElement data) {}
    public record Prepared(
        Map<String, Set<ResourceLocation>> blacklist,
        List<SourceFile> machineRecipes,
        List<String> warnings,
        List<String> errors
    ) {}

    /** Populate the client-side API catalogue from the exact same packaged JSON that
     * the server loads. The next authoritative server datapack reload replaces this
     * fallback snapshot. Custom remote datapack recipes require protocol sync. */
    public static synchronized void bootstrapBundled() {
        if (bundledBootstrapped || serverDatapacksReady) return;
        bundledBootstrapped = true;
        try (InputStream manifestStream = MachineRecipeDatapackReloadListener.class.getClassLoader()
                .getResourceAsStream("data/buildcraftfactory/bundled_machine_recipes.json")) {
            if (manifestStream == null) {
                LOG.warn("No packaged BuildCraft machine recipe manifest; built-ins will load from server datapacks");
                return;
            }
            JsonElement manifest;
            try (Reader input = new InputStreamReader(manifestStream, StandardCharsets.UTF_8)) {
                manifest = JsonParser.parseReader(input);
            }
            if (!manifest.isJsonArray()) throw new IllegalArgumentException("Machine recipe manifest is not an array");
            List<MachineRecipeRegistration> definitions = new ArrayList<>();
            for (JsonElement pathElement : manifest.getAsJsonArray()) {
                String resourcePath = "data/buildcraftfactory/" + pathElement.getAsString();
                try (InputStream data = MachineRecipeDatapackReloadListener.class.getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    if (data == null) throw new IllegalArgumentException("Missing packaged recipe " + resourcePath);
                    JsonObject json;
                    try (Reader reader = new InputStreamReader(data, StandardCharsets.UTF_8)) {
                        json = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    String recipeFile = pathElement.getAsString();
                    int firstSlash = recipeFile.indexOf('/');
                    String recipeKey = recipeFile.substring(firstSlash + 1, recipeFile.length() - 5);
                    ResourceLocation id = ResourceLocation.tryParse("buildcraftfactory:" + recipeKey);
                    if (id == null) throw new IllegalArgumentException("Invalid bundled recipe ID " + recipeFile);
                    String machine = recipeKey.substring(0, recipeKey.indexOf('/'));
                    definitions.add(new MachineRecipeRegistration(id, decode(machine, json),
                        new DefinitionProvenance("buildcraftfactory", "bundled-json", 0)));
                }
            }
            if (BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES) instanceof MachineRecipeRegistryImpl registry) {
                MachineRecipeReloadResult result = registry.reloadData(definitions);
                if (!result.published()) LOG.error("Could not publish packaged machine recipes: {}", result.diagnostics());
            }
        } catch (Exception failure) {
            LOG.error("Could not load bundled machine JSON recipes: {}", failure.toString());
        }
    }

    @Override
    protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Set<ResourceLocation>> deny = new LinkedHashMap<>();
        List<SourceFile> recipes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int platformVanillaOverlays = 0;
        try {
            // Read only the recipe directory that Minecraft actually loads for this target.
            // Treating both spellings as active caused duplicate IDs when packs ship
            // legacy and modern resources together (blacklist_recipes is universal).
            //? if <1.21 {
            for (String prefix : List.of("blacklist_recipes", "recipes")) {
            //?} else {
            /*?
            for (String prefix : List.of("blacklist_recipes", "recipe")) {
            ?*/
            //?}
                Map<ResourceLocation, List<Object>> files = stackedResources(manager, prefix);
                for (var entry : files.entrySet()) {
                    ResourceLocation fileId = entry.getKey();
                    String path = fileId.getPath();
                    if (!path.startsWith(prefix + "/") || !path.endsWith(".json")) continue;
                    if (!prefix.equals("blacklist_recipes") && entry.getValue().size() > 1) {
                        // NeoForge's own "mod/neoforge" layer mirrors many vanilla
                        // recipe resources. This normal platform overlay is not a
                        // modpack recipe conflict; don't drown real BCCE duplicates
                        // in hundreds of unhelpful warnings.
                        if (isBuiltinNeoForgeOverlay(fileId, entry.getValue())) {
                            platformVanillaOverlays++;
                        } else {
                            warnings.add("Duplicate recipe ID " + fileId + " in " + sources(entry.getValue())
                                + "; higher-priority datapack wins (blacklists still override both)");
                        }
                    }
                    String suffix = path.substring(prefix.length() + 1, path.length() - 5);
                    int separator = suffix.indexOf('/');
                    if (separator <= 0 || separator == suffix.length() - 1) {
                        if (prefix.equals("blacklist_recipes")) errors.add("Invalid blacklist path: " + fileId);
                        continue;
                    }
                    String machine = suffix.substring(0, separator);
                    if (prefix.equals("blacklist_recipes")) {
                        if (!SUPPORTED.contains(machine)) {
                            errors.add("Unknown blacklist machine '" + machine + "' at " + fileId);
                            continue;
                        }
                        // Every layer is parsed, even if a higher-priority pack replaces this path.
                        for (Object resource : entry.getValue()) {
                            String source = packName(resource);
                            try {
                                JsonObject json = readJson(resource);
                                if (!json.has("recipe") && (json.has("enabled") || json.has("allow") || json.has("action"))) {
                                    warnings.add("Ignoring attempted allow-only override at " + fileId + " from " + source
                                        + "; lower-pack deny rules still apply");
                                    continue;
                                }
                                ResourceLocation id = requireId(json, "recipe");
                                // A deny wins even when a pack also contains an attempted allow.
                                // These flags are ignored, never interpreted as a re-enable.
                                if (json.has("enabled") || json.has("action") || json.has("allow")) {
                                    warnings.add("Allow/enable directives are ignored in deny-only blacklist "
                                        + fileId + " from " + source + "; recipe " + id + " stays denied");
                                }
                                deny.computeIfAbsent(machine, ignored -> new LinkedHashSet<>()).add(id);
                            } catch (Exception invalid) {
                                errors.add("Invalid blacklist " + fileId + " from " + source + ": " + invalid.getMessage());
                            }
                        }
                    } else {
                        // Native Minecraft recipes remain in RecipeManager, but are audited here too.
                        // Audit BCCE native recipes, including legacy assembly/ and special/ folders.
                        // All namespace recipes above are checked for duplicate IDs, even if unrecognized.
                        if (!SUPPORTED.contains(machine) && !fileId.getNamespace().startsWith("buildcraft")
                            && !Set.of("assembly", "special", "plug_gate_create").contains(machine)) continue;
                        // Only the highest-priority recipe of each resource path is loaded.
                        Object winningResource = winningResource(manager, fileId, entry.getValue());
                        try {
                            recipes.add(new SourceFile(fileId, packName(winningResource), readJson(winningResource)));
                        } catch (Exception invalid) {
                            errors.add("Invalid recipe " + fileId + " from " + packName(winningResource) + ": " + invalid.getMessage());
                        }
                    }
                }
            }
            // Duplicate-content detection for all recognizable station recipes,
            // including native assembly recipes. Dynamic types may require runtime diagnostics.
            Map<Signature, SourceFile> identical = new HashMap<>();
            Map<ProgrammingRecipeDefinition, SourceFile> identicalProgramming = new HashMap<>();
            Map<Signature, SourceFile> overlapping = new HashMap<>();
            for (SourceFile file : recipes) {
                String machine = folder(file.location());
                if ("programming_table".equals(machine)) {
                    // Use decoded/effective values, not only the raw JSON spelling.
                    // Same board but different micro-MJ is a valid alternative.
                    try {
                        ProgrammingRecipeDefinition value = new ProgrammingRecipeDefinition(
                            requireString(file.json(), "board_key"), energy(file.json()));
                        SourceFile first = identicalProgramming.putIfAbsent(value, file);
                        if (first != null && !first.location().equals(file.location())) {
                            warnings.add("Duplicate programming recipe content at " + first.location()
                                + " (" + first.pack() + ") and " + file.location() + " (" + file.pack() + ")");
                        }
                    } catch (RuntimeException ignored) {
                        // Invalid definitions receive their detailed error during decode/apply.
                    }
                } else {
                    SourceFile first = identical.putIfAbsent(new Signature(machine, file.json()), file);
                    if (first != null && !first.location().equals(file.location())) {
                        warnings.add("Duplicate recipe content at " + first.location() + " (" + first.pack()
                            + ") and " + file.location() + " (" + file.pack() + ")");
                    }
                }
                if (MANAGED.contains(machine) && file.json().has("input")) {
                    JsonElement mode = file.json().get("mode");
                    Signature key = new Signature(machine, new com.google.gson.JsonArray());
                    com.google.gson.JsonArray inputSignature = new com.google.gson.JsonArray();
                    inputSignature.add(file.json().get("input"));
                    if (mode != null) inputSignature.add(mode);
                    key = new Signature(machine, inputSignature);
                    SourceFile competing = overlapping.putIfAbsent(key, file);
                    if (competing != null && !competing.location().equals(file.location())) {
                        warnings.add("Overlapping machine recipe input at " + competing.location()
                            + " and " + file.location() + "; the selected recipe is determined by priority");
                    }
                }
            }
        } catch (Exception fatal) {
            errors.add("Cannot enumerate datapack recipe/blacklist layers: " + fatal.getMessage());
        }
        if (platformVanillaOverlays > 0) {
            LOG.debug("Ignored {} ordinary vanilla/NeoForge recipe resource mirrors", platformVanillaOverlays);
        }
        return new Prepared(deny, recipes, warnings, errors);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
        serverDatapacksReady = true;
        prepared.warnings().forEach(LOG::warn);
        List<String> errors = new ArrayList<>(prepared.errors());
        List<MachineRecipeRegistration> definitions = new ArrayList<>();
        for (SourceFile file : prepared.machineRecipes()) {
            String machine = folder(file.location());
            if (!MANAGED.contains(machine)) continue;
            try {
                ResourceLocation id = recipeId(file.location());
                RecipeDefinition recipe = decode(machine, file.json());
                if (recipe instanceof ProgrammingRecipeDefinition programming) {
                    if (buildcraft.robotics.BCRoboticsBoards.getByKey(programming.boardKey())
                        == buildcraft.robotics.BCRoboticsBoards.EMPTY) {
                        throw new IllegalArgumentException("Unregistered board key " + programming.boardKey());
                    }
                }
                definitions.add(new MachineRecipeRegistration(
                    id, recipe, new DefinitionProvenance(file.location().getNamespace(), file.pack(), 1_000_000)
                ));
            } catch (RuntimeException invalid) {
                errors.add("Invalid machine recipe " + file.location() + " (" + file.pack() + "): " + invalid.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            errors.forEach(LOG::error);
            RecipeBlacklist.mergeFailClosed(prepared.blacklist());
            buildcraft.lib.net.MessageMachineRecipes.broadcast();
            LOG.error("Recipe definitions were not replaced, but all discovered blacklist bans were applied fail-closed");
            return;
        }
        if (!(BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES) instanceof MachineRecipeRegistryImpl registry)) {
            RecipeBlacklist.mergeFailClosed(prepared.blacklist());
            buildcraft.lib.net.MessageMachineRecipes.broadcast();
            LOG.error("Recipe registry unavailable; discovered blacklist bans were still applied fail-closed");
            return;
        }
        MachineRecipeReloadResult result = registry.reloadData(definitions);
        if (!result.published()) {
            result.diagnostics().forEach(d -> LOG.error("Recipe reload: {}", d));
            RecipeBlacklist.mergeFailClosed(prepared.blacklist());
            buildcraft.lib.net.MessageMachineRecipes.broadcast();
            LOG.error("Machine recipe reload aborted; previous recipes are retained and bans were merged fail-closed");
            return;
        }
        for (var diagnostic : result.diagnostics()) {
            LOG.warn("Recipe reload: {}", diagnostic);
        }
        // Both the winning recipes and deny-union must be published before notifying clients.
        List<buildcraft.lib.net.MessageMachineRecipes.Recipe> outgoing = new ArrayList<>();
        for (SourceFile source : prepared.machineRecipes()) {
            String machine = folder(source.location());
            if (!MANAGED.contains(machine)) continue;
            outgoing.add(new buildcraft.lib.net.MessageMachineRecipes.Recipe(
                machine, recipeId(source.location()), source.json().toString()));
        }
        outgoing.sort(java.util.Comparator.comparing(entry -> entry.id().toString()));
        syncedRecipes = List.copyOf(outgoing);
        hasSyncedRecipeSnapshot = true;
        RecipeBlacklist.publish(prepared.blacklist());
        buildcraft.lib.net.MessageMachineRecipes.broadcast();
        int blacklisted = prepared.blacklist().values().stream().mapToInt(Set::size).sum();
        LOG.info("Recipe reload published: {} machine JSON recipes, {} blacklist rules, {} warnings",
            definitions.size(), blacklisted, prepared.warnings().size());
    }

    /** Shared strict decoder for server execution and client display-only previews. */
    public static RecipeDefinition decode(String machine, JsonObject json) {
        String type = requireString(json, "type");
        if ("programming_table".equals(machine)) {
            if (!"buildcraftsilicon:programming".equals(type)) {
                throw new IllegalArgumentException("Expected type buildcraftsilicon:programming, got " + type);
            }
            return new ProgrammingRecipeDefinition(requireString(json, "board_key"), energy(json));
        }
        if ("integration_table".equals(machine)) {
            if ("buildcraftsilicon:integration".equals(type)) {
                CountedIngredient center = itemIngredient(requireObject(json, "center"));
                if (!json.has("ingredients") || !json.get("ingredients").isJsonArray()) {
                    throw new IllegalArgumentException("Missing array 'ingredients'");
                }
                JsonArray values = json.getAsJsonArray("ingredients");
                if (values.size() < 1 || values.size() > 8) throw new IllegalArgumentException("Expected 1 to 8 ingredients");
                List<CountedIngredient> ingredients = new ArrayList<>();
                for (JsonElement value : values) {
                    if (!value.isJsonObject()) throw new IllegalArgumentException("Invalid integration ingredient");
                    ingredients.add(itemIngredient(value.getAsJsonObject()));
                }
                JsonObject result = requireObject(json, "result");
                ResourceLocation itemId = requireId(result, "item");
                if (!itemRegistered(itemId)) throw new IllegalArgumentException("Unknown item: " + itemId);
                int count = result.has("count") ? Math.toIntExact(positive(result, "count")) : 1;
                ItemStack stack = new ItemStack(itemById(itemId), count);
                if (stack.isEmpty() || count > stack.getMaxStackSize()) {
                    throw new IllegalArgumentException("Invalid integration result stack size");
                }
                return new StaticIntegrationRecipe(center, ingredients, stack, energy(json));
            }
            Function<JsonObject, ? extends RecipeDefinition> factory = HANDLERS.get(type);
            if (factory == null) throw new IllegalArgumentException("Unknown integration handler " + type);
            RecipeDefinition definition = factory.apply(json);
            if (!(definition instanceof IntegrationRecipeDefinition)) {
                throw new IllegalArgumentException("Handler " + type + " did not provide an integration recipe");
            }
            return definition;
        }
        JsonObject input = requireObject(json, "input");
        FluidIngredient ingredient = FluidIngredient.exact(fluid(requireId(input, "fluid")), positive(input, "amount_mb"));
        if ("distiller".equals(machine)) {
            if (!"buildcraftfactory:distillation".equals(type)) {
                throw new IllegalArgumentException("Expected type buildcraftfactory:distillation, got " + type);
            }
            JsonObject outputs = requireObject(json, "outputs");
            FluidVolume gas = outputs.has("gas") ? volume(requireObject(outputs, "gas")) : FluidVolume.empty();
            FluidVolume liquid = outputs.has("liquid") ? volume(requireObject(outputs, "liquid")) : FluidVolume.empty();
            return new DistillationRecipeDefinition(ingredient, gas, liquid, energy(json));
        }
        if ("heat_exchanger".equals(machine)) {
            if (!"buildcraftfactory:heat_exchange".equals(type)) {
                throw new IllegalArgumentException("Expected type buildcraftfactory:heat_exchange, got " + type);
            }
            String mode = requireString(json, "mode");
            RecipeDefinition.Kind kind = switch (mode) {
                case "heating" -> RecipeDefinition.Kind.HEATING;
                case "cooling" -> RecipeDefinition.Kind.COOLING;
                default -> throw new IllegalArgumentException("Expected heating or cooling mode, got " + mode);
            };
            FluidVolume output = json.has("output") && !json.get("output").isJsonNull()
                ? volume(requireObject(json, "output")) : FluidVolume.empty();
            return new HeatExchangeRecipeDefinition(kind, ingredient, output,
                requireInt(json, "heat_from"), requireInt(json, "heat_to"));
        }
        throw new IllegalArgumentException("Unsupported machine " + machine);
    }

    private static long energy(JsonObject json) {
        return Math.multiplyExact(positive(requireObject(json, "energy"), "mj"), 1_000_000L);
    }

    private static CountedIngredient itemIngredient(JsonObject data) {
        int count = data.has("count") ? Math.toIntExact(positive(data, "count")) : 1;
        if (count > 64) throw new IllegalArgumentException("Integration ingredient count exceeds 64");
        boolean hasItem = data.has("item");
        boolean hasTag = data.has("tag");
        if (hasItem == hasTag) throw new IllegalArgumentException("Expected exactly one of 'item' or 'tag'");
        if (hasTag) {
            ResourceLocation tag = requireId(data, "tag");
            //? if <1.19.3 {
            return CountedIngredient.of(TagKey.create(net.minecraft.core.Registry.ITEM_REGISTRY, tag), count);
            //?} else {
            /*?
            return CountedIngredient.of(TagKey.create(Registries.ITEM, tag), count);
            ?*/
            //?}
        }
        ResourceLocation itemId = requireId(data, "item");
        if (!itemRegistered(itemId)) throw new IllegalArgumentException("Unknown item: " + itemId);
        return CountedIngredient.of(itemById(itemId), count);
    }

    private static boolean itemRegistered(ResourceLocation id) {
        //? if <1.19.3 {
        return net.minecraft.core.Registry.ITEM.containsKey(id);
        //?} else {
        /*?
        return BuiltInRegistries.ITEM.containsKey(id);
        ?*/
        //?}
    }

    private static Item itemById(ResourceLocation id) {
        //? if <1.19.3 {
        return net.minecraft.core.Registry.ITEM.get(id);
        //?} else {
        /*?
        return BuiltInRegistries.ITEM.get(id);
        ?*/
        //?}
    }

    private static FluidVariant fluid(ResourceLocation id) {
        //? if <1.19.3 {
        if (!net.minecraft.core.Registry.FLUID.containsKey(id)) {
            throw new IllegalArgumentException("Unknown fluid: " + id);
        }
        //?} else {
        /*?
        if (!BuiltInRegistries.FLUID.containsKey(id)) {
            throw new IllegalArgumentException("Unknown fluid: " + id);
        }
        ?*/
        //?}
        return FluidVariant.of(id);
    }

    private static FluidVolume volume(JsonObject data) {
        return FluidVolume.of(fluid(requireId(data, "fluid")), FluidAmount.of(positive(data, "amount_mb")));
    }

    private static String requireString(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive() || !json.get(name).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string '" + name + "'");
        }
        String value = json.get(name).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("Blank '" + name + "'");
        return value;
    }

    private static JsonObject requireObject(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonObject()) throw new IllegalArgumentException("Missing object '" + name + "'");
        return json.getAsJsonObject(name);
    }

    private static ResourceLocation requireId(JsonObject json, String name) {
        String id = requireString(json, name);
        ResourceLocation result = ResourceLocation.tryParse(id);
        if (result == null || !result.toString().equals(id)) throw new IllegalArgumentException("Invalid resource ID '" + id + "'");
        return result;
    }

    private static long positive(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive()
            || !json.get(name).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + name + "'");
        }
        long n = json.get(name).getAsBigDecimal().longValueExact();
        if (n <= 0) throw new IllegalArgumentException("'" + name + "' must be positive");
        return n;
    }

    private static int requireInt(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive()
            || !json.get(name).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + name + "'");
        }
        return json.get(name).getAsBigDecimal().intValueExact();
    }

    private static String folder(ResourceLocation file) {
        String path = file.getPath();
        int first = path.indexOf('/');
        int second = path.indexOf('/', first + 1);
        return first < 0 || second < 0 ? "" : path.substring(first + 1, second);
    }

    private static ResourceLocation recipeId(ResourceLocation file) {
        String path = file.getPath();
        int first = path.indexOf('/');
        if (first < 0 || !path.endsWith(".json")) throw new IllegalArgumentException("Invalid recipe file path: " + file);
        String recipePath = path.substring(first + 1, path.length() - 5);
        ResourceLocation id = ResourceLocation.tryParse(file.getNamespace() + ":" + recipePath);
        if (id == null) throw new IllegalArgumentException("Invalid recipe ID from " + file);
        return id;
    }

    /** Vanilla's final resource selection is authoritative for recipes; stack order is only
     * used for finding all bans and reporting duplicate recipe paths. */
    private static Object winningResource(ResourceManager manager, ResourceLocation id, List<Object> stack) {
        try {
            Method getResource = manager.getClass().getMethod("getResource", ResourceLocation.class);
            Object optional = getResource.invoke(manager, id);
            if (optional instanceof java.util.Optional<?> value && value.isPresent()) return value.get();
            if (optional != null && !(optional instanceof java.util.Optional<?>)) return optional;
        } catch (ReflectiveOperationException ignored) { /* API variant; fall back to stack */ }
        return stack.get(stack.size() - 1);
    }

    private static JsonObject readJson(Object resource) throws Exception {
        Method open = resource.getClass().getMethod("open");
        try (InputStream stream = (InputStream) open.invoke(resource);
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
            return parsed.getAsJsonObject();
        }
    }

    /** Pack APIs changed in 26.3: a Selector replaced the Predicate. Reflect only this boundary. */
    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, List<Object>> stackedResources(ResourceManager manager, String prefix) throws Exception {
        Map<ResourceLocation, List<Object>> result = new LinkedHashMap<>();
        Method stacked = findListMethod(manager.getClass(), "listResourceStacks");
        if (stacked != null) {
            Map<ResourceLocation, ?> files = (Map<ResourceLocation, ?>) stacked.invoke(manager, prefix, acceptAll(stacked.getParameterTypes()[1]));
            for (var entry : files.entrySet()) result.put(entry.getKey(), new ArrayList<>((List<?>) entry.getValue()));
            return result;
        }
        Method list = findListMethod(manager.getClass(), "listResources");
        if (list == null) throw new IllegalStateException("ResourceManager cannot list resources");
        Map<ResourceLocation, ?> files = (Map<ResourceLocation, ?>) list.invoke(manager, prefix, acceptAll(list.getParameterTypes()[1]));
        for (ResourceLocation id : files.keySet()) {
            Method stack = findStackMethod(manager.getClass());
            if (stack != null) result.put(id, new ArrayList<>((List<?>) stack.invoke(manager, id)));
            else result.put(id, List.of(files.get(id)));
        }
        return result;
    }

    private static Method findListMethod(Class<?> manager, String name) {
        for (Method m : manager.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 2
                && m.getParameterTypes()[0] == String.class && Map.class.isAssignableFrom(m.getReturnType())) return m;
        }
        return null;
    }

    private static Method findStackMethod(Class<?> manager) {
        for (Method m : manager.getMethods()) {
            if ((m.getName().equals("getResourceStack") || m.getName().equals("getResources"))
                && m.getParameterCount() == 1 && List.class.isAssignableFrom(m.getReturnType())) return m;
        }
        return null;
    }

    private static Object acceptAll(Class<?> selector) {
        return Proxy.newProxyInstance(selector.getClassLoader(), new Class<?>[]{selector}, (self, method, args) -> {
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return true;
            if (method.getName().equals("toString")) return "BCCE Recipe Selector";
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            return null;
        });
    }

    private static String packName(Object resource) {
        for (String getter : List.of("sourcePackId", "sourcePackName", "getSourceName", "getSourcePackId")) {
            try { return String.valueOf(resource.getClass().getMethod(getter).invoke(resource)); }
            catch (ReflectiveOperationException ignored) { /* versions differ */ }
        }
        return resource.getClass().getSimpleName();
    }

    private static boolean isBuiltinNeoForgeOverlay(ResourceLocation id, List<Object> resources) {
        if (!"minecraft".equals(id.getNamespace()) || resources.size() != 2) return false;
        Set<String> packs = new LinkedHashSet<>();
        for (Object resource : resources) packs.add(packName(resource));
        return packs.equals(Set.of("vanilla", "mod/neoforge"));
    }

    private static String sources(List<Object> resources) {
        List<String> names = new ArrayList<>();
        for (Object resource : resources) names.add(packName(resource));
        return String.join(" -> ", names);
    }
}
