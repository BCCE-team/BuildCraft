"""GameTest API materialization for registry-based Minecraft targets.

Minecraft 1.21.11 uses test-function and test-instance registries instead of the
annotation scanner used by pre-1.21.11 NeoForge targets. Maintained GameTest bodies stay
shared; this transform removes only the obsolete registration annotations and
emits enough generated metadata to register the same methods through the current
registries.
"""
from __future__ import annotations

import json
from pathlib import Path
import re

from source_preprocessor import version_tuple

_MARKER = "// bc-gametest-v2: "
_IMPORT_RE = re.compile(r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;\s*$")
_PACKAGE_RE = re.compile(r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;")
_CLASS_RE = re.compile(r"\b(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_$][\w$]*)\b")
_STRING_CONSTANT_RE = re.compile(
    r"(?m)^\s*(?:(?:public|protected|private)\s+)?static\s+final\s+String\s+"
    r"([A-Za-z_$][\w$]*)\s*=\s*(\"(?:\\.|[^\"\\])*\")\s*;"
)
_METHOD_RE = re.compile(
    r"\s*public\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*GameTestHelper\s+[A-Za-z_$][\w$]*\s*\)"
)


def _split_named_args(body: str) -> dict[str, str]:
    parts: list[str] = []
    start = 0
    depth = 0
    quote: str | None = None
    escape = False
    for index, char in enumerate(body):
        if quote is not None:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                quote = None
            continue
        if char in ('"', "'"):
            quote = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(body[start:index].strip())
            start = index + 1
    parts.append(body[start:].strip())

    values: dict[str, str] = {}
    for part in parts:
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"unsupported positional GameTest argument: {part!r}")
        name, value = part.split("=", 1)
        values[name.strip()] = value.strip()
    return values


def _annotation_end(text: str, open_paren: int) -> int:
    depth = 1
    quote: str | None = None
    escape = False
    index = open_paren + 1
    while index < len(text):
        char = text[index]
        if quote is not None:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                quote = None
        else:
            if char in ('"', "'"):
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return index + 1
        index += 1
    raise ValueError("unterminated @GameTest annotation")


def _java_string_value(literal: str) -> str:
    # The maintained template constants are ordinary Java string literals. JSON
    # decoding handles the escape subset they use and keeps generated output stable.
    try:
        return json.loads(literal)
    except json.JSONDecodeError as exc:
        raise ValueError(f"unsupported Java string literal in GameTest metadata: {literal}") from exc


def _qualify_expression(expr: str, imports: dict[str, str], local_strings: dict[str, str]) -> str:
    expr = expr.strip()
    if expr in local_strings:
        return json.dumps(local_strings[expr])
    if expr.startswith('"'):
        return expr
    match = re.fullmatch(r"([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)", expr)
    if match and match.group(1) in imports:
        return f"{imports[match.group(1)]}.{match.group(2)}"
    return expr


def transform_gametest_source(text: str, *, minecraft: str, relative: str) -> str:
    if version_tuple(minecraft) < version_tuple("1.21.11"):
        return text
    normalized = relative.replace("\\", "/")
    if not normalized.startswith("src/gametest/java/") or not normalized.endswith(".java"):
        return text
    if "@GameTest(" not in text:
        return text

    package = _PACKAGE_RE.search(text)
    clazz = _CLASS_RE.search(text)
    if package is None or clazz is None:
        raise ValueError(f"{relative}: cannot resolve package/class for GameTest registration")
    owner = f"{package.group(1)}.{clazz.group(1)}"

    imports = {qualified.rsplit(".", 1)[-1]: qualified for qualified in _IMPORT_RE.findall(text)}
    local_strings = {
        name: _java_string_value(literal)
        for name, literal in _STRING_CONSTANT_RE.findall(text)
    }

    holder = re.search(r"@GameTestHolder\(\s*([^\)]+?)\s*\)", text)
    if holder is None:
        raise ValueError(f"{relative}: GameTest class is missing @GameTestHolder")
    test_namespace = _qualify_expression(holder.group(1), imports, local_strings)

    prefix = re.search(r"@PrefixGameTestTemplate\(\s*(true|false)\s*\)", text)
    if prefix is None or prefix.group(1) != "false":
        raise ValueError(f"{relative}: only @PrefixGameTestTemplate(false) is supported")

    # Remove imports and class-level annotations that no longer exist in 1.21.11.
    text = re.sub(r"(?m)^\s*import\s+net\.minecraft\.gametest\.framework\.GameTest\s*;\s*\n?", "", text)
    text = re.sub(r"(?m)^\s*import\s+net\.neoforged\.neoforge\.gametest\.GameTestHolder\s*;\s*\n?", "", text)
    text = re.sub(r"(?m)^\s*import\s+net\.neoforged\.neoforge\.gametest\.PrefixGameTestTemplate\s*;\s*\n?", "", text)
    text = re.sub(r"(?m)^\s*@GameTestHolder\([^\n]*\)\s*\n", "", text)
    text = re.sub(r"(?m)^\s*@PrefixGameTestTemplate\([^\n]*\)\s*\n", "", text)

    out: list[str] = []
    cursor = 0
    pattern = re.compile(r"(?m)^(?P<indent>[ \t]*)@GameTest\(")
    while True:
        match = pattern.search(text, cursor)
        if match is None:
            out.append(text[cursor:])
            break
        open_paren = match.end() - 1
        end = _annotation_end(text, open_paren)
        body = text[open_paren + 1:end - 1]
        args = _split_named_args(body)
        required = {"templateNamespace", "template", "timeoutTicks"}
        missing = required - set(args)
        if missing:
            raise ValueError(f"{relative}: GameTest metadata missing {sorted(missing)}")

        method_match = _METHOD_RE.match(text, end)
        if method_match is None:
            preview = text[end:end + 160].replace("\n", " ")
            raise ValueError(f"{relative}: GameTest annotation is not followed by a static GameTestHelper method: {preview}")
        method = method_match.group(1)
        timeout = int(args["timeoutTicks"], 10)
        payload = {
            "test_namespace": test_namespace,
            "template_namespace": _qualify_expression(args["templateNamespace"], imports, local_strings),
            "template": _qualify_expression(args["template"], imports, local_strings),
            "timeout": timeout,
            "owner": owner,
            "method": method,
        }

        out.append(text[cursor:match.start()])
        out.append(match.group("indent") + _MARKER + json.dumps(payload, sort_keys=True, separators=(",", ":")))
        cursor = end
    return "".join(out)


def _read_markers(root: Path) -> list[dict[str, object]]:
    entries: list[dict[str, object]] = []
    if not root.is_dir():
        return entries
    for path in sorted(root.rglob("*.java")):
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped.startswith(_MARKER):
                continue
            payload = json.loads(stripped[len(_MARKER):])
            entries.append(payload)
    return entries


def generate_gametest_registry(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    if version_tuple(minecraft) < version_tuple("1.21.11") or platform != "neoforge":
        return 0
    game_root = destination_root / "src" / "gametest" / "java"
    entries = _read_markers(game_root)
    if not entries:
        return 0

    entries.sort(key=lambda item: (str(item["test_namespace"]), str(item["method"]), str(item["owner"])))
    seen: set[tuple[str, str]] = set()
    for entry in entries:
        key = (str(entry["test_namespace"]), str(entry["method"]).lower())
        if key in seen:
            raise ValueError(f"duplicate generated GameTest id: {key[0]}:{key[1]}")
        seen.add(key)

    namespaces: list[str] = []
    for entry in entries:
        ns = str(entry["test_namespace"])
        if ns not in namespaces:
            namespaces.append(ns)
    env_vars = {ns: f"environment{index}" for index, ns in enumerate(namespaces)}

    lines = [
        "package buildcraft.gametest;",
        "",
        "import net.minecraft.core.registries.BuiltInRegistries;",
        "import net.minecraft.gametest.framework.FunctionGameTestInstance;",
        "import net.minecraft.gametest.framework.GameTestHelper;",
        "import net.minecraft.gametest.framework.TestData;",
        "import net.minecraft.resources.Identifier;",
        "import net.minecraft.resources.ResourceKey;",
        "import net.minecraft.world.level.block.Rotation;",
        "import net.neoforged.bus.api.SubscribeEvent;",
        "import net.neoforged.fml.common.EventBusSubscriber;",
        "import net.neoforged.neoforge.event.RegisterGameTestsEvent;",
        "import net.neoforged.neoforge.registries.RegisterEvent;",
        "",
        "@EventBusSubscriber(modid = buildcraft.lib.BCLib.MODID)",
        "public final class BuildCraftGeneratedGameTests {",
        "    private BuildCraftGeneratedGameTests() {}",
        "",
        "    @FunctionalInterface",
        "    private interface CheckedGameTest {",
        "        void run(GameTestHelper helper) throws Exception;",
        "    }",
        "",
        "    private static void invoke(GameTestHelper helper, CheckedGameTest test) {",
        "        try {",
        "            test.run(helper);",
        "        } catch (RuntimeException exception) {",
        "            throw exception;",
        "        } catch (Exception exception) {",
        "            throw new RuntimeException(exception);",
        "        }",
        "    }",
        "",
        "    @SubscribeEvent",
        "    public static void registerFunctions(RegisterEvent event) {",
        "        event.register(BuiltInRegistries.TEST_FUNCTION.key(), registry -> {",
    ]
    for entry in entries:
        ns = str(entry["test_namespace"])
        method = str(entry["method"])
        owner = str(entry["owner"])
        path = method.lower()
        lines.append(
            f"            registry.register(Identifier.fromNamespaceAndPath({ns}, \"{path}\"), helper -> invoke(helper, {owner}::{method}));"
        )
    lines.extend([
        "        });",
        "    }",
        "",
        "    @SubscribeEvent",
        "    public static void registerInstances(RegisterGameTestsEvent event) {",
    ])
    for ns in namespaces:
        lines.append(
            f"        var {env_vars[ns]} = event.registerEnvironment(Identifier.fromNamespaceAndPath({ns}, \"default\"));"
        )
    if namespaces:
        lines.append("")
    for index, entry in enumerate(entries):
        ns = str(entry["test_namespace"])
        template_ns = str(entry["template_namespace"])
        template = str(entry["template"])
        method = str(entry["method"])
        timeout = int(entry["timeout"])
        path = method.lower()
        lines.extend([
            f"        Identifier test{index} = Identifier.fromNamespaceAndPath({ns}, \"{path}\");",
            f"        event.registerTest(test{index}, new FunctionGameTestInstance(",
            f"            ResourceKey.create(BuiltInRegistries.TEST_FUNCTION.key(), test{index}),",
            "            new TestData<>(",
            f"                {env_vars[ns]},",
            f"                Identifier.fromNamespaceAndPath({template_ns}, {template}),",
            f"                {timeout}, 0, true, Rotation.NONE, false, 1, 1, false",
            "            )",
            "        ));",
        ])
    lines.extend([
        "    }",
        "}",
        "",
    ])

    output = game_root / "buildcraft" / "gametest" / ("BuildCraftGeneratedGameTests" + ".java")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8", newline="")
    return 1


__all__ = ["transform_gametest_source", "generate_gametest_registry"]
