# Public API v2 compatibility and release gates

BCCE 8.0.31 declares API 2.2.0. The compatibility baseline is BCCE 8.0.30
(API 2.1.0), tag commit `e86c762ed6a2ace283621343b236c6c12ab621b4`.
`build-config/api-v2-abi-baseline.json` pins the SHA-256 digests of the eight
published production JARs from the [8.0.30 release](https://github.com/BCCE-team/BuildCraft/releases/tag/8.0.30).
Do not replace these with JARs rebuilt from a branch or with the candidate JAR.

## Mandatory gates

`build-logic/api-v2-gates.gradle` is shared by Forge, NeoForge and Fabric.
`check`, and therefore `build` / `buildAndCollect`, requires the following production-JAR checks:

1. `verifyApi2AddonBinary` independently compiles all 18 materialized addon
   fixtures against the target's production JAR and Minecraft/loader compile
   dependencies. It rejects references to internal BuildCraft packages.
2. `verifyApi2Compatibility` downloads or reads the exact baseline for the same
   Minecraft/loader target and verifies its SHA-256. It scans public/protected
   JVM class, method and field declarations in `buildcraft.api.v2`, including
   nested types and compiler-generated bridges. It rejects removed symbols,
   changed descriptors/visibility/static status, new final/abstract restrictions,
   new abstract obligations, changes to direct inheritance, narrowed sealed
   hierarchies and changed inlined constants. Additive types, overloads, and
   default interface methods are allowed by the scanner.
3. The same gate compiles `Api21BinaryConsumer` against the historical JAR
   alone, runs it against that baseline, and runs the *same class files* against
   the candidate production JAR, with JVM verification enabled. It exercises
   the entry point/version, MJ value/result types, default interface methods and
   a precompiled implementation of `MjPort`. It checks which JAR actually
   supplied `BuildCraftApi`.

`verifyApi2Fixtures` depends on both verification tasks and `buildApiTestAddon`,
which builds the standalone runtime conformance addon for the same target.

Forge gates explicitly wait for `reobfJar` when that task exists. The comparison
is always same-target: it does not promise Minecraft or loader ABI portability.
A missing or invalid baseline, ABI violation, compiler failure or binary-consumer
failure fails the Gradle task. There is no skip-on-error mode.

### Local execution

For example, from the repository root with the required target JDK:

```sh
cd builds/26.X
./gradlew :26.3-fabric:verifyApi2Compatibility
```

The baseline download is cached at
`build/api-abi/baselines/8.0.30/BuildCraft-Community-Edition-8.0.30+<mc>+<loader>.jar`.
For an offline Gradle run, pre-populate that cache from the official release;
its digest is still checked. The first online run needs access to GitHub release
assets and the normal Gradle dependencies. Reports are written to
`build/api-abi/reports/<target>.json` with baseline/candidate digests and any
violations. No baseline JARs or generated classes belong in Git.

Offline regression tests for the checker itself use real compiled Java fixtures:

```sh
python -m unittest discover -s scripts/tests -p test_api_abi.py -v
python -m unittest discover -s scripts/tests -p 'test_api_*.py' -v
python -m unittest discover -s scripts/tests -p 'test_configuration_*.py' -v
```

The configuration suite is mandatory in GitHub CI and the local CI runner.
It is also required for publication through the shared CI workflow.

## Release acceptance

The eight-target matrix builds each production JAR, runs unit tests,
GameTests, IT16 client/server join/rejoin, and the installed dedicated-server
smoke test. It stages the ABI-verified JAR and report before the development
runtime checks, restores that exact JAR before the installed-server smoke, and
uploads the staged pair only after the runtime gates succeed. The aggregate
job validates exactly one JAR/report for every configured target, including its
SHA-256, in addition to the four semantic gameplay trace comparisons required from all eight targets, including Fabric. The separate [installed-server API conformance addon](api-v2-test-addon.md) must also pass against the same candidate JAR.

`publish.yml` invokes this same reusable CI workflow on the release tag, with the
release's pinned localization revision. It publishes the verified artifacts,
not a second independent build. All eight artifacts and their passing ABI
reports must be present and match before the first distribution upload.

## Scope and maintenance

The bytecode scanner is intentionally conservative: moving a declared member
into a superclass or changing direct interfaces is rejected even if some JVM
linkage cases would remain compatible. Review such changes explicitly instead
of relaxing the gate just to obtain a green build. This is not a complete
formal proof of Java source compatibility or semantic compatibility; in
particular it does not compare generic signatures, annotations, method bodies,
or every possible default-method conflict in external hierarchies. The runtime
consumer is a focused loader-independent smoke, not an installed third-party
Minecraft mod. The existing conformance and in-game gates remain required.

Once API 2.2 has actually shipped and passed these checks, extend the baseline
policy to preserve its newly introduced contracts in future releases. Do not
silently advance the only baseline and abandon earlier compatibility promises.
