# Optional Create ship physics compatibility

Create alone does not provide rigid-body ship mass. BCCE ships physics definitions for the engines used by Create Aeronautics (Sable) and legacy Create/Clockwork/Interactive installations (Valkyrien Skies 2). Neither physics mod is a required dependency of BCCE.

## Targets

| BCCE target | Sable profiles | Live Sable pipe/tank mass | VS2 static mass |
| --- | --- | --- | --- |
| 1.19.2 Forge | Dormant | No | Yes |
| 1.20.1 Forge | Dormant | No | Yes |
| 1.21.1 NeoForge | Yes | Yes, when Sable is loaded | No |
| 1.21.11 NeoForge, 26.X NeoForge/Fabric | Dormant data | No | No |

The latter targets are not claimed to support an installed physics engine. The shared data is preparation for future ports, not a declaration of runtime compatibility. All 35 solid registered block IDs represented by these profiles have a corresponding blockstate resource in every configured target. Fluid blocks are excluded from rigid-body mass definitions.

## Sable

Definitions are in `data/buildcraftcompat/physics_block_properties/<module>/<block>.json`. The default priority is 1100. A pack can override these definitions by using the same resource ID or a higher-priority definition for the same selector. Mass, buoyancy volume, inertia multipliers (where appropriate), friction, restitution and fragility are specified independently. Mass is always positive; lightweight internal blocks are not massless.

Representative values in Sable kpg: pipe (static fallback) 0.20, empty tank 0.50, laser 0.50, pump 1.50, distiller 2.00, filler/builder 2.50, quarry 4.00. Engines have independent state overrides for wood (0.50), stone (1.50), iron (2.00), creative (1.00), FE (1.50).

On 1.21.1 NeoForge, the `bc_physics_mass` property has 21 bounded classes. Pipe mass is 0.10 + 0.05 * class; tank mass is 0.50 + 0.10 * class. A straight dry wood pipe is approximately 0.10, stone 0.20, iron 0.25, gold 0.30, obsidian 0.35. Branch geometry, installed attachments/facades and wire parts contribute to the mass. Fluid contents use the fraction of capacity and relative density, normalized to a small pipe volume or a 0.45 cubic metre tank volume. NeoForge gas densities do not produce negative gravitational mass. Fluid tank calculations use the local tank only. The range and hysteresis deliberately bound updates and prevent tiny fluid variations causing unnecessary physics updates. Addon pipe definitions use a neutral dry mass by default.

The server recomputes on first tick after load and then at staggered 20-tick intervals, only with Sable present. A mass-class change updates the same block's state, retaining all other properties and its block entity; it does not request neighbour/redstone notifications. Sable's own block-state change path is responsible for updating the mass tracker and inertia. The property is always present on this target so registry layouts do not depend on the installed mod list. Saved worlds without a previously stored property use the default state and recalculate when Sable is loaded.

The physical volume and intrinsic inertia multipliers are static approximations. The bridge does not model individual item cargo, energy charge, or contents of other machine inventories. It does not project the world interactions of quarries, pumps, builders or robots into transformed ship coordinates, implement Create kinetic power conversion, or supply a new contraption movement engine. Ship assembly, rotation, reload, and real collision behavior require in-game verification with the installed Sable release. The patch assumes the earlier BCCE ship pipe-targeting fix is applied.

## Valkyrien Skies 2

Legacy Forge targets include `data/buildcraftcompat/vs_mass/buildcraft.json`. This uses the native VS2 array-of-block-definitions format with mass in kg, friction, and priority. Example: transport pipe 50 kg, empty tank 125 kg, pump 350 kg, quarry 1000 kg. These are game balance values relative to the VS2 mass tables, not a conversion of Sable units. The documented VS2 mass JSON is per block, so the legacy profiles do not pretend to supply dynamic BlockEntity mass or engine-type distinctions. No native VS2 injection or dependency is introduced.

## Reference implementations

- [Sable source and block-property loader](https://github.com/ryanhcode/sable): native properties, state conditions, and mass tracker updates on block state changes.
- [Mass & Weight Compatibility](https://github.com/khazoda/massweightcompat): Mekanism pipe/cable classes use quarter mass and quarter volume; lasers use half mass and volume; Digital Miner is in the super-heavy group. This is third-party compatibility, not a native Mekanism implementation.
- [Valkyrien Skies 2 Create definitions](https://github.com/ValkyrienSkies/Valkyrien-Skies-2/blob/1de3cc3c1d26caaafaf7fb9654d68be812d46213/common/src/main/resources/data/valkyrienskies/vs_mass/compat/create.json): examples include 750 kg fluid pipes, 850 kg mechanical pumps, 1100 kg presses/mixers, 5000 kg fluid tanks. BCCE uses an independent balance for its smaller hollow pipe geometry and its compact machines.

## Verification

Run `python -m unittest discover -s scripts/tests -p test_create_physics.py -v` from the repository root. The tests check the actual layer resolver, selectors/resources, legacy/modern profile isolation, mass formulas and the optional state bridge using Java contract doubles. They do not replace full Gradle compilation and actual ship tests.

For in-game verification, compare the mass of a ship with and without 100 straight pipes, then test wood versus gold/obsidian pipes; add/remove attachments and wire, fill/drain tanks, transfer fluid between tanks at opposite ends of a ship, save/reload and assemble/disassemble the ship. Repeat a base BCCE world without Sable and confirm ordinary pipe/tank behavior is unaffected. For legacy VS2, compare the static mass change upon adding/removing pipes. Use a clean test world and the relevant Create physics version for each loader.
