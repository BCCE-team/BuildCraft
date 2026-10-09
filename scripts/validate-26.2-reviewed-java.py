#!/usr/bin/env python3
"""Pin reviewed post-compiler-fix 26.2 Java views without relaxing frozen 26.1.2."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from source_config import load_properties,target_layout
from source_layout import resolve_effective_source,_materialize_text_file
from tempfile import TemporaryDirectory

ROOT=Path(__file__).resolve().parents[1]
MODULES=('robotics','builders','transport','silicon')


def main() -> int:
    props=load_properties()
    layout=target_layout('26.2-neoforge',props)
    downports=tuple(x for x in (layout.family_downport_root,layout.family_platform_downport_root) if x)
    total=0
    with TemporaryDirectory(prefix='bc-262-reviewed-') as temp:
        out=Path(temp)
        for module in MODULES:
            manifest=json.loads((ROOT/f'build-config/materialized-baselines/26.2-{module}.json').read_text())
            expected=manifest['files']
            if manifest['file_count']!=len(expected):
                raise RuntimeError(f'{module}: invalid reviewed source manifest size')
            for logical,sha in expected.items():
                source=resolve_effective_source(layout,props,logical)
                if source is None:raise RuntimeError(f'{module}: missing {logical}')
                dest=out/logical
                _materialize_text_file(source,dest,logical_relative=logical,minecraft='26.2',family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports))
                if hashlib.sha256(dest.read_bytes()).hexdigest()!=sha:
                    raise RuntimeError(f'{module}: reviewed 26.2 Java changed: {logical}')
            total+=len(expected)
            print(f'{module}: {len(expected)} reviewed 26.2 Java sources byte-identical')
    print(f'26.2 reviewed Java views OK: {total} files')
    return 0

if __name__=='__main__':raise SystemExit(main())
