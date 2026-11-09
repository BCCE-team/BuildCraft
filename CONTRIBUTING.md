# Contributing to BuildCraft Community Edition

Thank you for your interest in **BuildCraft Community Edition (BCCE)**! Contributions of all sizes are welcome: bug fixes, gameplay improvements, tests, documentation, translations, and compatibility work all help keep BuildCraft alive.

BCCE is an unofficial continuation of BuildCraft 8.0.0 that restores mechanics from BuildCraft 7.1.27 and adapts them to newer Minecraft versions. Our main rule is simple:

> **Different implementation. Indistinguishable BuildCraft.**

Unless a change is deliberately proposed and agreed upon, the same BuildCraft systems should behave consistently across supported Minecraft versions and loaders.

## Before you begin

- Check [existing issues](https://github.com/BCCE-team/BuildCraft/issues) and [pull requests](https://github.com/BCCE-team/BuildCraft/pulls) to avoid duplicating work.
- Use the [issue forms](https://github.com/BCCE-team/BuildCraft/issues/new/choose) to report bugs or suggest features. For larger changes, discuss the approach before spending time on an implementation.
- Small, self-contained fixes and documentation corrections can generally go straight to a pull request.
- Be respectful and constructive in discussions and reviews. Disagreement about implementation details is normal; personal attacks are not.

### What makes a useful bug report?

Include the Minecraft version, loader and its version, BCCE version, steps to reproduce, and expected versus actual behavior. Attach logs for errors, crash reports for crashes, and screenshots or recordings when the issue is visual. Mention whether the issue happens with BCCE alone or only in a particular modpack.

## Development setup

BCCE is a multi-version Java project with **three independent Gradle/Stonecutter build generations**. Use the tools required by the generation you are changing, rather than assuming one JDK works for everything.

| Build generation | Maintained targets | Java |
| --- | --- | --- |
| `old` | `1.19.2-forge`, `1.20.1-forge` | 17 |
| `1.21.X` | `1.21.1-neoforge`, `1.21.11-neoforge` | 21 |
| `26.X` | `26.1.2-neoforge`, `26.2-neoforge`, `26.3-neoforge` | 25 |

You will also need **Python 3.11+** for the local CI runner. Use the Gradle Wrapper included with each build generation; there is no need to install a system-wide Gradle version.

```bash
git clone https://github.com/BCCE-team/BuildCraft.git
cd BuildCraft
python scripts/validate-stonecutter.py --list-targets
```

Build all generations with the appropriate script for your operating system:

```bash
./build-all.sh
```

On Windows, use `build-all.bat` or `./build-all.ps1` from the repository root. To build one generation, run its wrapper from the relevant directory, for example:

```bash
cd builds/1.21.X
./gradlew buildAndCollect
```

See [SOURCE_FAMILIES.md](SOURCE_FAMILIES.md) for the full source layout, toolchain separation, materialization rules and additional commands.

## Where changes belong

**Do not assume `version-src/` is the main source directory.** Effective target sources are assembled from multiple maintained layers:

| Location | Use it for |
| --- | --- |
| `source-shared/` | Code and resources shared by every target |
| `source-families/<family>/` | Minecraft-generation-specific implementations |
| `source-platforms/<loader>/` | Loader-specific integrations shared between families |
| `source-family-platforms/<family>/<loader>/` | Code specific to both a source family and a loader |
| `source-downports/<family>/<version>/` | Explicit older-version implementations when a small compatibility change is insufficient |
| `version-src/<target>/` | Exceptional files that cannot reasonably be owned by a broader layer |

Build targets and their properties are registered in `build-config/targets.properties`. Generated files under `build/effective-source` are **build output** and must not be edited or committed as source.

In particular:

- Keep loader imports out of shared and family gameplay code.
- Prefer a small compatibility boundary or a localized version condition over copying an entire class for each target.
- Use `//?` version conditions only where permitted by the source-layout rules. Do not use loader-selection conditions as a substitute for platform layers.
- Do not add identical target overlays, large source-tree copies, or class-specific rewriting rules to the generic source materializer.
- If you change how a system behaves on one target, check the equivalent behavior on other maintained targets.

For detailed placement requirements and exceptions, follow [SOURCE_FAMILIES.md](SOURCE_FAMILIES.md) rather than creating new conventions in a PR.

## Gameplay, API and compatibility expectations

BCCE preserves the recognizable gameplay of classic BuildCraft. **Minecraft 1.19.2 Forge is the current reference for player-visible behavior**, although modern targets may need very different code to achieve the same result.

When editing machines, pipes, energy, robotics, or building systems, consider:

- Operation timing, energy costs, resource handling, and routing behavior.
- Inventory and fluid safety: no duplication, silent deletion, or unintended overflow.
- Persistence across save/load, chunk unloading, and world restarts.
- Multiplayer synchronization, server-side authority, and permissions/land protection.
- GUI behavior, rendering, and client/server separation.
- Integration with JEI, Jade, and other applicable mods.

**Do not silently rebalance or redesign established mechanics.** If a change intentionally differs from the original behavior or from other BCCE targets, explain the reason in the PR and document it where appropriate.

### Public API

The supported addon-facing API is **`buildcraft.api.v2`**. Internal implementation packages are not part of the public compatibility guarantee. Prefer the existing public contracts instead of making addons depend on internal classes, and discuss potentially breaking API changes before implementing them. When updating the API, update relevant tests and documentation as well.

### Localization

Non-English translations are maintained in the separate [BCCE Localizations repository](https://github.com/BCCE-team/Localizations) and staged into BCCE builds. Please submit translation additions and fixes there. The original English text and guide metadata belong to the main repository.

## Testing your changes

Before opening a PR, run the checks relevant to your change and, whenever practical, the full local validation suite.

**Repository validation (quick first pass):**

```bash
python scripts/validate-stonecutter.py
python scripts/validate-source-families.py
python scripts/validate-behavior-parity.py
python scripts/validate-repository-cleanliness.py
```

**Local CI on Windows (PowerShell):**

```powershell
./run-ci-local.ps1 -ValidateOnly
./run-ci-local.ps1
```

The first command runs validators and Python-based tests only; the second also builds and runs GameTests for maintained targets. You can inspect available stages with `./run-ci-local.ps1 -ListSteps`. Local logs are saved under `logs/ci-local/`.

**Important:** Client and dedicated-server smoke tests run in GitHub CI, not in the Windows local CI runner. A successful local run is valuable, but it does **not** replace those checks or manual gameplay testing.

For gameplay fixes, add or update a regression test when feasible. For rendering, GUI, and interaction changes, include screenshots or a short recording and test in-game. If you could not run a relevant test, **say so in the PR** instead of claiming that everything passed.

## Submitting a pull request

1. Fork the repository, create a branch from the current default branch, and make one focused change.
2. Follow the existing code style and place files in the correct source layer.
3. Add or update tests, documentation, recipes, assets, or Guide Book content when the change requires them.
4. Run relevant validation and build steps. Avoid unrelated formatting changes, generated artifacts, and wholesale rewrites.
5. Open a PR against [BCCE-team/BuildCraft](https://github.com/BCCE-team/BuildCraft/pulls) with a clear title and description. Link the related issue, if any.
6. Respond to review feedback and keep the PR updated. Maintainers may request additional testing, changes, or a smaller scope before merging.

A useful PR description explains:

```markdown
## Summary
What changed, and why?

## Affected targets
Which Minecraft versions/loaders were tested or may be affected?

## Testing
- [ ] Relevant validators
- [ ] Build / GameTests
- [ ] In-game testing (if applicable)

## Compatibility and parity
Any behavior, API, save-data, or mod compatibility changes?

## Evidence
Screenshots, recordings, logs, or reproduction steps if useful.

## Related issues
Closes #...
```

**Keep PRs reviewable.** Do not combine unrelated fixes, submit repeated near-identical PRs, or make large automated changes without examining their effects. If you use AI-assisted tools, you are still responsible for understanding, reviewing, and testing the submitted code. Unverified bulk-generated changes are not a substitute for a tested contribution.

Please do not weaken CI checks or remove regression tests just to make a PR pass. If an existing test is incorrect, explain why and replace it with a valid check.

## Licenses and attribution

Review the repository's [LICENSE.txt](LICENSE.txt) (Mozilla Public License 2.0) and preserve copyright and license notices already present in the codebase. BCCE incorporates work originating in earlier BuildCraft releases, which may carry additional notices or applicable licensing requirements.

Only contribute code, artwork, text, or other assets that you have the right to submit. Identify reused third-party material and its source/license in the PR. Do not copy resources from other projects without checking that their licenses permit the proposed use.

## Need help?

If you are unsure where a fix belongs or whether a change fits the project, open a [GitHub issue](https://github.com/BCCE-team/BuildCraft/issues) or ask in the [community Discord](https://discord.gg/PTy9m4wxj3) before starting a large PR.

Thanks for helping make BuildCraft Community Edition better. Every well-tested fix, useful report, and thoughtful contribution matters. ❤️
