# Live physical ship runtime compatibility

Targets: Forge 1.19.2 and 1.20.1 (Valkyrien Skies 2 / Clockwork / Interactive), NeoForge 1.21.1 (Sable / Create Aeronautics). Shared reach checks also materialize on newer targets; no new required dependency is introduced. This complements [the optional physics definitions and mass bridge](create-physics.md) and the existing pipe-hit handling. It is not a promise of support for regular Create moving contraption entities, which do not expose live world block entities in the same way.

## Coordinate contract

- Keep a block's storage/plot position for block entity lookup, inventories, pipe links, adjacent redstone, MJ/fluid/item connections, menu and packet identities.
- Project spatial reach and map coordinates into the physical world through the optional engine's own helpers. On VS2 use `VSGameUtilsKt.getShipManagingPos`, `toWorldCoordinates`, `squaredDistanceToInclShips`; on Sable use `Sable.HELPER.getContaining`, `projectOutOfSubLevel`, `distanceSquaredWithSubLevels`. These names were checked against the public repositories, not a running binary.
- Do not retain ship instances or positions across ticks. Existing distance limits, level checks, permissions, block identity and packet validation remain in force. On an installed but incompatible bridge, emit a warning once and reject spatial interactions / pause unsafe world operations; do not silently accept arbitrary plot positions.
- A native ticking ship should tick its own block entities. Do not add a second ticker or force-load its storage chunks. Revalidate cached neighbour identity on the supported ship targets. Refresh engine redstone from local neighbours on the normal server tick.

## Runtime policy

Local inventory and machine processing (electronic library, automatic workbench, distillers, heat exchange, tanks, engines, pipe connections) remains enabled and uses storage coordinates. Actual execution on ships must be verified in the game. Zone Planner requests and initial map center use projected world coordinates; map selections describe the stationary world, not a zone attached to the hull. Remote robot processor access preserves its existing controller checks.

Laser tables (assembly, advanced crafting, integration, charging, programming) open their normal menus. Their processing tick and laser/direct MJ input are suspended on a physical structure. Lasers clear the active beam rather than searching an incompatible coordinate space. Already stored ingredients/energy are retained; the paused GUI may not refresh crafting previews. Warning text is shown in tile GUIs and via the server-side open hook. The English key uses normal translation fallback.

Quarry, builder, filler, architect, mining well, pump, flood gate and robot processor work scheduling are suspended on physical structures pending full world-operation/robot navigation support. Their menus remain available. This is a conservative safety policy, not world-space mining or building support. Do not move an actively operating world-bound machine without stopping it first; on disassembly check/reconfigure its working area and persistent task coordinates. Old active chunk tickets and work reservations require separate live lifecycle testing. No cross-ship transfer, transformed lasers, mobile quarry frame, or ordinary Create entity contraption simulation is implemented.

## Verification

Run `python scripts/tests/test_ship_world_compat.py` and `python scripts/tests/test_create_physics.py`, then `./build-all.ps1 assemble --offline` or `./build-all.sh assemble --offline` with the appropriate JDKs. The coordinate tests use Java API doubles, not Minecraft or Create. The prior mass/volume definitions are not changed here.

Manual acceptance matrix for each supported physics engine:

1. Open each BCCE menu on a stationary, translated, rotating and moving ship; verify inventory actions, packets and closing after moving beyond reach. Verify wrong-dimension and removed-block denial.
2. Exercise Electronic Library upload/download, Auto Workbench with ingredients, all engine variants with switched local redstone, MJ dynamo, distillers, tank and pipe flow, heat exchange. Confirm server progress and GUI sync, not just animation.
3. Verify that all five laser table types show the warning, accept inventory edits but do not progress or receive direct laser power. Test disassembly and subsequent normal operation.
4. Check Zone Planner world map, height/layer requests, movement and reopening. Verify unloaded-world map requests never generate chunks.
5. Check world-bound machines and robot processor stop before world-changing operations. Stop machines before assembling, verify normal operation after disassembly and reconfiguration.
6. Save/restart/reassemble, compare local inventories and MJ/fluid contents, test absent engine and permissions. Repeat ordinary-world behavior with and without Create installed.

Upstream source contracts: https://github.com/ryanhcode/sable and https://github.com/ValkyrienSkies/Valkyrien-Skies-2 (branches 1.19.2/main and 1.20.1/main).
