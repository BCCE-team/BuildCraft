# BCCE balance configuration (modpack mode)

BCCE generates `config/buildcraft-machines.ini` and `config/buildcraft-robots.ini` at first startup. These files list machine and robot properties but **do not change gameplay by default**.

`config/buildcraft-machines.ini` and `config/buildcraft-robots.ini` has the mode switch:

```ini
[meta]
schema_version = 1
; false = use original BCCE built-in balance values (ignore edited machine and robot values)
use_config = false
; true = apply BOTH INI files to machine and robot balance
use_config = true
```