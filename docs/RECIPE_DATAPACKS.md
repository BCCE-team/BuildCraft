# BuildCraft recipe datapacks and absolute blacklists

This document describes the machine-recipe datapack support introduced in this patch. It also distinguishes features already supported from planned extensions.

## Directory layout

For Minecraft 1.19.2 and 1.20.1 (Forge):

```text
data/<namespace>/recipes/<machine>/<recipe>.json
```

For Minecraft 1.21+ (NeoForge):

```text
data/<namespace>/recipe/<machine>/<recipe>.json
```

Only the recipe directory recognized by the current Minecraft version is processed;
including both `recipe/` and `recipes/` in a mixed-version pack does not register
duplicate machine definitions. Blacklist loading is independent of this distinction.

Blacklist paths are **unchanged across versions**:

```text
data/<namespace>/blacklist_recipes/<machine>/<rule>.json
```

The datapack namespace is the owner of the recipe. The first path segment below `recipe(s)` is organizational: the JSON `type` determines which serializer loads a recipe. Recipe IDs include the path below `recipe(s)`; for example `data/my_pack/recipe/distiller/steam.json` has the ID `my_pack:distiller/steam`.

The machine keys supported for **blacklists** are `distiller`, `heat_exchanger`, `assembly_table`, `integration_table`, `programming_table`, `auto_workbench`, and `advanced_crafting_table`. Charging Table only transfers energy and does not have craft recipes to blacklist.

The `integration_table` and `programming_table` directories now also contain machine JSON recipes. Their native Minecraft serializer is an inert crafting-grid placeholder: the dedicated BCCE loader validates the complete JSON and executes the corresponding API v2 definition. This preserves the ability to replace them through standard datapack resource priority without accidentally allowing the recipes in a vanilla crafting grid.

## Distiller recipe

`data/my_pack/recipe/distiller/water.json` (use `recipes` on older Minecraft):

```json
{
  "type": "buildcraftfactory:distillation",
  "input": { "fluid": "minecraft:water", "amount_mb": 1000 },
  "outputs": {
    "gas": { "fluid": "my_mod:steam", "amount_mb": 1000 }
  },
  "energy": { "mj": 16 }
}
```

Only use already-registered fluids. You may omit `gas` or `liquid`, but not both. Energy is specified in whole MJ and amounts in integer millibuckets. Java recipe APIs continue using micro-MJ internally; the loader performs the conversion. Out-of-range, fractional, and invalid amounts are rejected.

## Heat Exchanger recipe

`data/my_pack/recipe/heat_exchanger/heating/water_to_hot_water.json`:

```json
{
  "type": "buildcraftfactory:heat_exchange",
  "mode": "heating",
  "input": { "fluid": "minecraft:water", "amount_mb": 1000 },
  "output": { "fluid": "my_mod:hot_water", "amount_mb": 1000 },
  "heat_from": 0,
  "heat_to": 1
}
```

For a cooling recipe set `mode` to `cooling` and ensure `heat_to < heat_from`. Omit `output` for a consuming heat-exchange process. The existing machine runtime still enforces its heat-balance and capacity constraints.

Built-in distillation and heat-exchange definitions have been moved from `BCEnergyRecipes` Java initialization to JSON resources in `buildcraftfactory`.

## Integration Table recipes

A fixed-output Integration Table recipe can now be defined in a datapack. Example:

`data/my_pack/recipe/integration_table/module_upgrade.json`:

```json
{
  "type": "buildcraftsilicon:integration",
  "center": {"item": "minecraft:iron_pickaxe", "count": 1},
  "ingredients": [
    {"item": "minecraft:redstone", "count": 2},
    {"tag": "c:ingots/iron", "count": 1}
  ],
  "result": {"item": "minecraft:diamond_pickaxe", "count": 1},
  "energy": {"mj": 500}
}
```

This example is illustrative: the tag must exist in the target version, and it produces a **new** item without transferring the source pickaxe's enchantments or damage. The `center` item and all additional ingredients are consumed. The actual Integration Table has eight additional inventory slots. Inputs may be specified with an item or item tag; resource identifiers and integer quantities are strictly validated. Overlapping tag requirements are allocated **jointly**, so one stack cannot satisfy two requirements twice. Unmatched extra items prevent crafting.

Complex recipes that transform or preserve Data Components remain Java algorithms, but their **activation and cost** can be defined by a JSON descriptor. For example, the built-in robot-board operation is now:

`data/buildcraftrobotics/recipe/integration_table/robot_board_integration.json`:

```json
{
  "type": "buildcraftsilicon:robot_board_integration",
  "energy": {"mj": 5000}
}
```

