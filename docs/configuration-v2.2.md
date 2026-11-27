# BCCE Configuration (API v2.2)

Open the BCCE editor from the native **Mods > Config** button on Forge/NeoForge or the optional Mod Menu integration on Fabric 26.3. Neither integration owns BCCE settings or exposes a loader configuration spec. Fabric uses Mod Menu only as a screen entrypoint (compile-only API; not bundled or required). In a world, assign **Options > Controls > Key Binds > BuildCraft > Open BuildCraft settings**; the binding has no default and does not open while typing or using another screen. No custom buttons are added to the title or pause menu.

The editor has Client, World/Server and Balance tabs plus an Addons/BCCE view switch. Both views have the same three tabs. Addon categories are generated entirely from registered addon definitions; an unpopulated tab is empty. In the title screen and in integrated singleplayer, World and Balance refer to the installation's personal gameplay profile. In a multiplayer session they refer only to the connected server, and become available after an authorized response. Search covers all available settings in the current view, and retains its text on tab/view changes. The BCCE and Addons views never include each other's search results or reset targets. Pending edits in both views are saved together with Done. Parent categories only expand/collapse; any settings directly registered on a parent appear under a separate General child. There is no All settings pseudo-category. Indented child rows and branch chevrons show the category hierarchy. Scroll each panel independently with the mouse wheel, or drag its scrollbar when the contents overflow; there are no page buttons. Select a leaf to edit its values; reset a single value, leaf, subtree, entire section, or all accessible settings. Changes and resets are staged until Done; Cancel discards them. Hover over a setting for its detailed description, possible enum modes, default, bounds and application policy. Tooltips wrap at word boundaries and separate explanations from technical details. The currently selected described enum choice is highlighted.

## Storage and migration

