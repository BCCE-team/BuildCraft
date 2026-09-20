# BuildCraft build generations and hybrid source layout

BuildCraft Community Edition uses two independent Gradle/Stonecutter builds and one shared source repository.

The project has one gameplay goal across every supported target:

> **Different implementation. Indistinguishable BuildCraft.**

Minecraft versions, loaders and build toolchains are implementation details. Unless a deviation is explicitly documented, players should not be able to identify the target from BuildCraft gameplay, balance, persistence, UI behaviour, machine timing, routing, permissions or resource handling.

## Build generations

### `legacy`

Current targets:

- `1.19.2-forge`
- `1.20.1-forge`

Build root: `builds/legacy`

The legacy build owns ForgeGradle-era targets and currently uses its own Gradle 8 wrapper. `1.19.2-forge` remains the behaviour reference; newer implementations are not required to look identical in source.

### `modern`

Current targets:

- `1.21.1-neoforge`
- `1.21.11-neoforge`

Planned targets include 26.x NeoForge/Fabric and 1.21.11 Fabric.

Build root: `builds/modern`

The modern wrapper is independent from the legacy wrapper. It may move to a newer Gradle, Stonecutter or Java toolchain when future Minecraft/Fabric/NeoForge versions require it, without forcing those requirements onto the legacy build.

If a future generation becomes structurally incompatible with the current modern family, create another independent build generation instead of forcing every version through one wrapper or filling gameplay code with large condition blocks.

## Repository layout

```text
build-config/
├─ common.properties              shared mod metadata and source-root registry
├─ targets.properties             canonical per-target registry
└─ generations.properties         independent build-generation index

build-logic/
├─ common-target.gradle           shared target/materialization wiring
└─ loaders/                       Forge/NeoForge/Fabric build adapters

builds/
├─ legacy/                        legacy settings, controller and wrapper
└─ modern/                        modern settings, controller and wrapper

source-shared/
└─ src/                           files valid for every target

source-families/
├─ legacy/
│  └─ src/                        generation-wide legacy implementation
└─ modern/
   └─ src/                        generation-wide modern implementation

source-platforms/
├─ forge/
├─ neoforge/
└─ fabric/                        loader-wide implementation

source-family-platforms/
├─ legacy/
│  ├─ forge/
│  └─ fabric/
└─ modern/
   ├─ neoforge/
   └─ fabric/                     loader API tied to one source family

source-downports/
└─ modern/
   └─ 1.21.1/
      ├─ family/                  older Minecraft view of canonical modern Java
      └─ neoforge/                older NeoForge-specific view

version-src/
├─ 1.19.2-forge/
├─ 1.20.1-forge/
├─ 1.21.1-neoforge/
└─ 1.21.11-neoforge/               irreducible target-only files/resources
```

A target is materialized from the same five ownership layers. Older targets may additionally insert a downport view immediately after the family or family-platform owner:

```text
shared
→ family canonical
→ optional family downport
→ platform
→ family-platform canonical
→ optional family-platform downport
→ target escape hatch
```

Downports are not a new ownership axis: they are explicit older-Minecraft views of the canonical source owned by that family/family-platform. The modern canonical Java API is currently **1.21.11**. `1.21.1-neoforge` consumes explicit downports only where the canonical 1.21.11 implementation cannot be shared unchanged.

The generated effective tree is created under the target subproject's `build/effective-source` directory. It is build output, not authoritative source.

The Python side is deliberately split by responsibility:

- `scripts/source_layout.py` — generic layer resolution/materialization only;
- `scripts/source_config.py` — canonical target registry and layer ownership;
- `scripts/source_preprocessor.py` — `//?` condition parsing only;
- `scripts/transforms/` — path-independent mechanical Java/resource transforms only.

Class-specific Java rewriting is forbidden. `scripts/transforms/java_compat.py` may only perform mechanical API-shape/symbol conversion and must not name BuildCraft source files. Native 1.21.11 implementations live in maintained family/family-platform ownership; explicit 1.21.1 downports preserve the older modern target without making `version-src` an ownership axis.

