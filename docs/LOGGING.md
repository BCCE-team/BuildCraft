# Logging and diagnostics

BuildCraft Community Edition (BCCE) provides four logging profiles for different diagnostic needs. The default profile keeps player and server logs focused on failures that may require attention; higher profiles expose progressively more information for reproducing and investigating defects.

This page covers configuration, log contents, robot tracing, troubleshooting, and conventions for contributors. The profiles apply to **BuildCraft's logging pipeline**, not to messages written by Minecraft, mod loaders, or other mods.

## Quick start

For normal gameplay, leave **Logging Profile** set to `DEFAULT`. When investigating a problem:

1. Open the BCCE settings editor on your loader and select **BCCE > World/Server > Diagnostics** (see [Configuration](#configuration)). On a dedicated server, edit its world configuration while it is stopped or use the authorized in-game editor.
2. Select the profile appropriate to the problem using the table below.
3. Save with Done and reopen the world or restart the dedicated server; these settings are world-restart-bound. A process restart is necessary to change JVM/environment overrides.
4. Reproduce the issue, retain the resulting `latest.log` (and any relevant crash report), then return to `DEFAULT`.

Do not enable the most detailed profiles permanently on busy servers: the additional messages can increase log volume and diagnostic overhead.

## Logging profiles

| Profile | Intended use | Included BuildCraft messages |
| --- | --- | --- |
| **Default** (`DEFAULT`) | Normal play, production servers, ordinary bug reports | `WARN`, `ERROR`, and `FATAL`: failures and important warnings. Routine information and expected recovery events are omitted. |
| **Debug** (`DEBUG`) | Reproducing intermittent failures, compatibility problems, synchronization issues | Default messages, `DEBUG` messages, and diagnostic notices about fallbacks, recoveries, and expected transient conditions. |
| **Hard debugging** (`HARD_DEBUGGING`) | Investigating machines, automation, tasks, and robot behavior | Debug messages plus meaningful lifecycle and state-transition events emitted by instrumented systems. |
| **Verbose** (`VERBOSE`) | Short, focused investigations requiring maximum general detail | All of the above plus `TRACE` and unsampled general diagnostics. Robot per-tick tracing remains separately controlled. |

Profiles are cumulative. Moving to a more detailed profile does not remove errors or warnings from the lower profiles. The table describes the levels and event categories supported by the logging system; not every component currently emits every possible diagnostic event.

### Choosing a profile

- **A machine stopped or a recipe failed:** start with `DEFAULT`; use `DEBUG` if the log does not explain the failure.
- **A fallback, missing client tile, or inconsistent synchronization:** use `DEBUG` to distinguish expected recovery from an actual defect.
- **A robot cannot find work, repeatedly changes tasks, or fails to dock:** use `HARD_DEBUGGING` to capture important transitions. Enable *Verbose Robot Logging* only when those events are insufficient.
- **A problem occurs inside a fast or repeated operation:** use `VERBOSE` for a brief reproduction, then switch back. Avoid sharing unnecessarily large logs.

## Configuration

### In-game configuration

Logging settings are owned by **BuildCraft Core** and exposed in the shared BCCE configuration editor, under **World/Server > Diagnostics** in the BCCE view. They are server-scoped and use the `WORLD_RESTART` policy.

| Platform | Configuration entry |
| --- | --- |
| Forge / NeoForge | Native **Mods > Config** entrypoint opens the BCCE editor |
| Fabric 26.3 | Optional Mod Menu configuration entrypoint opens the same editor |
| All targets | In a world, assign and use the **Open BuildCraft settings** key binding (unbound by default) |

The Diagnostics category contains two independent settings: **Logging Profile** and **Verbose Robot Logging**. The latter is described in [Robot tracing](#robot-tracing). In multiplayer only an authorized operator can read and modify the remote server's settings; they do not configure the local client's installation.

### Configuration file

For integrated singleplayer use `config/buildcraft/server-defaults.properties`. For an existing dedicated-server world use `<world>/buildcraft/configuration.properties`; its installation's `config/buildcraft/server-defaults.properties` is the template for new worlds. Stop the world/server before editing files directly.

```properties
buildcraftcore\:debug/loggingProfile=DEFAULT
buildcraftcore\:debug/verboseRobotLogging=false
```

The escaped colon is Java properties syntax for a literal colon in the setting ID. Accepted profile values are `DEFAULT`, `DEBUG`, `HARD_DEBUGGING`, and `VERBOSE`. Preserve other entries in the file. The legacy `config/buildcraftcore-common.toml` is imported as a migration input; existing new-format values take precedence. See [BCCE Configuration](configuration-v2.2.md) for the storage and authorization rules.

### Temporary launch overrides

A Java system property takes priority over the Core configuration:

```text
-Dbuildcraft.log.profile=hard-debugging
```

The `BUILDCRAFT_LOG_PROFILE` environment variable is also supported. Valid values are case-insensitive; `hard-debugging`, `hard_debugging`, and `HARD_DEBUGGING` are equivalent.

The effective selection is resolved in this order:

1. `-Dbuildcraft.log.profile=...`
2. Legacy `-Dbuildcraft.debug=...` setting, when it specifies a supported mode
3. `BUILDCRAFT_LOG_PROFILE`
4. `buildcraftcore:debug/loggingProfile` in the active BCCE server configuration
5. `DEFAULT`

A supported external override takes precedence over the saved configuration choice until the process is restarted without that override. An invalid explicit profile is reported and uses `DEFAULT` for that process.

## Robot tracing

`verboseRobotLogging` is a **separate switch**, disabled by default, including when the general profile is `VERBOSE`.

| General profile | Verbose Robot Logging | Robot diagnostics |
| --- | --- | --- |
| `DEFAULT` or `DEBUG` | `false` | No ordinary robot lifecycle trace from the dedicated robot-trace facility |
| `HARD_DEBUGGING` or `VERBOSE` | `false` | Meaningful robot transitions, including available job, docking, and task events; repetitive waits and per-tick traces are suppressed |
| Any profile | `true` | Dedicated robot tracing also includes repeated waits, movement-related events, and per-tick AI traces |

The robot switch may generate substantial output and should be enabled only when diagnosing robotics behavior. It does not require changing the general profile. Other significant warnings and errors continue to follow their normal severity rules.

## Message classification and noise control

BuildCraft uses both conventional Log4j severity levels and diagnostic event categories:

| Message type | When to use it | First visible in |
| --- | --- | --- |
| `ERROR` / `FATAL` | Failed functionality, an unrecoverable state, or an exception requiring investigation | `DEFAULT` |
| `WARN` | A real unexpected condition or significant recoverable failure | `DEFAULT` |
| Diagnostic fallback / recovery | An expected transient condition, a safe fallback, or an operation successfully recovered | `DEBUG` |
| Lifecycle event | A meaningful start, completion, assignment, or state transition | `HARD_DEBUGGING` |
| Repeated low-level tracing | Fine-grained operation details, where a producer provides them | `VERBOSE` (or the independent robot switch for robot traces) |

For example, a client tile update can arrive after its target chunk has unloaded. When the update can safely be ignored, this is a **diagnostic**, not a player-facing warning. An invalid packet that indicates an actual fault can still be reported as `WARN` or `ERROR` as appropriate.

### Repeated messages

For messages explicitly written through `BCLog.diagnosticSampled(...)` or `BCLog.warningSampled(...)`, BuildCraft tracks occurrences by a **stable category**:

- The first **three** occurrences are emitted.
- After that, **every 128th** occurrence is emitted.
- Under `VERBOSE`, these sampled paths emit every occurrence.
- This sampling applies only to messages using the sampled APIs; it does **not** generally suppress every warning or error. Ordinary errors remain visible.

Categories must not contain block positions, coordinates, player names, or raw packet contents. Such values would defeat the sampling mechanism and multiply the number of counters.

## Producing a useful bug report

When reporting a BCCE issue, include:

1. **Version and loader:** for example, the BCCE build, Minecraft version, and Forge/NeoForge/Fabric version.
2. **Reproduction:** the smallest sequence of actions that triggers the problem, with expected and actual behavior.
3. **Logging settings:** selected profile, whether `verboseRobotLogging` was enabled, and any JVM/environment overrides.
4. **Logs:** `logs/latest.log` from the affected client or server; include `debug.log` and a crash report if present and relevant.
5. **Environment:** other mods involved, especially if the issue is caused by an interaction between mods.

Use `DEFAULT` first. If that log does not contain enough context, retry with `DEBUG` or `HARD_DEBUGGING`. Use `VERBOSE` or robot per-tick tracing only for a short reproduction. Review logs for personal or server-sensitive information (such as addresses, tokens, or player information) before sharing them publicly.

## Contributor guidelines

New logging should communicate **what happened and why it matters**, rather than merely recording that a method was called.

- Choose `WARN` / `ERROR` for actionable or unexpected failures; do not demote real faults merely to silence them.
- Use `BCLog.diagnostic(...)` for recoveries and benign fallbacks that matter during troubleshooting but should not appear in ordinary player logs.
- Use `BCLog.event(...)` for meaningful transitions in machine, routing, and task lifecycles. Emit once per transition, not once per tick while a state is unchanged.
- Use `BCLog.diagnosticSampled(...)` or `BCLog.warningSampled(...)` for recurring events that need bounded output. Assign a stable, low-cardinality category.
- Guard expensive diagnostic computations, string formatting, and world/entity traversal behind a profile or tracing check when necessary.
- Include useful identifiers and relevant context in messages; preserve exception causes when logging failures. Avoid logging secrets or complete user-controlled payloads.

Examples (simplified):

```java
// Expected transient condition; hidden in Default and sampled in Debug.
BCLog.diagnosticSampled("tile-update-missing",
    "Skipped update for an unavailable or incompatible tile at {}", pos);

// Meaningful state change; visible from Hard debugging upward.
BCLog.event("Robot task {} completed for {}", taskId, robotId);

// Real problem; visible even in Default.
BCLog.logger.error("Unable to restore task {}", taskId, exception);
```

Existing subsystem-specific debugging switches are separate compatibility mechanisms; do not introduce new log spam through an unconditional `logger.info(...)` call when a semantic diagnostic/event API is more appropriate.

## Troubleshooting and limitations

**Changing the profile has no apparent effect.** Check the effective value in the BCCE world/server configuration, then look for `-Dbuildcraft.log.profile`, `-Dbuildcraft.debug`, or `BUILDCRAFT_LOG_PROFILE` overrides. Restart after modifying the setting. The profile does not control logging from other mods or Minecraft itself.

**The log contains repeated warnings even in Default.** Identify the logger that emitted them. Messages written directly by Minecraft, the mod loader, or another mod are outside the BuildCraft profile filter. A vanilla pick-block warning involving `quarry_drill_collision`, for example, needs a fix in the block interaction rather than a BuildCraft log-level change.

**Hard debugging is not enough to explain a robot problem.** Enable `verboseRobotLogging` temporarily and reproduce the issue on the affected side, usually the server. Disable it afterward to avoid per-tick output accumulating indefinitely.

**Migration from earlier experimental builds.** The retired `config/buildcraft-logging.properties` file is no longer read. Transfer its `profile` value once to `[debug] loggingProfile` in `config/buildcraftcore-common.toml` or select the profile through the BCCE editor. Legacy `-Dbuildcraft.debug` flags remain supported for compatibility, but new workflows should use `loggingProfile` or `-Dbuildcraft.log.profile`.
