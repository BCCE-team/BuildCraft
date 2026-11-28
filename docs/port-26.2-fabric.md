# Minecraft 26.2 Fabric port candidate

The new target is `26.2-fabric`, using the existing 26.X build generation and
Java 25. Shared gameplay and the public API are retained. The source layout is
26.X family + 26.2 family downport + Fabric platform + 26.X Fabric platform +
26.2 Fabric downport + target overlay. No full independent source copy is needed.

The native downport covers metadata, GUI input, oil world generation, fuel,
rendering/model interfaces, and vanilla entity/block interaction changes. The
26.3-only cushion interaction is excluded. Optional integrations are pinned
for Minecraft 26.2; they still require compilation and in-game validation.

The target participates in the existing client, CI, GameTest, release and
API ABI machinery. For the first release there is no historical 26.2 Fabric
JAR: the 26.3 Fabric API 2.1 baseline is an explicit reference, not a purported
historical 26.2 artifact. A real released 26.2 baseline can replace that
reference on the next release.

## Verification

Source-port candidate, not a compiled or runtime-certified release. The
preparation environment had JDK 21, not JDK 25, and could not resolve the
Fabric Maven host. Compilation, optional integrations, GameTests, world join,
rendering and cross-loader gameplay parity remain unverified. Run these tasks
before publishing:

```powershell
cd builds/26.X
.\gradlew.bat :26.2-fabric:buildAndCollect
.\gradlew.bat :26.2-fabric:runGameTestServer
.\gradlew.bat :26.2-fabric:runClient
```

Run the repository CI and the ABI and semantic-trace gates; do not treat source
materialization alone as evidence of a working mod.

## Compile feedback fix 02

The first client attempt got past dependency resolution and reported 457 Java
errors, largely cascading from missing internal item, fluid and energy types.
Fabric 26.2 now selects the required shared storage contracts independently of
NeoForge's 26.3 migration. The relocation and MJ bridges run without applying
26.3-only vanilla rewrites. The 26.2 native downport also uses the older PiP
bounds signature, delegates FakeWorld fuel values and brewing, and restores the
four-argument pipe destruction particles call. The NBT conflict GameTest is
registered on 26.3 only, matching the corresponding NeoForge suite.

The Fabric static materialization and source-family architecture gates pass;
219 gameplay GameTests are registered for 26.2 Fabric and 220 for 26.3 Fabric.
These are registration checks, not executed world tests. Real Java 25 Gradle
compilation and Minecraft startup still need verification on a machine with
resolved dependencies. Fix 02 includes the previous MezzConfig Maven filter fix.
