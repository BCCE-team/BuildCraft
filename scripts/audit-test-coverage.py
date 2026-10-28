#!/usr/bin/env python3
"""Audit the entire BCCE scripts tree for repeated tests and planning-era names.

Uses Python's AST instead of textual name matching to avoid considering domain
concepts such as ItemTransitPhase or engine stages as obsolete milestone labels.
"""
from __future__ import annotations

import argparse
import ast
from collections import defaultdict
import json
from pathlib import Path
import re
import sys

OBSOLETE_FILE = re.compile(r"(?:^|_)(?:phase|stage|step)[_-]?\d+(?:_|\.|$)", re.I)
OBSOLETE_CLASS = re.compile(r"(?:^|\b)(?:RecipePhase(?:One|Two|Three|Four|Five|Six|Seven|Eight|Nine|\d+)|(?:Phase|Stage|Step)\d+(?:Test|Tests))\b")


def fingerprints(root: Path) -> dict:
    if not (root / "scripts").is_dir():
        raise FileNotFoundError(f"Missing scripts: {root}")
    tests: dict[str,list[str]] = defaultdict(list)
    helpers: dict[str,list[str]] = defaultdict(list)
    names: list[str] = []
    python_files = 0
    suite_files = 0
    test_methods = 0
    for path in sorted((root / "scripts").rglob("*.py")):
        if any(part in {"__pycache__", ".venv", "build"} for part in path.parts):
            continue
        python_files += 1
        rel = str(path.relative_to(root)).replace("\\", "/")
        src = path.read_text(encoding="utf-8-sig")
        try:
            tree = ast.parse(src, filename=rel)
        except SyntaxError as err:
            names.append(f"Invalid Python syntax {rel}: {err}")
            continue
        if OBSOLETE_FILE.search(path.name):
            names.append(f"Historic filename {rel}")
        # Identical wrappers may call different imported fixtures. Resolve
        # import bindings so `from a import run; print(run())` is not treated
        # as the same check as `from b import run; print(run())`.
        imported: dict[str, str] = {}
        for statement in tree.body:
            if isinstance(statement, ast.ImportFrom):
                source_module = "." * statement.level + (statement.module or "")
                for alias in statement.names:
                    if alias.name != "*":
                        imported[alias.asname or alias.name] = f"{source_module}.{alias.name}"
            elif isinstance(statement, ast.Import):
                for alias in statement.names:
                    bound_name = alias.asname or alias.name.split(".")[0]
                    imported[bound_name] = alias.name if alias.asname else bound_name
        has_test = False
        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef) and OBSOLETE_CLASS.search(node.name):
                names.append(f"Historic class name {rel}:{node.lineno}: {node.name}")
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
                has_test = True
                test_methods += 1
                # Compare executable implementations without the test's descriptive name.
                body = ast.dump(ast.Module(body=node.body,type_ignores=[]), include_attributes=False)
                # Only loaded imports are relevant; locally assigned variables
                # and method parameters must not be mistaken for module imports.
                locals_in_method = {arg.arg for arg in (*node.args.posonlyargs, *node.args.args,
                                                      *node.args.kwonlyargs)}
                if node.args.vararg:
                    locals_in_method.add(node.args.vararg.arg)
                if node.args.kwarg:
                    locals_in_method.add(node.args.kwarg.arg)
                locals_in_method.update(
                    name.id for name in ast.walk(node)
                    if isinstance(name, ast.Name) and isinstance(name.ctx, (ast.Store, ast.Del))
                )
                imports_used = tuple(sorted({
                    (name.id, imported[name.id])
                    for name in ast.walk(node)
                    if isinstance(name, ast.Name) and isinstance(name.ctx, ast.Load)
                    and name.id in imported and name.id not in locals_in_method
                }))
                tests[(body, imports_used)].append(f"{rel}:{node.lineno}:{node.name}")
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.col_offset == 0 and not node.name.startswith("test_"):
                key = ast.dump(ast.Module(body=node.body,type_ignores=[]),include_attributes=False)
                helpers[key].append(f"{rel}:{node.lineno}:{node.name}")
        if has_test:
            suite_files += 1
    exact_tests = [g for g in tests.values() if len(g) > 1]
    exact_helpers = [g for g in helpers.values() if len(set(x.split(':')[0] for x in g)) > 1]
    return {
        "scanned_python_scripts":python_files,
        "files_containing_tests":suite_files,
        "test_methods":test_methods,
        "historical_naming_findings":names,
        "duplicate_test_implementations":exact_tests,
        "repeated_top_level_helper_implementations":exact_helpers,
        "note":"Matching function bodies are candidates for manual consolidation; fixtures and side effects must be checked before removing any test.",
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo",type=Path,default=Path.cwd())
    p.add_argument("--json",type=Path)
    p.add_argument("--strict",action="store_true",help="Fail for obsolete names or exact duplicate test implementations")
    args=p.parse_args()
    try:
        report=fingerprints(args.repo.resolve())
    except (OSError,ValueError) as e:
        print(str(e),file=sys.stderr)
        return 2
    text=json.dumps(report,indent=2,ensure_ascii=False)+"\n"
    if args.json:
        args.json.write_text(text,encoding="utf-8")
    print(text)
    return int(args.strict and bool(report["historical_naming_findings"] or report["duplicate_test_implementations"]))


if __name__ == "__main__":
    raise SystemExit(main())
