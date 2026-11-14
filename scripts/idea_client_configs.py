#!/usr/bin/env python3
"""Generate loader-qualified IntelliJ IDEA Gradle client run configurations.

The canonical target registry is the only source of versions, loaders, build
roots and Gradle task names. This avoids collisions when two loaders target the
same Minecraft release (for example 26.3 NeoForge and 26.3 Fabric).

    python scripts/idea_client_configs.py --check
    python scripts/idea_client_configs.py --write
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

from source_config import ROOT, load_properties, target_ids

RUN_DIRECTORY = ROOT / ".run"
LOADER_NAMES = {"forge": "Forge", "neoforge": "NeoForge", "fabric": "Fabric"}
CLIENT_PATTERN = "Run Client *.run.xml"


@dataclass(frozen=True)
class ClientConfig:
    target: str
    generation: str
    minecraft: str
    loader: str

    @property
    def name(self) -> str:
        return f"Run Client {self.minecraft} ({LOADER_NAMES[self.loader]})"

    @property
    def filename(self) -> str:
        return f"{self.name}.run.xml"

    @property
    def project_path(self) -> str:
        return f"$PROJECT_DIR$/builds/{self.generation}"

    @property
    def gradle_task(self) -> str:
        return f":{self.target}:runClient"


def client_configs(props: dict[str, str]) -> list[ClientConfig]:
    """Return one unique launch configuration per canonical (version, loader)."""
    result: list[ClientConfig] = []
    keys: set[tuple[str, str]] = set()
    for target in target_ids(props):
        prefix = f"target.{target}."
        minecraft = props.get(prefix + "deps.minecraft", "").strip()
        loader = props.get(prefix + "source.platform", "").strip()
        generation = props.get(prefix + "build.generation", "").strip()
        if not minecraft or loader not in LOADER_NAMES or not generation:
            raise ValueError(f"Incomplete IDEA client target metadata for {target}")
        if not re.fullmatch(r"[A-Za-z0-9._-]+", minecraft + generation):
            raise ValueError(f"Unsafe IDEA client target metadata for {target}")
        if f"{minecraft}-{loader}" != target:
            raise ValueError(f"Target {target} does not match Minecraft {minecraft} / loader {loader}")
        key = (minecraft, loader)
        if key in keys:
            raise ValueError(f"Duplicate IDEA client configuration: {minecraft} / {loader}")
        keys.add(key)
        result.append(ClientConfig(target, generation, minecraft, loader))
    if not result:
        raise ValueError("No canonical targets to generate IDEA clients")
    return result


def config_xml(config: ClientConfig) -> str:
    """Preserve IntelliJ's shareable GradleRunConfiguration XML schema."""
    name = escape(config.name, {'"': '&quot;'})
    project = escape(config.project_path, {'"': '&quot;'})
    task = escape(config.gradle_task, {'"': '&quot;'})
    return f'''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="{name}" type="GradleRunConfiguration" factoryName="Gradle">
    <ExternalSystemSettings>
      <option name="executionName" />
      <option name="externalProjectPath" value="{project}" />
      <option name="externalSystemIdString" value="GRADLE" />
      <option name="scriptParameters" value="" />
      <option name="taskDescriptions">
        <list />
      </option>
      <option name="taskNames">
        <list>
          <option value="{task}" />
        </list>
      </option>
      <option name="vmOptions" value="" />
    </ExternalSystemSettings>
    <ExternalSystemDebugServerProcess>false</ExternalSystemDebugServerProcess>
    <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
    <DebugAllEnabled>false</DebugAllEnabled>
    <RunAsTest>false</RunAsTest>
    <method v="2" />
  </configuration>
</component>
'''


def verify_config(path: Path, config: ClientConfig) -> None:
    """Compare semantic XML fields, not IDEA's formatting or option order."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"Invalid IDEA XML in {path}: {exc}") from exc
    if root.tag != "component" or root.get("name") != "ProjectRunConfigurationManager":
        raise ValueError(f"Unexpected IDEA configuration root in {path}")
    node = root.find("configuration")
    if node is None or any((
        node.get("name") != config.name,
        node.get("default") != "false",
        node.get("type") != "GradleRunConfiguration",
        node.get("factoryName") != "Gradle",
    )):
        raise ValueError(f"Wrong IDEA Gradle client name/type in {path}")
    opts = {option.get("name"): option for option in node.findall("ExternalSystemSettings/option")}
    for key, expected in (("externalProjectPath", config.project_path),
                          ("externalSystemIdString", "GRADLE")):
        if key not in opts or opts[key].get("value") != expected:
            raise ValueError(f"{path}: incorrect {key}: expected {expected}")
    tasks = [x.get("value") for x in opts.get("taskNames", ET.Element("missing")).findall("list/option")]
    if tasks != [config.gradle_task]:
        raise ValueError(f"{path}: expected only {config.gradle_task}, found {tasks}")


def validate_client_configs(directory: Path = RUN_DIRECTORY,
                            props: dict[str, str] | None = None) -> list[ClientConfig]:
    configs = client_configs(props if props is not None else load_properties())
    expected = {directory / c.filename: c for c in configs}
    actual = set(directory.glob(CLIENT_PATTERN))
    missing, obsolete = sorted(expected.keys() - actual), sorted(actual - expected.keys())
    if missing or obsolete:
        raise ValueError(
            "IDEA client run configs are out of sync with target registry: "
            f"missing={[p.name for p in missing]}, obsolete={[p.name for p in obsolete]}. "
            "Run: python scripts/idea_client_configs.py --write"
        )
    for path, config in expected.items():
        verify_config(path, config)
    return configs


def write_client_configs(directory: Path = RUN_DIRECTORY,
                         props: dict[str, str] | None = None) -> list[ClientConfig]:
    configs = client_configs(props if props is not None else load_properties())
    directory.mkdir(parents=True, exist_ok=True)
    expected = {directory / c.filename for c in configs}
    for path in directory.glob(CLIENT_PATTERN):
        if path in expected:
            continue
        # Remove only our Gradle runClient configurations; preserve custom IDEA
        # configurations even if their filenames happen to match this glob.
        try:
            node = ET.parse(path).getroot().find("configuration")
            text = path.read_text(encoding="utf-8")
        except (ET.ParseError, OSError):
            raise ValueError(f"Refusing to delete unknown IDEA configuration: {path}")
        if node is None or node.get("type") != "GradleRunConfiguration" or not re.search(
                r":\S+:(?:runClient)\b", text):
            raise ValueError(f"Refusing to delete non-BCCE IDEA configuration: {path}")
        path.unlink()
    for config in configs:
        (directory / config.filename).write_text(config_xml(config), encoding="utf-8", newline="\n")
    validate_client_configs(directory, props if props is not None else load_properties())
    return configs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true", help="validate committed IDEA client configurations")
    action.add_argument("--write", action="store_true", help="regenerate IDEA client configurations")
    args = parser.parse_args()
    try:
        configs = write_client_configs() if args.write else validate_client_configs()
    except (ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: {len(configs)} loader-qualified IDEA client configurations: " +
          ", ".join(f"{c.minecraft}/{c.loader}" for c in configs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