The handler `buildcraftsilicon:robot_board_integration` is registered in Java by the robotics module. A JSON datapack can replace its energy requirement, disable its resource, or blacklist its recipe ID. A JSON file **cannot** supply arbitrary Java class names or executable code. Other handlers require explicit Java registration by their owning addon.

The built-in robot integration ID is now `buildcraftrobotics:integration_table/robot_board_integration`, rather than `buildcraftrobotics:robot_board_integration`. Update blacklist rules referring to the legacy ID. **Important:** this is a *single dynamic recipe* that handles multiple registered boards. Blacklisting this ID disables **all** robot-board integration variants, not one JEI page. To smoke-test one banned integration recipe while retaining vanilla robot integrations, add and blacklist a separate static Integration Table recipe ID, as done by the 1.21.1 smoke-test datapack.

## Programming Table recipes

Programming a registered robot board uses a JSON definition rather than the old Java energy-cost constant. For example:

`data/buildcraftrobotics/recipe/programming_table/picker.json`:

```json
{
  "type": "buildcraftsilicon:programming",
  "board_key": "picker",
  "energy": {"mj": 800}
}
```

All 17 standard board recipes are supplied in `buildcraftrobotics` resources for each supported source family. The `board_key` must correspond to a registered Java robot-board program; datapacks control whether it is programmable and what it costs, **not its AI behavior**. A registered board with no active JSON definition is not offered in the Programming Table.
A board may be referenced by multiple recipe IDs; blacklist checks operate on the
**actual recipe IDs**, not an assumed `buildcraftrobotics:programming_table/<board_key>`.
When one recipe ID is forbidden, another independent non-blacklisted recipe for the
same board remains valid. Addon board definitions therefore need a companion datapack JSON file. The server synchronizes **each** active board/energy variant with each open Programming Table menu.

Two different recipe IDs may target the **same `board_key`** while specifying different MJ costs. They are distinct, selectable programming operations both in JEI and in the machine. Recipes are collapsed **only if their complete effective definitions match** (currently `board_key` plus micro-MJ cost), after applying blacklist restrictions to every recipe ID separately. Such identical-content recipes are reported in the log. Differing energy requirements are not duplicate recipes, and priority must not arbitrarily choose one. The board item tooltip summarizes the **minimum–maximum MJ cost across active recipes for that board**, or one value if all costs are equal; the JEI recipe page still displays the exact cost of its selected recipe. Blacklisted definitions do not affect the tooltip range. Tick duration is not currently a separate Programming Table JSON parameter; unsupported fields do not create new processing behavior. To override a particular recipe ID, replace **its original path**; the absolute blacklist always wins.

## Absolute blacklist: deny always wins

`data/my_pack/blacklist_recipes/distiller/ban_oil.json`:

```json
{
  "recipe": "buildcraftfactory:distiller/buildcraftenergy/oil"
}
```

Bans are keyed by machine type **and exact recipe ID**. The loader reads **all resource-stack layers**, not just the highest-priority file. A ban in any loaded datapack therefore remains active even if another pack replaces the same blacklist file, introduces a recipe override, or attempts to allow it. `enabled`/`allow`/`action` fields do not authorize re-enabling; any document with a valid `recipe` field is still a ban. To restore a recipe, remove **all** loaded rules banning its ID and reload.

A ban on an ID with no currently loaded recipe remains meaningful: it will block that ID if it becomes available later. Blacklists are enforced in server-side recipe lookups and never delete the underlying vanilla recipe. Thus blacklisting a crafting recipe under `auto_workbench` does **not** prohibit a player from crafting it manually.

### Examples for other machines

```text
data/my_pack/blacklist_recipes/assembly_table/no_chipset.json
  {"recipe":"buildcraftsilicon:assembly/diamond_chipset"}

data/my_pack/blacklist_recipes/auto_workbench/no_sticks.json
  {"recipe":"minecraft:stick"}

data/my_pack/blacklist_recipes/advanced_crafting_table/no_sticks.json
  {"recipe":"minecraft:stick"}

data/my_pack/blacklist_recipes/integration_table/no_board_robot.json
  {"recipe":"buildcraftrobotics:integration_table/robot_board_integration"}

data/my_pack/blacklist_recipes/programming_table/no_picker.json
  {"recipe":"buildcraftrobotics:programming_table/picker"}
```

For Programming Table the recipe key uses a registered board's `key` field (`picker`, `carrier`, etc., or the addon-defined key). The server filters the available board list and synchronizes blocked keys with the client GUI.

## Duplicate recipe reporting