- `config/buildcraft/client.properties`: local installation's client settings.
- `config/buildcraft/server-defaults.properties`: shared personal World/Balance profile in a client installation, or initial template in a dedicated-server installation.
- `<world>/buildcraft/configuration.properties`: authoritative dedicated server settings (created from the server installation's template on first load).

Personal worlds share the same profile, so title-screen edits also apply when reopening existing personal worlds. Changes made inside a personal world save to that same profile. They never overwrite a remote server's configuration. Legacy per-save configuration files created by the first v2.2 editor in integrated worlds are preserved on disk, but are not automatically merged into the shared profile: different saves can contain conflicting values. Back them up and manually transfer desired settings into the personal profile if needed. Dedicated-server world files continue to be used unchanged.

The module schemas are registered by BCCE's shared ConfigBinding rather than Forge/NeoForge config specs. Legacy `*-common.toml` values and enabled machine/robot INI overrides are migration inputs. Existing new-format values take precedence. Unknown keys are preserved. For an existing installation, back up the old configuration and world before first launch.

The server authorizes both read and write requests (operator permission level 2 or the integrated-server owner), validates types/ranges and uses revisions to reject stale edits. Each scope is written atomically; a mixed client/server edit is not a distributed atomic transaction. Most existing client values apply on game restart; screen modes and display units are live (screen mode applies on the next opening). World and current balance values apply on world/server restart. This avoids changing cached limits in the middle of a running world. Existing world data is not automatically transformed if a capacity is reduced.

## Addon API

`BuildCraftApi.service(BuildCraftServices.CONFIGURATION)` provides `ConfigurationService`. Register a `ConfigDefinition` during initialization using an addon-owned namespace and one of `CLIENT`, `WORLD`, or `BALANCE`. The returned `Supplier<String>` reads the effective value, while the editor shows the saved value. The registry prefixes the category with the addon namespace. Client settings may use `LIVE`; the addon should read the supplier at the point of use instead of caching it indefinitely. Server-side definitions are transmitted only to authorized clients and must use a restart policy. Registration (both client and server) closes at `ApiLifecycle.FROZEN`; register during normal mod initialization. Registering a schema increments its scope revision so an editor with a stale snapshot cannot overwrite it. A frozen schema remains frozen across world unloads and reloads. Legacy `Section.ADDONS` remains binary/wire compatible and maps to Client or World by scope; new definitions should use an explicit section. The existing setting ID and stored values are unchanged.

Example (the addon selects its own categories):

```java
var config = BuildCraftApi.service(BuildCraftServices.CONFIGURATION);
var definition = new ConfigDefinition(
    "example:tank_capacity", ConfigDefinition.Section.BALANCE,
    List.of("Tanks", "Steel"), ConfigDefinition.Scope.SERVER,
    ConfigDefinition.Kind.INTEGER, "64000", 1000, 256000, List.of(),
    "example.config.capacity.name", "example.config.capacity.description",
    "example.config.capacity.tooltip", "Steel Tank Capacity", "Tank volume in mB.",
    "Capacity is applied when the world reloads.", ConfigDefinition.ApplyPolicy.WORLD_RESTART);
var capacity = config.registerAddon(definition);
int milliBuckets = Integer.parseInt(capacity.get());
```

The typed API uses the same `ConfigDefinition`, IDs, validation, storage and authorization. For example, with `definition` constructed as above (without registering it twice):

```java
ConfigKey<Integer> capacityKey = ConfigKey.integer(definition);
ConfigValue<Integer> capacity = config.register(capacityKey);
int milliBuckets = capacity.get();
Optional<Integer> effective = config.read(capacityKey);
```

The supported factories are `bool`, `integer`, `longInteger`, `decimal`, `string` and `enumeration(definition, MyEnum.class)`. The factory checks the schema kind, the `int` range and allowed enum constants before registration; an enum may expose a subset of its constants. Typed handles implement `Supplier<T>` and never mutate configuration. `read(key)` returns empty for an unregistered ID and rejects a registered schema with incompatible kind, scope, policy, numeric bounds or enum choices. A key's local presentation metadata is not part of that check. Prefer a retained handle for frequently read values. Integer validation compares against the exact represented `double` bounds (no rounding of the incoming `long`); the legacy schema and wire format still represent bounds as `double`, so not every arbitrary long bound is representable. The existing `registerAddon(ConfigDefinition)` and `value(String)` remain available without descriptor changes.

### Application policies

| Policy | Client settings | Integrated server | Dedicated server |
| --- | --- | --- | --- |
| `LIVE` | Immediately after successful save | Not supported for addons | Not supported for addons |
| `WORLD_RESTART` | On the next play-session login (not a dimension change) | Next world opening | Next world opening |
| `RESTART` | Next game process | Next game process, not world reopening | First authoritative world load in a new server process |

Server handles should be read after the server's world configuration is initialized, not during registration. The first dedicated world load takes its restart-only values from that world's file; reopening or switching worlds in the same process does not activate later changes to these settings. For integrated servers, restart-only values are initialized from the personal profile at registration. Client session changes are applied through each loader's login event. Read client handles at point of use rather than indefinitely caching them. These policies do not migrate existing machine state or grant write permission.

This setting appears only in **Addons > Balance > example > Tanks > Steel**. Client, World and Balance have no built-in addon categories. Optionally localize enum choices via `<nameKey>.choice.<lowercase-value>`. The optional `<nameKey>.choice.<lowercase-value>.description` translation supplies an explanation for that choice in the tooltip; it needs no extra registration or network fields. Use the main description/tooltip for context common to all choices and use paragraph breaks for longer explanations.

## Screen modes and display units

Client > Interface > Screens contains independent Zone Planner and Electronic Library policies: `SWITCH`, `LEGACY_ONLY`, `MODERN_ONLY`. Forced modes hide both mode buttons and preserve the previous manual choice. The Robot Processor's dedicated planner view is not switched into the legacy Zone Planner.

Client > Interface > Units contains `Rate Unit` (seconds/ticks, default ticks) and `Fluid Volume Unit` (B/mB, default millibuckets). Both static and flowing fluid displays share the same setting. Fresh installations and reset actions use ticks and millibuckets; explicitly saved and migrated choices are preserved. Migration prefers the old static-unit choice if the two old unit settings conflict; the old flow choice is used if the static choice is absent. The old keys are retained in storage but are no longer registered/displayed. One second means 20 game ticks, not measured server TPS. Values stored in files and transmitted over the network retain canonical units.

The public `ConfigurationService.displayUnits()` snapshot provides `formatFluid`, `formatFluidRate`, `formatMjRate`, `formatFeRate` and `formatRate(BigDecimal perTick, String unit)` for addon readouts. `ScreenMode` and `ConfigurationService.screenMode(id)` can be reused with an addon-registered client enum whose choices are the three `ScreenMode` constants. Read these preferences on the client when drawing a screen, not on a dedicated server when simulating gameplay. The BCCE numeric editor reversibly converts known built-in rate, fluid and micro-MJ settings; arbitrary addon schemas keep their canonical units unless the addon formats its own presentation. Native Jade fluid bars and JEI ingredient tooltips remain subject to their respective mods' unit-formatting behavior; fixed prose in guide pages is not dynamically rewritten.

## Calculated numeric explanations

Selected built-in numeric settings show a read-only calculation in their list row, with further results in the tooltip. Calculations use validated, unsaved edits from the current editor session, including related settings; invalid or unavailable values do not fall back to stale results. The displayed results are previews, not newly applied gameplay values. For example, the MJ/FE ratio uses the same four-decimal rounding as the energy conversion implementation: 0.1 means 10 FE per MJ and 0.2 means 5 FE per MJ. Non-terminating ratios are marked as approximate. Actual transfers still use whole FE.

Other previews cover pipe extraction costs, energy equivalents of the base transfer rates, balance multipliers, configured pump extraction cost, quarry task attempts and rate limits, quarry extra-task penalties, item lifetime and network update intervals. Rates follow the active client display unit. Machine previews intentionally exclude additional API machine-property modifiers; a calculated rate or cost is not a guarantee of actual throughput. No additional configuration keys are saved. The current built-in preview rules are internal to the editor; arbitrary addon values do not receive automatic calculations. A general declarative extension point for calculated metadata is still planned; the typed value API does not add new editor features.

## Scope and remaining validation

The editor covers existing BCConfigSpec schemas and the 56 active machine/robot balance parameters. It does not yet expose every hardcoded fuel, recipe or gameplay constant, separate internal UI state or profile import/export. Every built-in module setting has an English name, concise description and detailed tooltip; machine/robot descriptions are supplied by the balance schema. Translations use the registered localization keys. Enum values cycle through allowed choices. No advanced-settings switch or manual world selector is provided.

Run `python scripts/tests/test_configuration_registry.py`, `python scripts/tests/test_configuration_tree.py` and `python scripts/tests/test_configuration_scroll.py` for standalone validation. Full loader compilation and in-game QA are required before release, particularly the GUI and lifecycle hooks on 26.X, world-specific changes that affect registration, network payload sizes with many addons, and existing-world capacity changes.

## Additional client and world preferences

Client > Interface > Units adds Large Number Format (plain/grouped/compact) and Temperature Unit (K/C/F). `ConfigurationService.displayFormat()` exposes these presentation preferences without changing the existing `DisplayUnits` record or numeric storage. The format can also combine with `DisplayUnits` via `formatFluid(units, amount)` and `formatRate(units, perTick, unit)`. Editable fields keep exact, unabridged values. Rendering > Pipes contains a fluid texture animation toggle, item display mode (all up to the configured limit / one representative / disabled), camera distance (16–128, default 64 blocks), and a per-pipe rendered payload limit (1–512, default 64). All are client presentation options, not transport limits. Interface > Guide includes the existing per-book page-position memory toggle. Updates > General controls automatic checks and notifications; Updates > Actions performs manual checks and clears ignored versions separately from configuration transactions. These client preferences are live after saving.

World/Server > World Generation > Oil supports Custom alongside Standard, High and Very High. The custom slider ranges from 0 to 1 in steps of 0.01 (default 0.3), with a numeric input for exact values; only Custom uses its stored value. The generator continues to scale datapack rates relative to the stock 0.3 rate. World/Server > Operating Modes > Engines includes a separate explosion block-damage toggle. Disabling block damage does not prevent entity damage or the failed engine's removal. These world settings apply on world restart.

## Versioned addon schemas

For addons that change setting IDs or encodings, register an optional `ConfigSchema` **before** registering any definitions for its namespace and scope. Unversioned stored data is version 0. The list of migration callbacks contains one step per version increment (index 0 upgrades 0 to 1, index 1 upgrades 1 to 2, and so on). Existing addons can continue to register definitions without a schema; their storage and defaults are unchanged.

```java
config.registerSchema(new ConfigSchema("example", ConfigDefinition.Scope.SERVER, 2, List.of(
    values -> {
        String old = values.remove("old_capacity");
        if (old != null) values.putIfAbsent("tank_capacity", old);
    },
    values -> {
        // Example: convert the stored unit from buckets to millibuckets.
        if (values.containsKey("tank_capacity")) {
            long buckets = Long.parseLong(values.get("tank_capacity"));
            values.put("tank_capacity", Long.toString(Math.multiplyExact(buckets, 1000)));
        }
    }
)));
// Then register the new ConfigDefinition / ConfigKey with the same namespace and scope.
```

Callbacks receive only the addon's relative keys, in a mutable private map. Preserve unknown keys unless the migration deliberately removes them. Do not perform I/O, call the configuration service, or otherwise produce side effects from a callback. Steps must remain deterministic and historically stable across addon releases. Fresh stores begin at the current version without running old transformations. The client profile, global server defaults and each dedicated world store their versions independently as internal `@bcce.schema.<namespace>` properties; those properties are not configuration definitions and cannot be edited through the public value API or network. Integrated worlds use the migrated global server profile. Dedicated world migrations operate on the original world file's values before the overlay of migrated defaults. If an addon is absent, its stored values and version are retained without mixing different schema versions.

A throwing migration or invalid migration output does not publish its staging map. Invalid current values for registered versioned definitions prevent saving rather than being silently replaced by defaults; invalid migrated world files and newer schema versions fail loading without rewriting that world file. A schema that fails definition validation cannot be frozen or persisted. Each file is written with the registry's existing per-file replacement semantics; there is no cross-file transaction. Back up configuration before addon upgrades. Version checks require that the installed addon actually registers its schema; an older addon that never opted in cannot declare its supported version. Neither migration nor registration is allowed after the schema freezes. The migration extension is a default method on `ConfigurationService`, preserving existing implementers' binary linkage; runtimes without migration support explicitly reject it.

## Bounded remote configuration protocol

The server validates access before constructing a snapshot. A snapshot contains one revision and all server definitions, saved values and effective values. It is encoded with bounded field lengths and an **8 MiB aggregate encoded size limit**, and split into frames of at most 24,000 data bytes (plus a small header). A single large definition may span frames. A client accepts only the currently requested token, a bounded total size and strictly contiguous offsets; duplicate, out-of-sequence or inconsistent frames fail closed. No partial schema is exposed. Invalid/trailing data and duplicate IDs are rejected after assembly. Closing the screen, changing connection, cancellation and timeout discard pending data. The editor allows 200 ticks of inactivity and 2,400 ticks overall for a response.

Client writes still use a single optimistic-concurrency transaction; requests exceeding the conservative 24,000-byte bound must be divided into smaller user saves. The server enforces the same limit before processing changes, including malicious direct requests, and sends a small commit acknowledgement with the revision, rather than resending the entire schema. No setting is written while a snapshot is being assembled. A schema exceeding 8 MiB is explicitly rejected; this is bounded fragmentation, not an unbounded streaming protocol. The aggregate size cap applies to the serialized snapshot, not the size of the heap needed to build it.

This is an internal network format change. Client and server must use the same protocol generation. Forge/NeoForge target protocol versions have been incremented; Fabric payload IDs derive from the canonical target protocol version to keep incompatible payload formats separate. This does not change the public Java API major/minor version. Standalone tests exercise the real serializer and receiver; the final production loader build and in-game network verification remain necessary.
