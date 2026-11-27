# Independent API v2.2 installed-server conformance addon

The installed-server probe now lives **inside the addon fixture tree**, under
`addon-fixture/src/testaddon` and `addon-fixture/src/testaddon-loaders`.
It remains a separate, test-only mod with its own mod ID (`bcceapitest`),
loader metadata and entrypoint. It is not a BuildCraft module bundled into the
production JAR, does not use TestKit, and must never be uploaded as a
BuildCraft release artifact.

This keeps every third-party API check in one place:

- `addon-fixture/src/main/java` — compile-time public API fixtures;
- `addon-fixture/src/abi/java` — released-API binary compatibility fixture;
- `addon-fixture/src/testaddon` — installable live server smoke addon.

## Build and execute

`verifyApi2Fixtures` aggregates the compile-only gate, ABI gate and `buildApiTestAddon`. It is a mandatory dependency of `check` for all eight targets.
It invokes the target toolchain's `javac`, with no source lookup or annotation
processing. The compiler sees public API class files extracted from the actual
production JAR plus that target's Minecraft and loader dependencies. A dedicated
ZIP check rejects internal BuildCraft references and shaded game/BCCE classes.
The test mod contains a binding to the SHA-256 of the exact candidate JAR.

For a local example on the Forge 1.20.1 target (with its configured Java toolchain):

```sh
cd builds/old
./gradlew :1.20.1-forge:buildAndCollect
cd ../..
BCCE_API_TEST_ADDON="$PWD/build/addon-fixture/installable/1.20.1-forge/bcce-api-test.jar" \
  STONECUTTER_TARGET=1.20.1-forge BUILD_GENERATION=old \
  bash scripts/ci-server-smoke.sh
```

For Fabric, run `:26.3-fabric:collectFabricServerMods` before the installed-server
smoke as usual. The existing smoke script installs a clean real loader server,
checks the addon/candidate binding, copies both JARs into its isolated `mods`
directory, and seeds an old configuration key in global defaults and a world file.
Do not install this addon in normal player worlds; its purpose is testing.

## Unified fixture infrastructure

All three independent consumers now use the same fixture umbrella and helper logic. The compile-only and released-ABI consumers are not installed as game mods. Shared Python helpers centralise:

- fixture source discovery (new compile-only fixtures are picked up automatically, while missing baseline cases are rejected);
- validation that only `buildcraft.api.v2` is used;
- temporary public-bytecode extraction and filtering from the actual JAR under test (no published API-only artifact);
- common javac options, with source lookup and annotation processing disabled;
- bytecode-level checks that internal BCCE packages are not referenced, including during validation of the packaged probe.

The old top-level `api-test-addon/` tree is removed; the probe and its loader adapters live in the fixture tree.

This reduces drift between the compile-time and live-runtime gates while keeping
release packaging unchanged.

## Live assertions

Forge and NeoForge register through the common-setup work queue, and Fabric
through a dependent `ModInitializer`. All use the actual server-started event
and a loaded server world. The probe asserts:

- real ServiceLoader runtime, API version, lifecycle, and core/module features;
- actual item, fluid and energy adapter availability;
- registration and retrieval of addon module, pipe type, alias, provenance and
  signal channel; codec round trip and signal combination;
- schema registration, typed effective configuration access and rejection of
  registration after freeze; migrated world value 73 overrides global value 41;
- real MJ buffer simulation, insertion, extraction, capacity and snapshot restore;
- invocation of an independently registered permission provider and revocation
  of a callback-scoped world facade on the server thread;
- real robot and logistics lookup services, including in an empty world.

The server must log `BCCE_API_TEST REGISTERED v1` and `BCCE_API_TEST PASS v1`,
and reach its normal ready state while the process is alive. A failure marker,
fatal startup error, missing marker, timeout or mismatched candidate fails the
blocking CI job. The candidate JAR restored after userdev GameTests/world-join
checks is byte-for-byte the one used for this probe and verified CI artifact
upload. The independent release workflow builds new JARs with the pinned
release localization revision and rechecks ABI compatibility; it does not
publish this CI artifact or rerun the installed-server probe. The test addon
is staged separately and uploaded only as a diagnostic artifact.

## Scope and limits

This is an installed **dedicated-server** conformance smoke, not exhaustive
coverage of every API operation. It does not place an addon block, create a
custom robot in the world, simulate pipe transport, render a client UI, or
simulate connection/reconnect with the test addon installed. Keep the four mandatory cross-target semantic scenarios, other GameTests, fixture conformance, binary compatibility and client/world-join
checks enabled on all eight targets. Offline tests compile the probe with materialized API contracts
and stand-ins, check loader metadata and artifact binding; they are not live
Minecraft tests. Only the real target matrix establishes installed-server success.
