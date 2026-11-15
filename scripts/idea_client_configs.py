#!/usr/bin/env python3
"""Generate loader-qualified IntelliJ IDEA client run configurations.

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

    @property
    def launcher_args(self) -> str:
        loader_arg = " -Loader fabric" if self.loader == "fabric" else ""
        return self.minecraft + loader_arg


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
    """Launch the Windows PowerShell JDK-selector, not the IDEA-selected Gradle JVM.

    On a multi-generation BuildCraft checkout IDEA cannot choose Java 17/21/25
    from one global Project SDK. GradleRunConfiguration bypassed build-all.ps1
    and started the 26.X build on Java 19, before the game's run task existed.
    Shell Script is bundled with IDEA; the same project launcher remains usable
    from PowerShell and its JDK selection is independent of the IDEA daemon.
    """
    name = escape(config.name, {'"': '&quot;'})
    args = escape(config.launcher_args, {'"': '&quot;'})
    return f'''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="{name}" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="$PROJECT_DIR$/run-client.ps1" />
    <option name="SCRIPT_OPTIONS" value="{args}" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="powershell.exe" />
    <option name="INTERPRETER_OPTIONS" value="-NoProfile -ExecutionPolicy Bypass -File" />
    <option name="EXECUTE_IN_TERMINAL" value="false" />
    <option name="EXECUTE_SCRIPT_FILE" value="true" />
    <envs />
    <method v="2" />
  </configuration>
</component>
''' 


def verify_config(path: Path, config: ClientConfig) -> None:
    """Compare launch settings, including the JDK-selecting entrypoint."""
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
        node.get("type") != "ShConfigurationType",
    )):
        raise ValueError(f"Wrong IDEA Shell Script client name/type in {path}")
    opts = {option.get("name"): option.get("value") for option in node.findall("option")}
    expected = {
        "INDEPENDENT_SCRIPT_PATH": "true",
        "SCRIPT_PATH": "$PROJECT_DIR$/run-client.ps1",
        "SCRIPT_OPTIONS": config.launcher_args,
        "INDEPENDENT_SCRIPT_WORKING_DIRECTORY": "true",
        "SCRIPT_WORKING_DIRECTORY": "$PROJECT_DIR$",
        "INDEPENDENT_INTERPRETER_PATH": "true",
        "INTERPRETER_PATH": "powershell.exe",
        "INTERPRETER_OPTIONS": "-NoProfile -ExecutionPolicy Bypass -File",
        "EXECUTE_SCRIPT_FILE": "true",
    }
    for key, value in expected.items():
        if opts.get(key) != value:
            raise ValueError(f"{path}: expected {key}={value!r}, found {opts.get(key)!r}")
    if node.find("ExternalSystemSettings") is not None:
        raise ValueError(f"{path}: Gradle runtime would bypass the JDK-selecting launcher")


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
        # Remove only our former Gradle runClient configurations or our Shell
        # Script launcher; preserve unrelated configurations.
        try:
            node = ET.parse(path).getroot().find("configuration")
            text = path.read_text(encoding="utf-8")
        except (ET.ParseError, OSError):
            raise ValueError(f"Refusing to delete unknown IDEA configuration: {path}")
        is_old_gradle = (node is not None and node.get("type") == "GradleRunConfiguration"
                         and re.search(r":\S+:(?:runClient)\b", text))
        is_our_script = (node is not None and node.get("type") == "ShConfigurationType"
                         and "run-client.ps1" in text)
        if not (is_old_gradle or is_our_script):
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
