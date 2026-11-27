# Recipe viewers in development

Set `common.recipe_viewer_mod=jei`, `rei`, or `emi` in
`build-config/common.properties`. The default remains JEI. For a particular
Minecraft/loader target, set `target.<target>.recipe_viewer_mod` in
`build-config/targets.properties`; Gradle's `-Precipe_viewer_mod=rei` overrides
both. Only the chosen recipe viewer is added to the development runtime.
Compile-only integration dependencies are not packaged into the BCCE jar.

Examples (PowerShell, from the repository root):

```powershell
.\run-client.ps1 1.21.1 -GradleArgs @('-Precipe_viewer_mod=rei')
.\run-client.ps1 26.3 -Loader fabric -GradleArgs @('-Precipe_viewer_mod=rei')
```

The existing fixed `ci_runtime_profile=modpack` keeps JEI for reproducible
modpack tests. Forge and NeoForge also accept the explicit CI profiles
`jei`, `rei`, and `emi`. GameTest default profiles do not load a viewer.
Fabric's IT16 world-join fixture keeps JEI.

REI uses a native optional plugin, not JEI's plugin compatibility layer.
Machine displays read BCCE's live client recipe catalogue, including the
server's deny rules and oil-industry mode. The REI assembly transfer uses
REI's normal item-moving protocol (requires server-side support); the two
crafting blueprint transfers use BCCE's existing phantom-slot packets.

EMI selection launches EMI rather than providing a new native EMI plugin.
The 26.x targets use EMI - Port Forward. There is no verified published
1.21.11 NeoForge EMI artifact in this patch: set
`target.1.21.11-neoforge.deps.emi` or `-Pdeps.emi=<Maven coordinate>` if you
have a compatible build. That target's `runClient` fails explicitly rather
than silently running without a viewer.

REI uses standard REI widgets rather than the JEI-specific textures and
animations. Client-only dynamic machine displays do not provide persistent
REI serialization. Full dependency resolution and in-game acceptance are
required before release.