## Placement rules

Choose the narrowest layer that represents the real reason for a difference.

### `source-shared`

Use for code and resources that are valid for every supported target.

### `source-families/<generation>`

Use for substantial Minecraft-generation differences shared by every target in one build generation. Examples include serialization models, registry architecture, networking generations or broad rendering/API changes.

### `source-platforms/<loader>`

Use for loader APIs and integration points that are reusable across source families, including:

- Forge, NeoForge or Fabric registration;
- capabilities or transfer APIs;
- loader lifecycle and events;
- loader networking setup;
- access transformers/access wideners;
- loader metadata and loader-specific compatibility glue.

Loader imports must not escape into `source-shared` or `source-families`. A Java file in `source-platforms/<loader>` must actually reference that loader API; loader-neutral Java stranded in a platform layer is rejected by the source-layout validator and must be promoted to shared/family ownership.

### `source-family-platforms/<family>/<loader>`

Use when code is genuinely loader-specific **and** tied to one source family. This is the normal home for a NeoForge/Fabric implementation whose API shape changes between `legacy` and `modern`. It overrides the generic platform layer without forcing a complete target copy.

### Loader-neutral network boundary

Gameplay and internal module code use `BCPacketContext` and `BCNetworkSide`. Forge and NeoForge packet contexts are converted exactly at the networking boundary by `ForgePacketContext` and `NeoForgePacketContext`; raw `NetworkEvent.Context`, `IPayloadContext` and loader-side enums must not leak into shared/family gameplay. The transport/registration implementation remains loader-owned.

### `source-downports/<family>/<minecraft>/...`

Use only when the canonical family/family-platform Java is written against the newest supported Minecraft API and an older target needs a substantially different complete implementation. Downports contain no `//?` directives and are selected by target configuration, not by loader predicates. Prefer a small compat facade or a short inline version condition when that is clearer than a full downport.

### `version-src/<target>`

Use only when a complete file or resource is genuinely target-specific and cannot remain readable in a family/platform layer. Target overlays should stay small and must not contain inline version conditions. For `1.21.11-neoforge`, the only remaining Java exception is the frozen API file `buildcraft/api/v2/recipe/CountedIngredient.java`; API restructuring is intentionally outside the architecture migration.

## Internal actors, permissions and client registration

`buildcraft.api` is unchanged. Permission decisions still call the existing API2
service. `BCPermissions` is an internal service integration, not a new claims
system; the saved owner UUID identifies the actor rather than restricting access
to a machine to its owner. The previous `AutomationPermissionUtil` remains a
compatibility facade for existing callers.

`BCActors` owns the per-world/per-profile actor cache. `PlatformActors` creates
Forge or NeoForge fake players; the 1.20+ no-sign-editor implementation and online
advancement-owner repair are retained. `withTool` scopes position and main-hand
state and restores them on nested calls or exceptions, without copying the tool
and losing legitimate durability changes. World unload evicts cached actors.
Native break/place protection events and canceled-placement snapshot restoration
belong in `PlatformWorldActions`.

`BCChunkTickets` owns work-area comparison, config decisions and release logic.
`PlatformChunkTickets` owns native callback registration and forcing. Ticket keys
remain the loading block position and the existing mod/controller ID. World
unload discards the in-memory mirror but must not unforce persistent tickets;
machine removal releases both old non-ticking and current ticking forms.

Client catalogues use `ClientRegistration`: menu-screen factories, entity/block
entity renderer factories, colours and applicable render-layer settings.
`PlatformClientRegistration` binds them to the native lifecycle. Forge screen
registration is queued exactly once; NeoForge's menu event is immediate. Native
event subscriber classes remain loader-owned and client-only.