On `/reload`, the loader writes WARN entries for:

* an identical recipe resource ID appearing in multiple loaded packs;
* identical JSON definitions under different IDs for recognized machine folders;
* overlapping inputs of recognized distillation/heat-exchange recipes;
* a Java-defined machine recipe overridden by a datapack recipe with the same ID.

The highest-priority standard Minecraft resource wins for duplicate IDs, and a warning does not itself disable a recipe. **Absolute blacklist rules apply afterward and cannot be overridden by a recipe.**

## Reload behavior and limitations

The loader validates staged machine recipes and deny rules before publishing the new registry and blacklist snapshots. On validation failure it retains the previous machine recipe registry, **but merges every newly discovered blacklist entry into the previous blacklist** (fail-closed), so an invalid recipe cannot bypass a ban. The errors are logged. Successful reloads replace the blacklist with the exact union of currently loaded bans, allowing removal of bans only when all applicable rules are gone. This does not override Minecraft's own native recipe-loading error policy.

Native Assembly and ordinary crafting recipes already use Minecraft recipe files. Static Integration Table processing, the built-in robot-board integration descriptor, and standard Programming Table costs are now JSON-defined as well. The robot integration **algorithm** and each board AI are still Java implementations; they cannot and should not be serialized as arbitrary code in JSON. Generic dynamic addon integrations still require a registered handler or the legacy API v2 Java registration path. The blacklist applies in all machine runtimes. A follow-up synchronizes the **static Distiller/Heat Exchanger JSON catalog and machine blacklist rules** to remote clients and JEI (see below). Programmatic addon recipes without a JSON wire representation remain an explicit limitation. Existing public API v2 Java registration remains available for addon compatibility.

The migration changes built-in distillation and heat-exchange recipe IDs to `buildcraftfactory:distiller/...` and `buildcraftfactory:heat_exchanger/...`. Update any datapacks and persisted machine configurations that reference older `buildcraftenergy:distillation/...` or `buildcraftenergy:heating/...` IDs. The fluids and energy amounts are intended to remain unchanged.

## Client recipe synchronization (follow-up)

On server datapack publication, BCCE sends the winning machine JSON definitions and the
absolute deny-union to connected players; it resends this snapshot after players join.
The transport uses a bounded, multi-part payload (64 KiB per part and 8 MiB overall).
The client assembles all parts and validates them before replacing its display snapshot.
A missing, malformed, or incomplete update never partially publishes its recipes or
lifts blacklists. The client display cache is cleared on logout so moving between
servers does not leak recipes or permissions.

JEI's Distiller/Heat Exchanger entries are now derived from this server-authored
client snapshot whenever it is available. Disabled recipes are filtered from JEI's
Assembly, Integration board, and Programming board views. Legacy clients poll
for recipe-generation changes; modern clients subscribe directly to the snapshot.
The actual machine calculation continues exclusively on the server.

**Scope limitation:** This wire protocol now carries the JSON descriptors for
Distiller, Heat Exchanger, Integration Table and Programming Table. Its client
validator can reconstruct fixed Integration definitions and registered dynamic
handlers. Java-only addon recipes without JSON wire representations are not
transmitted. The JEI Integration Table category displays both robot-board transformations and
arbitrary fixed JSON Integration recipes, including the centre item, up to eight
additional inputs, tag alternatives and the configured energy cost. The recipe
category is refreshed when the authoritative server snapshot changes. Dynamic
addon handlers without a serializable display descriptor remain Java-only. Server execution and the
Programming Table GUI do use the new JSON definitions and costs. Some legacy
board-registry metadata still contains the historical fallback cost for API
compatibility; it no longer decides the Programming Table craft cost.

## Safe Distiller progress after recipe reload (phase 4)

Each Distiller now saves the identity of the **selected recipe**, including its ID,
input variant and amount, outputs and MJ requirement, alongside its unfinished MJ.
If any of those change during `/reload`, the paid progress is moved to a saved refund
buffer instead of being reused against a different recipe. If the battery cannot
accept the entire refund, the Distiller waits until space becomes available. Legacy
world saves with unfinished work and no recipe identity are safely refunded.

The same rule applies when a blacklist makes the selected recipe unavailable.
Programmatic non-serializable fluid matchers are revalidated after a world restart.
Normal battery energy and fluid inventories are not discarded on a recipe change.

### Verification boundary

The phase-4 tests validate the shared recipe-identity algorithm and the materialized
source wiring. A successful source materialization is **not** a Gradle build or a
GameTest run. Actual tank rollback and JEI UI rendering should still be verified
with a dedicated modded client and server before release.
