"""Lexical fingerprints of the supplied baseline, not a second implementation.

Only loader-specific vocabulary is normalized. String
contents (config keys, comments emitted into configs, defaults) remain exact.
"""
from __future__ import annotations
import hashlib
import re

JAVA_TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[A-Za-z_$][A-Za-z_0-9$]*|\d+(?:\.\d+)?|[^\s]')
CONFIGS = ('core/BCCoreConfig', 'builders/BCBuildersConfig', 'energy/BCEnergyConfig', 'silicon/BCSiliconConfig', 'transport/BCTransportConfig')


def config_tokens(source: str) -> str:
    source = re.sub(r'^import [^;]+;\s*', '', source, flags=re.M)
    source = source.replace('ForgeConfigSpec', 'BCConfigSpec').replace('ModConfigSpec', 'BCConfigSpec')
    source = re.sub(r'ModConfigEvent\.(?:Loading|Reloading) event', 'String modId', source)
    source = source.replace('event.getConfig().getModId()', 'modId')
    return '\n'.join(t for t in JAVA_TOKEN.findall(source) if not t.startswith(('//', '/*')))


def digest(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def catalog_entries(source: str) -> list[str]:
    """Ordered literal content registration names, retaining their catalog owner."""
    # Comments cannot contribute registration operations; literals stay opaque.
    source = JAVA_TOKEN.sub(lambda m: ' ' * len(m[0]) if m[0].startswith(('//', '/*')) else m[0], source)
    direct = re.compile(r'(?P<owner>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.register\(\s*(?P<name>"(?:\\.|[^"\\])*")')
    compat = re.compile(r'RegistryCompat\.register(?:Block|Item)\(\s*(?P<owner>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*,\s*(?P<name>"(?:\\.|[^"\\])*")')
    entries = [(m.start(), m['owner'] + ':' + m['name']) for pattern in (direct, compat) for m in pattern.finditer(source)]
    return [value for _, value in sorted(entries)]
