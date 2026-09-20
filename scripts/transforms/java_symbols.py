"""Narrow lexical symbol downports for native canonical modern sources.

Only two reviewed Minecraft type aliases are supported. Comments, strings,
character literals and text blocks are opaque. No method bodies or gameplay
semantics are inferred, and no BuildCraft class/path is special-cased.
"""
from __future__ import annotations
import re
from source_preprocessor import _version_tuple

OPAQUE = re.compile(r'"""[\s\S]*?"""|//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def downport_symbols(text: str, *, minecraft: str, relative: str) -> str:
    if not relative.endswith('.java') or _version_tuple(minecraft) >= _version_tuple('1.21.11'):
        return text
    # A matching import is required: unrelated user-defined Identifier classes
    # must never be renamed merely because they share a simple name.
    imports = OPAQUE.sub(lambda match: ' ' * len(match.group()), text)
    identifier = bool(re.search(r'^import net\.minecraft\.resources\.Identifier;', imports, re.M))
    render_type = bool(re.search(r'^import net\.minecraft\.client\.renderer\.rendertype\.RenderType;', imports, re.M))
    if not (identifier or render_type):
        return text

    def segment(code: str) -> str:
        if identifier:
            code = re.sub(r'\bIdentifier\b', 'ResourceLocation', code)
        if render_type:
            code = re.sub(r'\bnet\.minecraft\.client\.renderer\.rendertype\.RenderType\b',
                          'net.minecraft.client.renderer.RenderType', code)
        return code

    result: list[str] = []
    offset = 0
    for match in OPAQUE.finditer(text):
        result.extend((segment(text[offset:match.start()]), match.group()))
        offset = match.end()
    result.append(segment(text[offset:]))
    return ''.join(result)
