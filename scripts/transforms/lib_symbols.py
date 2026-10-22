"""Lexical aliases for retained BuildCraft render/text boundary consumers.

Only type names change. Strings, comments, character literals and text blocks
are opaque, and no method body is inferred from a class or source path.
"""
from __future__ import annotations
import re
from source_preprocessor import version_tuple
from .java_symbols import OPAQUE

ALIASES = {
    'net.minecraft.client.renderer.MultiBufferSource':
        'buildcraft.lib.compat.minecraft.render.BCVertexBuffers',
    'net.minecraft.ChatFormatting':
        'buildcraft.lib.compat.minecraft.text.BCTextFormat',
}


def upgrade_lib_symbols(text: str, *, minecraft: str, relative: str) -> str:
    if not relative.endswith('.java') or version_tuple(minecraft) < version_tuple('26.2'):
        return text
    if not any(old in text for old in ALIASES):
        return text
    code = OPAQUE.sub(lambda match: ' ' * len(match.group()), text)
    selected = {old: new for old, new in ALIASES.items() if old in code}
    if not selected:
        return text
    imported = {old.rsplit('.', 1)[1]: new.rsplit('.', 1)[1]
                for old, new in selected.items()
                if re.search(r'^import\s+' + re.escape(old) + r'\s*;', code, re.M)}

    def rewrite(segment: str) -> str:
        for old, new in selected.items():
            segment = re.sub(r'\b' + re.escape(old) + r'\b', new, segment)
        for old, new in imported.items():
            segment = re.sub(r'\b' + old + r'\b', new, segment)
        return segment

    pieces = []
    offset = 0
    for match in OPAQUE.finditer(text):
        pieces.extend((rewrite(text[offset:match.start()]), match.group()))
        offset = match.end()
    pieces.append(rewrite(text[offset:]))
    return ''.join(pieces)