Model and atlas implementations receive `ClientModelBaking` and `ClientAtlas`
views rather than loader event objects. Views keep the actual bake maps and
atlas instance; mutating a detached copy would silently lose model replacement.
On 1.21.11, `ClientStandaloneModel` rebinds its native lookup on each standalone
registration and resolves it from that generation's model manager. These views
replace registration plumbing, not the renderer/model algorithms. Loader-native
geometry loaders and fluid client extensions remain loader bindings by design.

Run `python -m unittest discover -s scripts/tests -p test_actor_client_boundaries.py -v`
for the four-target actor/ticket and client-registration contract suite. It uses
real boundary classes with small native API doubles and stored catalogue
snapshots; a real Gradle compile and client/server runtime acceptance are still
separate requirements.

## Whole-file source variants

A complete native implementation that belongs to a maintained family or family-platform layer but only exists from a Minecraft API boundary may use a first-line source selector:

```java
//? source if >=1.21.11
package buildcraft.example;
```

The selector is structural metadata, not emitted Java. If it is disabled for a target, materialization falls back to the next lower layer at the same logical path. A selected whole-file variant is already native to that Minecraft API and bypasses mechanical upgrade transforms.

Use whole-file selectors only in `source-families` or `source-family-platforms`. Do not put loader predicates in them; loader ownership is represented by the directory tree. Do not use them in `source-shared`, `source-platforms` or `version-src`.

This is intended for a genuinely different complete implementation. Small API differences still belong in localized `//? if ...` blocks, and repeated API cliffs should move behind a compat facade.

## Localized version conditions

Small Minecraft API differences may remain in a family or platform file with Stonecutter-style directives. The effective-source generator evaluates them for each target before compilation.

Example:

```java
//? if <1.20 {
player.level
//?} else {
/*?
player.level()
?*/
//?}
```

Use inline conditions for local differences such as:

- renamed methods, fields, constants or enum values;
- a small signature change;
- an added argument or import;
- a short alternative branch.

Do not use inline conditions for loader selection. Loader differences belong in `source-platforms` or `source-family-platforms`.

Current policy enforced by `scripts/validate-source-families.py`:

1. no loader conditions in shared/family gameplay source;
2. no inline conditions in target overlays;
3. large conditional counts are reported as architecture drift and belong in compat/family-platform implementations;
4. byte-identical overrides are rejected;
5. every configured target must successfully preprocess every conditional file;
6. `scripts/source_layout.py` is kept generic and may not contain BuildCraft-class-specific rewrites.

## Behaviour parity

`1.19.2-forge` is the reference implementation for player-visible behaviour.

Parity means equivalent observable results, not source-code identity. It includes:

- machine speed, costs, capacity and refunds;
- MJ generation, consumption and routing;
- item/fluid pipe routing and filtering;
- Builder, Filler and Quarry behaviour;
- robots, boards, stations and ownership identity;
- gates, wires, lasers and statements;
- save/load and chunk-unload persistence;
- permission/protection behaviour;
- blueprint contents and resource reservations;
- GUI semantics and user interactions.

A target may use different data components, registries, network APIs, events, capabilities or transfer systems as long as the observable BuildCraft behaviour remains equivalent.

### Blind-room criterion

> Put players on different supported BCCE targets in separate rooms, hide Minecraft/loader information, and give them equivalent BuildCraft scenarios. They should not be able to identify the target from BuildCraft behaviour alone.

A visible difference is either an intentional and documented vanilla-induced deviation or a parity regression.

## Build commands

The effective-source generator requires Python 3. Gradle, Java and loader toolchains remain isolated inside their respective build generation.

Build every generation with its own wrapper:

```text
./build-all.sh
```

Windows:

```text
build-all.bat
```

PowerShell:

```text
./build-all.ps1
```

Build only one generation:

```text
cd builds/legacy
./gradlew buildAndCollect
```

```text
cd builds/modern
./gradlew buildAndCollect
```

Run the active target of one generation:

```text
cd builds/legacy
./gradlew runActiveClient
```

```text
cd builds/modern
./gradlew runActiveClient
```

List configured targets:

```text
python scripts/validate-stonecutter.py --list-targets
python scripts/validate-stonecutter.py --list-targets --generation legacy
python scripts/validate-stonecutter.py --list-targets --generation modern
```

Validate the complete architecture:

```text
python scripts/validate-stonecutter.py
python scripts/validate-source-families.py
python scripts/validate-repository-cleanliness.py
python scripts/validate-behavior-parity.py
```

Materialize one target manually:

```text
python scripts/source_layout.py 1.20.1-forge --output build/manual/1.20.1-forge
```

## Adding targets

1. Add the target once to `build-config/targets.properties` and assign `build.generation`, `source.family` and `source.platform`.
2. Reuse the existing build-root selector; only add a new build root when the toolchain truly requires one.
3. Reuse `source-shared`, the family, platform and family-platform layers.
4. Add only irreducible files to `version-src/<target>`.
5. Prefer a small version condition or a focused compat facade over copying a large gameplay class.
6. A new loader gets a `build-logic/loaders/<loader>-target.gradle` adapter plus platform/family-platform implementations; gameplay code should not be copied.

Do not create another full source-tree copy for a new port.

## Internal Minecraft compatibility boundaries

`buildcraft.lib.compat.minecraft` is internal implementation code, not an addon API.
The public `buildcraft.api` tree is outside these internal compatibility-boundary rules.
The boundaries serve the modern target family; the legacy family keeps its established
effective-source layout.

| Boundary | Responsibilities and current consumers |
| --- | --- |
| `persistence` | `BCBlockEntity` owns the vanilla load/save callbacks. Machines implement `readData(BCValueInput)` and `writeData(BCValueOutput)`; `TileBC_Neptune` supplies common owner/item/tank/delta hooks. |
| `gui` | `BCContainerScreen` translates native input, `BCInputState` scopes modifiers, `BCWidgetInput` identifies internal recipe panels, and `BCGraphics`/`BCGuiTooltip` handle matrix, texture and tooltip submission differences. |
| `render` | `BCGeometryRenderer` dispatches immediate geometry on 1.21.1 and immutable captured geometry on 1.21.11. `BCRenderTypes` and `BCCamera` own their Minecraft API differences. |
| `registry` | `BCRegistrationScope` owns Minecraft property/identifier/name stamping; loader registration remains in its existing platform adapter. |
| `components` | `BCItemData` handles registry-aware optional item serialization; loader-specific fuel/tool queries are not moved into it. |
| `recipe` | `BCRecipeDisplays` centralizes native recipe-book traversal and crafting-display input access without changing recipe eligibility. |
| `world` | `BCWorldHeight` exposes minimum and exclusive maximum height without pulling client camera classes into server code. |

### Persistence rules

- Never override vanilla `loadAdditional` / `saveAdditional` in a modern BCCE machine.
  Their signatures and exactly-once superclass calls belong to `BCBlockEntity`.
- Keep the existing schema: flat data on 1.21.1; common root fields and `bc_legacy`
  machine data on 1.21.11. Pipe holders, oil springs and quarry drill collision
  entities explicitly retain their already-shipped root layouts.
- Nested BCCE codecs may use `BCValueInput.tag()` / `BCValueOutput.tag()` with the
  supplied registry provider. This is an intentional transition escape hatch,
  not permission to recreate vanilla callbacks throughout the modules.
- An early save without registry context must not fabricate registry-dependent
  data. Root serializers that already work without it explicitly opt out of that
  requirement; in particular unknown pipe payloads must not be erased.
- Network update callbacks are separate from disk persistence. Do not mechanically
  rename methods on native `ValueInput` to methods of `BCValueInput`.

### GUI and rendering rules

Use the shared input boundary rather than duplicating native mouse/key record
bridges in each container screen. Internal recipe panels implement `BCWidgetInput`;
vanilla controls use `GuiEventListener`. Modifier scopes restore the previous
state even on nested callbacks or exceptions.

