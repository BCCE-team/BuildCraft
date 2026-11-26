# BCCE Configuration (API v2.2)

Open the BCCE editor from the native **Mods > Config** button on Forge/NeoForge or the optional Mod Menu integration on Fabric 26.3. Neither integration owns BCCE settings or exposes a loader configuration spec. Fabric uses Mod Menu only as a screen entrypoint (compile-only API; not bundled or required). In a world, assign **Options > Controls > Key Binds > BuildCraft > Open BuildCraft settings**; the binding has no default and does not open while typing or using another screen. No custom buttons are added to the title or pause menu.

The editor has Client, World/Server and Balance tabs plus an Addons/BCCE view switch. Both views have the same three tabs. Addon categories are generated entirely from registered addon definitions; an unpopulated tab is empty. In the title screen and in integrated singleplayer, World and Balance refer to the installation's personal gameplay profile. In a multiplayer session they refer only to the connected server, and become available after an authorized response. Search covers all available settings in the current view, and retains its text on tab/view changes. The BCCE and Addons views never include each other's search results or reset targets. Pending edits in both views are saved together with Done. Parent categories only expand/collapse; any settings directly registered on a parent appear under a separate General child. There is no All settings pseudo-category. Indented child rows and branch chevrons show the category hierarchy. Scroll each panel independently with the mouse wheel, or drag its scrollbar when the contents overflow; there are no page buttons. Select a leaf to edit its values; reset a single value, leaf, subtree, entire section, or all accessible settings. Changes and resets are staged until Done; Cancel discards them. Hover over a setting for its description, default, bounds, scope and application policy.

## Storage and migration

- `config/buildcraft/client.properties`: local installation's client settings.
- `config/buildcraft/server-defaults.properties`: shared personal World/Balance profile in a client installation, or initial template in a dedicated-server installation.
- `<world>/buildcraft/configuration.properties`: authoritative dedicated server settings (created from the server installation's template on first load).

Personal worlds share the same profile, so title-screen edits also apply when reopening existing personal worlds. Changes made inside a personal world save to that same profile. They never overwrite a remote server's configuration. Legacy per-save configuration files created by the first v2.2 editor in integrated worlds are preserved on disk, but are not automatically merged into the shared profile: different saves can contain conflicting values. Back them up and manually transfer desired settings into the personal profile if needed. Dedicated-server world files continue to be used unchanged.

The module schemas are registered by BCCE's shared ConfigBinding rather than Forge/NeoForge config specs. Legacy `*-common.toml` values and enabled machine/robot INI overrides are migration inputs. Existing new-format values take precedence. Unknown keys are preserved. For an existing installation, back up the old configuration and world before first launch.

The server authorizes both read and write requests (operator permission level 2 or the integrated-server owner), validates types/ranges and uses revisions to reject stale edits. Each scope is written atomically; a mixed client/server edit is not a distributed atomic transaction. Most existing client values apply on game restart; screen modes and display units are live (screen mode applies on the next opening). World and current balance values apply on world/server restart. This avoids changing cached limits in the middle of a running world. Existing world data is not automatically transformed if a capacity is reduced.

## Addon API

`BuildCraftApi.service(BuildCraftServices.CONFIGURATION)` provides `ConfigurationService`. Register a `ConfigDefinition` during initialization using an addon-owned namespace and one of `CLIENT`, `WORLD`, or `BALANCE`. The returned `Supplier<String>` reads the effective value, while the editor shows the saved value. The registry prefixes the category with the addon namespace. Client settings may use `LIVE`; the addon should read the supplier at the point of use instead of caching it indefinitely. Server-side definitions are transmitted only to authorized clients and must use a restart policy. Legacy `Section.ADDONS` remains binary/wire compatible and maps to Client or World by scope; new definitions should use an explicit section. The existing setting ID and stored values are unchanged.

Example (the addon selects its own categories):

```java
var config = BuildCraftApi.service(BuildCraftServices.CONFIGURATION);
var capacity = config.registerAddon(new ConfigDefinition(
    "example:tank_capacity", ConfigDefinition.Section.BALANCE,
    List.of("Tanks", "Steel"), ConfigDefinition.Scope.SERVER,
    ConfigDefinition.Kind.INTEGER, "64000", 1000, 256000, List.of(),
    "example.config.capacity.name", "example.config.capacity.description",
    "example.config.capacity.tooltip", "Steel Tank Capacity", "Tank volume in mB.",
    "Capacity is applied when the world reloads.", ConfigDefinition.ApplyPolicy.WORLD_RESTART));
int milliBuckets = Integer.parseInt(capacity.get());
```

This setting appears only in **Addons > Balance > example > Tanks > Steel**. Client, World and Balance have no built-in addon categories. Optionally localize enum choices via `<nameKey>.choice.<lowercase-value>`.

## Screen modes and display units

Client > Interface > Screens contains independent Zone Planner and Electronic Library policies: `SWITCH`, `LEGACY_ONLY`, `MODERN_ONLY`. Forced modes hide both mode buttons and preserve the previous manual choice. The Robot Processor's dedicated planner view is not switched into the legacy Zone Planner.

Client > Interface > Units contains `Rate Unit` (seconds/ticks, default ticks) and `Fluid Volume Unit` (B/mB, default millibuckets). Both static and flowing fluid displays share the same setting. Fresh installations and reset actions use ticks and millibuckets; explicitly saved and migrated choices are preserved. Migration prefers the old static-unit choice if the two old unit settings conflict; the old flow choice is used if the static choice is absent. The old keys are retained in storage but are no longer registered/displayed. One second means 20 game ticks, not measured server TPS. Values stored in files and transmitted over the network retain canonical units.

The public `ConfigurationService.displayUnits()` snapshot provides `formatFluid`, `formatFluidRate`, `formatMjRate`, `formatFeRate` and `formatRate(BigDecimal perTick, String unit)` for addon readouts. `ScreenMode` and `ConfigurationService.screenMode(id)` can be reused with an addon-registered client enum whose choices are the three `ScreenMode` constants. Read these preferences on the client when drawing a screen, not on a dedicated server when simulating gameplay. The BCCE numeric editor reversibly converts known built-in rate, fluid and micro-MJ settings; arbitrary addon schemas keep their canonical units unless the addon formats its own presentation. Native Jade fluid bars and JEI ingredient tooltips remain subject to their respective mods' unit-formatting behavior; fixed prose in guide pages is not dynamically rewritten.

## Scope and remaining validation

The editor covers existing BCConfigSpec schemas and the 78 registered machine/robot balance parameters. It does not yet expose every hardcoded fuel, recipe or gameplay constant, separate internal UI state, update preferences, or profile import/export. Current descriptions are based on existing schema comments and English fallbacks; translations use the registered localization keys. Enum values cycle through allowed choices. No advanced-settings switch or manual world selector is provided.

Run `python scripts/tests/test_configuration_registry.py`, `python scripts/tests/test_configuration_tree.py` and `python scripts/tests/test_configuration_scroll.py` for standalone validation. Full loader compilation and in-game QA are required before release, particularly the GUI and lifecycle hooks on 26.X, world-specific changes that affect registration, network payload sizes with many addons, and existing-world capacity changes.
