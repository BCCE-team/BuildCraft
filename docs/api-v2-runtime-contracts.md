# API 2.2 runtime negotiation and threading

## Negotiating capabilities

`BuildCraftApi.runtime().features()` returns an immutable snapshot of installed capabilities. The declared feature level for each target is an upper bound. A built-in feature is advertised only when its required services and registries are present, and level 2 may fall back to level 1 when its extra dependencies are not installed. `TRANSFER` additionally requires all three platform transfer adapters. `PERSISTENCE` consists of standalone codec/value contracts and does not need a service. `CONFIGURATION` level 1 identifies the configuration service.

For optional modules, an unavailable feature is a normal result during bootstrap or in a partial installation. Do not cache early negative results indefinitely. Reacquire a snapshot after loader registration; a prior snapshot stays unchanged. After freeze, built-in service installation is closed and registry mappings cannot change. This does not imply that all registered content is usable, that a world exists, or that every operation is available in the current context. Continue to use typed `service`/`registry` lookup and per-operation results. Feature levels are not API version numbers or guarantees of cross-target behavioral parity.

```java
ApiRuntime runtime = BuildCraftApi.runtime();
if (runtime.features().supports(BuildCraftFeatures.PIPES, 2)) {
    PipeService pipes = runtime.requireService(BuildCraftServices.PIPES);
    // Use pipes on the owning game thread with a valid world and position.
}
```

The shared Lib `RuntimeFeatures` policy is applied by the old/1.21.X, 26.X NeoForge and 26.X Fabric runtime implementations. Missing optional backends suppress the corresponding flags, not startup. The TestKit remains an explicitly configured simulation; features supplied to its constructor are not automatically a guarantee that the fake runtime implements them.

For configuration registration, migrations, typed values and client display preferences, see [the configuration guide](configuration-v2.2.md). Same-target production-JAR compatibility is verified separately by the [ABI gates](api-v2-abi-gates.md).

## Thread ownership

| Operation | Contract |
| --- | --- |
| Runtime discovery, service/registry lookup, lifecycle and feature queries | Any thread in production. Querying a handle does not authorize using it off-thread. |
| Registry reads and iteration | Production registry methods synchronize access and return detached, immutable mapping/collection snapshots. Registered values are not deep-copied. |
| API registration, aliases, schema and external modules | Loader-controlled registration phase only. Finish all work before freeze; do not race registration with the loader lifecycle barrier. Complete multi-step registrations in order. |
| World/permission queries, pipe/MJ/fluid/item operations, robots, requests, machines, schematics, world generation | Owning logical game thread, including `SIMULATE` and read-only access to live state, unless a particular callback or contract specifies a different context (e.g. vanilla generation/reload workers). |
| Client presentation, UI, render callbacks | Owning client/render thread as appropriate. Never invoke client-only logic on a dedicated server. |
| Definition reload | Invoking reload phase; do not assume game-thread context. Treat published snapshots as immutable data and respect the ownership of values they contain. |
| Configuration reads | Synchronized effective-value and definition access in production; server values are meaningful only after world startup. Separate calls are not a transaction. |
| Configuration migration | Synchronous storage lifecycle callback; pure transformation of its supplied map. Do not call back into game/configuration services or wait on another thread. |
| Pure immutable values and documented immutable snapshots | May cross threads; mutable references contained in them keep their own lifetime/thread constraints. |
| Unspecified service/callback | Not assumed thread-safe. Invoke on the thread that owns the operation. |

`ApiLifecycle` is process-wide; opening or closing an integrated or dedicated server world is not an API re-bootstrap. `RUNNING` means registration completed, not that a world is loaded. Reload updates data through domain-specific services rather than reopening type registration. Do not retain a live world/pipe/robot handle across unload, disconnect or replacement unless its individual contract explicitly permits it.

These rules specify supported use, not universal runtime thread assertions. BCCE does not transparently schedule calls to Minecraft threads. Addons must schedule work via their loader/game environment and avoid blocking on callbacks into the same owner thread. A single synchronized registry call is safe; a compound sequence across multiple registries or services is not an atomic transaction. The existing loader barrier remains responsible for coordinating registration with freeze.