Geometry-only machine renderers implement `renderContents`. Deferred submission
must consume the already-captured vertices, not a live block entity. Keep special
native Tank/Pipe/Robot/Zone Planner renderers: this boundary is not a replacement
for their specialized render-state logic or for native item/laser submissions.

Canonical-source downports in `scripts/transforms/java_symbols.py` are restricted
to two reviewed Minecraft type aliases. The transform requires a matching import,
ignores comments/string/character/text-block contents, and never rewrites a method
body semantically. Other API differences belong to explicit Java boundaries.

### Validation and architecture constraints

```text
python -m unittest discover -s scripts/tests -p test_minecraft_compat.py -v
python -m unittest discover -s scripts/tests -p test_gui_regressions.py -v
python scripts/validate-12111-parity.py
python scripts/validate-regressions.py
```

The executable Java probes compile maintained classes against offline API doubles.
They do not replace a full Gradle build, real world save/reload, client rendering,
or dedicated-server testing. CI explicitly installs Java 21 for these probes.

Modern canonical Java targets 1.21.11; the 1.21.1 implementation is selected
through explicit downport views. Materialization uses mechanical transforms and
native loader bindings rather than class-specific source rewrites. The frozen API
exception in the 1.21.11 target overlay is intentionally retained.

## Internal platform boundaries

`buildcraft.lib.platform` is an internal implementation boundary, not public API
v2. Contracts and content/config descriptors are shared or family-owned; native
bindings stay in `source-platforms/<loader>`.

| Area | Internal contracts | Native binding |
| --- | --- | --- |
| Items | `ItemStorage`, optional `MutableItemStorage` | `StorageAdapters`, `PlatformStorage` |
| Fluids | `FluidStorage<F>`, optional `FilteredFluidStorage<F>` | Lossless native carrier/boolean-action views |
| Energy | `EnergyStorage` (FE quantities, no unit conversion) | Native energy lookup/import/export |
| Events | `BCEvents` records and explicit tick phases | `PlatformEvents`, `PlatformClientEvents` |
| Content | `BCDeferredRegister`, `BCRegistryEntry`, `BCRegistryBinder`, `BCMenuFactory` | `RegistryBinding`, `PlatformMenus` |
| Configuration | `BCConfigSpec` schema and live value handles | `ConfigBinding` and native config events |

Storage adapters preserve native identity when unwrapped. This is required for
Tank/TankManager rollback and for NeoForge transactions; a new operation view must
not become a cache or invent a second transaction. Existing fluid carriers and
save formats are retained: `FluidStorage<F>` does not serialize fluid components
into a different representation. Provider capability exports, native item-fluid
containers, and Minecraft's native Fluid/FluidType classes remain loader-bound.
This boundary covers storage operations only; native storage types remain
loader-bound where required by the platform API.

Content catalogs preserve ID and definition order. Descriptor creation and bus
binding never execute a content factory. Factories run under native registry
lifecycle control; late definitions before the registration event remain allowed,
while the native freeze is still authoritative. The 1.21.11 registration scope
continues stamping block/item property IDs. Registry entries are not fake vanilla
`Holder` implementations.

Config builders record schema operations, including names, comments, ranges,
enum choices, defaults and world-restart flags. Binding creates one native spec;
value handles forward live getters so reload does not become a stale snapshot.
Module-specific reload filtering remains in the owning config class.

Gameplay tick/join/unload/watch callbacks receive normalized records and are
registered once. START and END phases are not coalesced: Transport's two distinct
phases are preserved. Client tick/login/logout bindings are client-gated; renderer,
model and screen registration remains separate. Permission actors, fake players,
and chunk-ticket management use their dedicated platform services.

Run `python -m unittest discover -s scripts/tests -p test_platform_boundaries.py -v`.
The test suite materializes all four supported targets, compiles the real boundary
classes against small native API doubles, and checks the supplied baseline's
config fingerprints and literal content-registration order. It is not a full
Forge/NeoForge build or dedicated-server/client integration test.
