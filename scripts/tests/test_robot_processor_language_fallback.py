"""Verify the robot processor has a localized fallback independent of external packs."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
LANG_DIR = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/lang'

def test_processor_language_fallback():
    en = json.loads((LANG_DIR / 'en_us.json').read_text(encoding='utf-8'))
    ru = json.loads((LANG_DIR / 'ru_ru.json').read_text(encoding='utf-8'))
    assert en.keys() == ru.keys()
    assert ru['block.buildcraftrobotics.robot_processor'] == 'Панель управления'
    assert ru['item.buildcraftrobotics.controller'] == 'Контроллер роботов'
    assert len(ru) >= 140
    assert all(not v.startswith('gui.buildcraftrobotics.') for v in ru.values())
    for k, e in en.items():
        assert isinstance(e, str) and isinstance(ru[k], str)
        assert e and ru[k]
        placeholders = lambda s: re.findall(r'%(?:\d+\$)?s', s)
        assert placeholders(e) == placeholders(ru[k]), k

if __name__ == '__main__':
    test_processor_language_fallback()
    print('Robot processor language fallback OK')
